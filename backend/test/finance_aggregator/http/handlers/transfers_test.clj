(ns finance-aggregator.http.handlers.transfers-test
  "Tests for the transfer-matching handlers."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [charred.api :as json]
   [datalevin.core :as d]
   [finance-aggregator.http.handlers.transfers :as handlers]
   [finance-aggregator.test-utils.setup :as setup]))

(use-fixtures :each setup/with-empty-db)

(defn- day [n] (java.util.Date. (* (long n) 86400000)))

(defn- make-account! [external-id name]
  (d/transact! setup/*test-conn* [{:institution/id (str "inst-" external-id)
                                   :institution/name "Test Bank"}])
  (d/transact! setup/*test-conn* [{:account/external-id external-id
                                   :account/external-name name
                                   :account/institution [:institution/id (str "inst-" external-id)]}])
  (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:account/external-id external-id])))

(defn- make-tx! [external-id account-id amount day-n]
  (d/transact! setup/*test-conn* [{:transaction/external-id external-id
                                   :transaction/account account-id
                                   :transaction/amount amount
                                   :transaction/payee "Transfer"
                                   :transaction/posted-date (day day-n)}])
  (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:transaction/external-id external-id])))

(defn- body [response] (json/read-json (:body response) :key-fn keyword))

(deftest suggestions-handler-test
  (testing "GET suggestions returns 200 with candidate pairs"
    (let [checking (make-account! "checking" "Checking")
          savings (make-account! "savings" "Savings")
          out (make-tx! "out-1" checking -100.00M 10)
          in (make-tx! "in-1" savings 100.00M 11)
          response ((handlers/suggestions-handler {:db-conn setup/*test-conn*}) {})
          data (:data (body response))]
      (is (= 200 (:status response)))
      (is (= 1 (count data)))
      (is (= out (get-in (first data) [:outflow :db/id])))
      (is (= in (get-in (first data) [:inflow :db/id]))))))

(deftest confirm-and-unmatch-handler-test
  (testing "POST confirm links both legs; DELETE unmatches"
    (let [checking (make-account! "c2" "Checking")
          savings (make-account! "s2" "Savings")
          out (make-tx! "out-2" checking -100.00M 10)
          in (make-tx! "in-2" savings 100.00M 10)
          confirm ((handlers/confirm-handler {:db-conn setup/*test-conn*})
                   {:body-params {:outflowId out :inflowId in}})]
      (is (= 200 (:status confirm)))
      (is (= in (get-in (d/pull (d/db setup/*test-conn*) '[{:transaction/transfer-pair [:db/id]}] out)
                        [:transaction/transfer-pair :db/id])))
      (let [unmatch ((handlers/unmatch-handler {:db-conn setup/*test-conn*})
                     {:path-params {:id (str out)}})]
        (is (= 200 (:status unmatch)))
        (is (true? (get-in (body unmatch) [:data :unmatched])))
        (is (nil? (:transaction/transfer-pair
                   (d/pull (d/db setup/*test-conn*) '[{:transaction/transfer-pair [:db/id]}] out))))))))

(deftest reject-handler-test
  (testing "POST reject records the pair and removes it from later suggestions"
    (let [checking (make-account! "c3" "Checking")
          savings (make-account! "s3" "Savings")
          out (make-tx! "out-3" checking -100.00M 10)
          in (make-tx! "in-3" savings 100.00M 10)]
      ((handlers/reject-handler {:db-conn setup/*test-conn*})
       {:body-params {:aId out :bId in}})
      (let [response ((handlers/suggestions-handler {:db-conn setup/*test-conn*}) {})]
        (is (empty? (:data (body response))))))))

(deftest candidates-handler-test
  (testing "GET candidates returns inverse counterparts for a transaction"
    (let [checking (make-account! "c4" "Checking")
          savings (make-account! "s4" "Savings")
          src (make-tx! "src-4" checking -100.00M 10)
          counterpart (make-tx! "cp-4" savings 100.00M 12)
          response ((handlers/candidates-handler {:db-conn setup/*test-conn*})
                    {:query-params {"transactionId" (str src)}})
          data (:data (body response))]
      (is (= 200 (:status response)))
      (is (= [counterpart] (map :db/id data))))))

(deftest handler-bad-input-test
  (testing "confirm with a missing id raises :bad-request (a clean 400, not a 500)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid or missing"
                          ((handlers/confirm-handler {:db-conn setup/*test-conn*})
                           {:body-params {:outflowId 5}}))))

  (testing "reject with a missing id raises :bad-request"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid or missing"
                          ((handlers/reject-handler {:db-conn setup/*test-conn*})
                           {:body-params {}}))))

  (testing "candidates with no transactionId raises :bad-request"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid or missing"
                          ((handlers/candidates-handler {:db-conn setup/*test-conn*})
                           {:query-params {}}))))

  (testing "unmatch with a non-numeric id raises :bad-request rather than a silent no-op"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid or missing"
                          ((handlers/unmatch-handler {:db-conn setup/*test-conn*})
                           {:path-params {:id "abc"}})))))

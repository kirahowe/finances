(ns finance-aggregator.http.handlers.transactions-test
  "Tests for the transaction split handler."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [charred.api :as json]
   [datalevin.core :as d]
   [finance-aggregator.http.handlers.transactions :as handlers]
   [finance-aggregator.db.categories :as categories]
   [finance-aggregator.db.transactions :as db-transactions]
   [finance-aggregator.test-utils.setup :as setup])
  (:import [java.util Date]))

(use-fixtures :each setup/with-empty-db)

(defn- make-category! [name ident]
  (:db/id (categories/create! setup/*test-conn*
                              {:category/name name :category/type :expense :category/ident ident})))

(defn- make-tx!
  ([external-id amount] (make-tx! external-id amount nil))
  ([external-id amount description]
   (d/transact! setup/*test-conn* [(cond-> {:transaction/external-id external-id
                                            :transaction/amount amount
                                            :transaction/payee "Costco"
                                            :transaction/posted-date (Date.)}
                                     description (assoc :transaction/description description))])
   (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:transaction/external-id external-id]))))

(defn- call-description [tx-id description]
  ((handlers/set-transaction-description-handler {:db-conn setup/*test-conn*})
   {:path-params {:id (str tx-id)} :body-params {:description description}}))

(defn- call-splits [tx-id splits]
  ((handlers/set-transaction-splits-handler {:db-conn setup/*test-conn*})
   {:path-params {:id (str tx-id)} :body-params {:splits splits}}))

(defn- call-reviewed [tx-id reviewed?]
  ((handlers/set-transaction-reviewed-handler {:db-conn setup/*test-conn*})
   {:path-params {:id (str tx-id)} :body-params {:reviewed reviewed?}}))

(defn- call-split-reviewed [tx-id split-id reviewed?]
  ((handlers/set-split-reviewed-handler {:db-conn setup/*test-conn*})
   {:path-params {:id (str tx-id) :splitId (str split-id)} :body-params {:reviewed reviewed?}}))

(defn- call-split-memo [tx-id split-id memo]
  ((handlers/set-split-memo-handler {:db-conn setup/*test-conn*})
   {:path-params {:id (str tx-id) :splitId (str split-id)} :body-params {:memo memo}}))

(deftest set-transaction-splits-handler-test
  (testing "PUT splits returns 200 and the transaction with its parts"
    (let [g (make-category! "Groceries" :category/groceries)
          h (make-category! "Household" :category/household)
          tx-id (make-tx! "tx-h1" -100.00M)
          response (call-splits tx-id [{:amount "-60.00" :categoryId g}
                                       {:amount "-40.00" :categoryId h}])
          body (json/read-json (:body response) :key-fn keyword)
          parts (sort-by :split/order (get-in body [:data :transaction/splits]))]
      (is (= 200 (:status response)))
      (is (true? (:success body)))
      (is (= 2 (count parts)))
      ;; camelCase :categoryId is mapped onto the :split/category ref
      (is (= g (get-in (first parts) [:split/category :db/id])))
      (is (= h (get-in (second parts) [:split/category :db/id])))))

  (testing "amounts sent as strings are stored exactly (bigdec, no double drift)"
    (let [a (make-category! "A" :category/a)
          b (make-category! "B" :category/b)
          tx-id (make-tx! "tx-h2" -0.30M)
          response (call-splits tx-id [{:amount "-0.10" :categoryId a}
                                       {:amount "-0.20" :categoryId b}])
          ;; Pull the stored bigdec amounts straight from the DB and sum exactly.
          stored (->> (d/pull (d/db setup/*test-conn*) db-transactions/transaction-pull-pattern tx-id)
                      :transaction/splits
                      (map :split/amount))]
      (is (= 200 (:status response)))
      (is (== -0.30M (reduce + 0M stored)))))

  (testing "non-reconciling splits raise a bad-request"
    (let [a (make-category! "A2" :category/a2)
          b (make-category! "B2" :category/b2)
          tx-id (make-tx! "tx-h3" -100.00M)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"add up"
                            (call-splits tx-id [{:amount "-60.00" :categoryId a}
                                                {:amount "-30.00" :categoryId b}])))))

  (testing "an empty splits vector clears the splits"
    (let [a (make-category! "A3" :category/a3)
          b (make-category! "B3" :category/b3)
          tx-id (make-tx! "tx-h4" -100.00M)]
      (call-splits tx-id [{:amount "-60.00" :categoryId a} {:amount "-40.00" :categoryId b}])
      (let [response (call-splits tx-id [])
            body (json/read-json (:body response) :key-fn keyword)]
        (is (= 200 (:status response)))
        (is (empty? (get-in body [:data :transaction/splits])))))))

(deftest set-transaction-description-handler-test
  (testing "PUT description returns 200 and the effective description"
    (let [tx-id (make-tx! "tx-desc-h1" -100.00M "STARBUCKS #1234")
          response (call-description tx-id "Coffee with Sam")
          body (json/read-json (:body response) :key-fn keyword)]
      (is (= 200 (:status response)))
      (is (true? (:success body)))
      (is (= "Coffee with Sam" (get-in body [:data :transaction/user-description])))
      (is (= "Coffee with Sam" (get-in body [:data :transaction/effective-description])))
      ;; The imported description rides along untouched (for a future "view original").
      (is (= "STARBUCKS #1234" (get-in body [:data :transaction/description])))))

  (testing "PUT a blank description clears the override, falling back to the import"
    (let [tx-id (make-tx! "tx-desc-h2" -100.00M "IMPORTED")]
      (call-description tx-id "Override")
      (let [response (call-description tx-id "")
            body (json/read-json (:body response) :key-fn keyword)]
        (is (= 200 (:status response)))
        (is (nil? (get-in body [:data :transaction/user-description])))
        (is (= "IMPORTED" (get-in body [:data :transaction/effective-description])))))))

(deftest set-split-memo-handler-test
  (testing "PUT split memo returns 200 and the refreshed parent with the memo set on one part"
    (let [g (make-category! "Groceries" :category/groceries)
          h (make-category! "Household" :category/household)
          tx-id (make-tx! "tx-memo-h1" -100.00M)
          splits-body (json/read-json
                       (:body (call-splits tx-id [{:amount "-60.00" :categoryId g}
                                                  {:amount "-40.00" :categoryId h}]))
                       :key-fn keyword)
          [s1 s2] (map :db/id (sort-by :split/order (get-in splits-body [:data :transaction/splits])))
          response (call-split-memo tx-id s1 "paper towels")
          body (json/read-json (:body response) :key-fn keyword)
          parts (sort-by :split/order (get-in body [:data :transaction/splits]))]
      (is (= 200 (:status response)))
      (is (true? (:success body)))
      (is (= "paper towels" (:split/memo (first parts))))
      ;; The sibling is untouched.
      (is (nil? (:split/memo (second parts))))
      (is (= s2 (:db/id (second parts))))))

  (testing "PUT a blank split memo clears it"
    (let [g (make-category! "G2" :category/g2)
          h (make-category! "H2" :category/h2)
          tx-id (make-tx! "tx-memo-h2" -100.00M)
          splits-body (json/read-json
                       (:body (call-splits tx-id [{:amount "-60.00" :categoryId g}
                                                  {:amount "-40.00" :categoryId h}]))
                       :key-fn keyword)
          [s1] (map :db/id (sort-by :split/order (get-in splits-body [:data :transaction/splits])))]
      (call-split-memo tx-id s1 "temp")
      (let [response (call-split-memo tx-id s1 "")
            body (json/read-json (:body response) :key-fn keyword)
            parts (sort-by :split/order (get-in body [:data :transaction/splits]))]
        (is (= 200 (:status response)))
        (is (nil? (:split/memo (first parts))))))))

(deftest set-transaction-reviewed-handler-test
  (testing "PUT reviewed=true returns 200 and the reviewed transaction"
    (let [tx-id (make-tx! "tx-rev-h1" -100.00M)
          response (call-reviewed tx-id true)
          body (json/read-json (:body response) :key-fn keyword)]
      (is (= 200 (:status response)))
      (is (true? (:success body)))
      (is (true? (get-in body [:data :transaction/reviewed])))))

  (testing "PUT reviewed=false clears it"
    (let [tx-id (make-tx! "tx-rev-h2" -100.00M)]
      (call-reviewed tx-id true)
      (let [response (call-reviewed tx-id false)
            body (json/read-json (:body response) :key-fn keyword)]
        (is (= 200 (:status response)))
        ;; Cleared flag is absent from the response, nil-punning to not-reviewed.
        (is (nil? (get-in body [:data :transaction/reviewed])))))))

(deftest set-split-reviewed-handler-test
  (testing "PUT split reviewed marks the part and rolls the parent up when all are reviewed"
    (let [g (make-category! "Groceries" :category/groceries)
          h (make-category! "Household" :category/household)
          tx-id (make-tx! "tx-rev-h3" -100.00M)
          splits-body (json/read-json
                       (:body (call-splits tx-id [{:amount "-60.00" :categoryId g}
                                                  {:amount "-40.00" :categoryId h}]))
                       :key-fn keyword)
          [s1 s2] (map :db/id (sort-by :split/order (get-in splits-body [:data :transaction/splits])))
          after-first (json/read-json (:body (call-split-reviewed tx-id s1 true)) :key-fn keyword)
          after-second (json/read-json (:body (call-split-reviewed tx-id s2 true)) :key-fn keyword)]
      ;; One part reviewed: the parent roll-up is still not reviewed.
      (is (false? (get-in after-first [:data :transaction/reviewed])))
      ;; Both parts reviewed: the parent row reads as reviewed.
      (is (true? (get-in after-second [:data :transaction/reviewed]))))))

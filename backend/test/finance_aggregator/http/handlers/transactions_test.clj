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

(defn- make-tx! [external-id amount]
  (d/transact! setup/*test-conn* [{:transaction/external-id external-id
                                   :transaction/amount amount
                                   :transaction/payee "Costco"
                                   :transaction/posted-date (Date.)}])
  (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:transaction/external-id external-id])))

(defn- call-splits [tx-id splits]
  ((handlers/set-transaction-splits-handler {:db-conn setup/*test-conn*})
   {:path-params {:id (str tx-id)} :body-params {:splits splits}}))

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
          stored (->> (d/pull (d/db setup/*test-conn*) db-transactions/splits-pull-pattern tx-id)
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

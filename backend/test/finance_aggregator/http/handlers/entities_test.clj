(ns finance-aggregator.http.handlers.entities-test
  "Tests for entity listing handlers with month filtering"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [finance-aggregator.http.handlers.entities :as entities]
   [finance-aggregator.test-utils.setup :as setup]
   [charred.api :as json]
   [datalevin.core :as d])
  (:import [java.util Date]))

(use-fixtures :each setup/with-empty-db)

;; Helper to create test transactions with specific dates
(defn make-test-transaction
  "Create a transaction map for testing with specified posted-date."
  [external-id payee amount posted-date]
  {:transaction/external-id external-id
   :transaction/payee payee
   :transaction/amount (bigdec amount)
   :transaction/posted-date posted-date})

(defn date-for
  "Create a Date for YYYY-MM-DD at midnight UTC."
  [year month day]
  (-> (java.time.LocalDate/of year month day)
      (.atStartOfDay (java.time.ZoneId/of "UTC"))
      .toInstant
      Date/from))

(deftest list-transactions-handler-no-filter-test
  (testing "returns all transactions when no month param"
    ;; Insert test transactions
    (d/transact! setup/*test-conn*
                 [(make-test-transaction "tx-1" "Starbucks" 5.00 (date-for 2025 1 15))
                  (make-test-transaction "tx-2" "Amazon" 25.00 (date-for 2025 2 10))
                  (make-test-transaction "tx-3" "Walmart" 50.00 (date-for 2025 1 20))])

    (let [handler (entities/list-transactions-handler {:db-conn setup/*test-conn*})
          request {:query-params {}}
          response (handler request)]
      (is (= 200 (:status response)))
      (let [body (json/read-json (:body response) :key-fn keyword)
            data (:data body)]
        (is (= 3 (count data)))))))

(deftest list-transactions-handler-month-filter-test
  (testing "filters transactions by month when month param provided"
    ;; Insert test transactions spanning multiple months
    (d/transact! setup/*test-conn*
                 [(make-test-transaction "tx-jan-1" "Starbucks" 5.00 (date-for 2025 1 15))
                  (make-test-transaction "tx-feb-1" "Amazon" 25.00 (date-for 2025 2 10))
                  (make-test-transaction "tx-jan-2" "Walmart" 50.00 (date-for 2025 1 20))])

    (let [handler (entities/list-transactions-handler {:db-conn setup/*test-conn*})
          request {:query-params {"month" "2025-01"}}
          response (handler request)]
      (is (= 200 (:status response)))
      (let [body (json/read-json (:body response) :key-fn keyword)
            data (:data body)]
        ;; Should only have the 2 January transactions
        (is (= 2 (count data)))
        (is (every? #(or (= "tx-jan-1" (:transaction/external-id %))
                         (= "tx-jan-2" (:transaction/external-id %)))
                    data))))))

(deftest list-transactions-handler-empty-month-test
  (testing "returns empty list for month with no transactions"
    ;; Insert transactions NOT in the queried month
    (d/transact! setup/*test-conn*
                 [(make-test-transaction "tx-1" "Test" 5.00 (date-for 2025 1 15))])

    (let [handler (entities/list-transactions-handler {:db-conn setup/*test-conn*})
          request {:query-params {"month" "2024-06"}}
          response (handler request)]
      (is (= 200 (:status response)))
      (let [body (json/read-json (:body response) :key-fn keyword)
            data (:data body)]
        (is (= 0 (count data)))))))

(deftest list-transactions-handler-boundaries-test
  (testing "handles transactions at month boundaries"
    ;; Add transactions exactly at month start and end
    (d/transact! setup/*test-conn*
                 [(make-test-transaction "tx-mar-first" "First of March" 10.00 (date-for 2025 3 1))
                  (make-test-transaction "tx-mar-last" "Last of March" 20.00 (date-for 2025 3 31))
                  (make-test-transaction "tx-apr-first" "First of April" 30.00 (date-for 2025 4 1))])

    (let [handler (entities/list-transactions-handler {:db-conn setup/*test-conn*})
          request {:query-params {"month" "2025-03"}}
          response (handler request)]
      (is (= 200 (:status response)))
      (let [body (json/read-json (:body response) :key-fn keyword)
            data (:data body)
            ids (set (map :transaction/external-id data))]
        ;; March should include first and last of March, but NOT first of April
        (is (contains? ids "tx-mar-first"))
        (is (contains? ids "tx-mar-last"))
        (is (not (contains? ids "tx-apr-first")))
        (is (= 2 (count data)))))))

(deftest list-transactions-handler-invalid-month-test
  (testing "returns 400 for invalid month format"
    (let [handler (entities/list-transactions-handler {:db-conn setup/*test-conn*})]
      ;; Invalid format
      (is (thrown-with-msg? Exception #"Invalid month format"
                            (handler {:query-params {"month" "2025-1"}})))
      (is (thrown-with-msg? Exception #"Invalid month format"
                            (handler {:query-params {"month" "invalid"}})))
      (is (thrown-with-msg? Exception #"Month must be between"
                            (handler {:query-params {"month" "2025-13"}}))))))

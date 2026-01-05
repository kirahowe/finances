(ns finance-aggregator.http.integration-test
  "Integration tests for HTTP stack including middleware.

   These tests verify that the full HTTP pipeline works correctly,
   including query parameter parsing from the query string."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [finance-aggregator.http.router :as router]
   [finance-aggregator.test-utils.setup :as setup]
   [charred.api :as json]
   [datalevin.core :as d])
  (:import [java.util Date]))

(use-fixtures :each setup/with-empty-db)

(defn date-for
  "Create a Date for YYYY-MM-DD at midnight UTC."
  [year month day]
  (-> (java.time.LocalDate/of year month day)
      (.atStartOfDay (java.time.ZoneId/of "UTC"))
      .toInstant
      Date/from))

(defn make-test-transaction
  "Create a transaction map for testing with specified posted-date."
  [external-id payee amount posted-date]
  {:transaction/external-id external-id
   :transaction/payee payee
   :transaction/amount (bigdec amount)
   :transaction/posted-date posted-date})

(defn create-test-handler
  "Create the full application handler with all middleware."
  []
  (router/create-handler
   {:db-conn setup/*test-conn*
    :secrets {}
    :plaid-config {}
    :cors-config {:allowed-origins ["*"]}}))

(defn make-request
  "Create a minimal Ring request map for testing.
   This simulates what the HTTP adapter would pass to the handler."
  ([method uri]
   (make-request method uri nil))
  ([method uri query-string]
   {:request-method method
    :uri uri
    :query-string query-string
    :headers {}}))

(deftest full-stack-transactions-month-filter-test
  (testing "Full HTTP stack filters transactions by month query param"
    ;; Insert test transactions spanning multiple months
    (d/transact! setup/*test-conn*
                 [(make-test-transaction "tx-jan-1" "Starbucks" 5.00 (date-for 2025 1 15))
                  (make-test-transaction "tx-feb-1" "Amazon" 25.00 (date-for 2025 2 10))
                  (make-test-transaction "tx-jan-2" "Walmart" 50.00 (date-for 2025 1 20))])

    (let [handler (create-test-handler)
          ;; Create request with query string (like a real browser would)
          ;; The query string needs to be parsed by middleware into :query-params
          request (make-request :get "/api/transactions" "month=2025-01")
          response (handler request)]
      (is (= 200 (:status response)))
      (let [body (json/read-json (:body response) :key-fn keyword)
            data (:data body)]
        ;; Should only have the 2 January transactions - NOT all 3!
        (is (= 2 (count data))
            (str "Expected 2 transactions for January, but got " (count data)
                 ". This indicates query params are not being parsed from the query string. "
                 "Transactions returned: " (pr-str (map :transaction/external-id data))))
        (is (every? #(or (= "tx-jan-1" (:transaction/external-id %))
                         (= "tx-jan-2" (:transaction/external-id %)))
                    data))))))

(deftest full-stack-transactions-no-filter-test
  (testing "Full HTTP stack returns all transactions when no month param"
    ;; Insert test transactions
    (d/transact! setup/*test-conn*
                 [(make-test-transaction "tx-1" "Starbucks" 5.00 (date-for 2025 1 15))
                  (make-test-transaction "tx-2" "Amazon" 25.00 (date-for 2025 2 10))
                  (make-test-transaction "tx-3" "Walmart" 50.00 (date-for 2025 1 20))])

    (let [handler (create-test-handler)
          request (make-request :get "/api/transactions")
          response (handler request)]
      (is (= 200 (:status response)))
      (let [body (json/read-json (:body response) :key-fn keyword)
            data (:data body)]
        (is (= 3 (count data)))))))

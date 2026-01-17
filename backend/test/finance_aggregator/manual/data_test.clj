(ns finance-aggregator.manual.data-test
  "Unit tests for manual account data transformation functions."
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.manual.data :as data])
  (:import
   [java.util Date]))

(deftest test-parse-manual-account
  (testing "Transforms manual account creation request to database format"
    (let [account-data {:name "TD Chequing"
                       :type "checking"
                       :currency "CAD"
                       :institution-name "TD Bank"}
          user-id "test-user"
          result (data/parse-manual-account account-data user-id)]
      (is (string? (:account/external-id result))
          "External ID should be generated")
      (is (.startsWith (:account/external-id result) "manual-")
          "External ID should have manual- prefix")
      (is (= "TD Chequing" (:account/external-name result)))
      (is (= "CAD" (:account/currency result)))
      (is (= :manual (:account/source result)))
      (is (= [:user/id "test-user"] (:account/user result)))
      (is (= [:institution/id "manual-td-bank"] (:account/institution result))
          "Institution should be ref with normalized ID"))))

(deftest test-parse-manual-account-defaults-usd
  (testing "Defaults to USD when currency not provided"
    (let [account-data {:name "Cash"
                       :type "cash"
                       :institution-name "Manual"}
          result (data/parse-manual-account account-data "test-user")]
      (is (= "USD" (:account/currency result))))))

(deftest test-generate-transaction-external-id
  (testing "Generates unique external ID from transaction data"
    (let [account-id "manual-abc123"
          date (Date.)
          amount (bigdec "45.67")
          payee "Grocery Store"
          row-index 0
          result (data/generate-transaction-external-id account-id date amount payee row-index)]
      (is (string? result))
      (is (.startsWith result "csv-")
          "External ID should have csv- prefix")
      (is (> (count result) 10)
          "Should be a hash of reasonable length")))

  (testing "Same data produces same ID (for duplicate detection)"
    (let [account-id "manual-abc123"
          date (Date.)
          amount (bigdec "45.67")
          payee "Grocery Store"
          row-index 0
          id1 (data/generate-transaction-external-id account-id date amount payee row-index)
          id2 (data/generate-transaction-external-id account-id date amount payee row-index)]
      (is (= id1 id2)
          "Same inputs should produce same ID")))

  (testing "Different row index produces different ID"
    (let [account-id "manual-abc123"
          date (Date.)
          amount (bigdec "45.67")
          payee "Grocery Store"
          id1 (data/generate-transaction-external-id account-id date amount payee 0)
          id2 (data/generate-transaction-external-id account-id date amount payee 1)]
      (is (not= id1 id2)
          "Different row indices should produce different IDs"))))

(deftest test-parse-csv-transaction
  (testing "Transforms CSV row to database transaction format"
    (let [row {:date "2024-01-15"
              :amount "45.67"
              :payee "Grocery Store"
              :description "Weekly groceries"}
          account-external-id "manual-abc123"
          user-id "test-user"
          mapping {:date-format "yyyy-MM-dd"}
          row-index 0
          result (data/parse-csv-transaction row account-external-id user-id mapping row-index)]
      (is (string? (:transaction/external-id result)))
      (is (.startsWith (:transaction/external-id result) "csv-"))
      (is (= [:account/external-id "manual-abc123"] (:transaction/account result)))
      (is (= [:user/id "test-user"] (:transaction/user result)))
      (is (instance? Date (:transaction/date result)))
      (is (instance? Date (:transaction/posted-date result)))
      (is (= (:transaction/date result) (:transaction/posted-date result))
          "Date and posted-date should be the same for CSV imports")
      (is (instance? java.math.BigDecimal (:transaction/amount result)))
      (is (= (bigdec "45.67") (:transaction/amount result)))
      (is (= "Grocery Store" (:transaction/payee result)))
      (is (= "Weekly groceries" (:transaction/description result))))))

(deftest test-parse-csv-transaction-negative-amount
  (testing "Handles negative amounts correctly"
    (let [row {:date "2024-01-15"
              :amount "-100.00"
              :payee "Payment"}
          result (data/parse-csv-transaction row "manual-abc" "user" {:date-format "yyyy-MM-dd"} 0)]
      (is (= (bigdec "-100.00") (:transaction/amount result))))))

(deftest test-parse-csv-transaction-amount-with-currency-symbol
  (testing "Strips currency symbols from amount"
    (let [row {:date "2024-01-15"
              :amount "$45.67"
              :payee "Store"}
          result (data/parse-csv-transaction row "manual-abc" "user" {:date-format "yyyy-MM-dd"} 0)]
      (is (= (bigdec "45.67") (:transaction/amount result))))))

(deftest test-parse-csv-transaction-amount-with-commas
  (testing "Handles amounts with comma thousands separators"
    (let [row {:date "2024-01-15"
              :amount "1,234.56"
              :payee "Store"}
          result (data/parse-csv-transaction row "manual-abc" "user" {:date-format "yyyy-MM-dd"} 0)]
      (is (= (bigdec "1234.56") (:transaction/amount result))))))

(deftest test-parse-csv-transaction-optional-description
  (testing "Handles missing description field"
    (let [row {:date "2024-01-15"
              :amount "45.67"
              :payee "Store"}
          result (data/parse-csv-transaction row "manual-abc" "user" {:date-format "yyyy-MM-dd"} 0)]
      (is (nil? (:transaction/description result))))))

(ns finance-aggregator.plaid.data-test
  "Unit tests for Plaid data transformation functions.
   Tests follow SimpleFIN pattern for data transformation."
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.plaid.data :as data])
  (:import
   [java.util Date]))

(deftest test-parse-institution
  (testing "Transforms Plaid institution details to database format"
    (let [institution {:institution_id "ins_123"
                      :name "Chase"
                      :url "https://www.chase.com"
                      :primary_color "#0D1B41"
                      :logo "base64-logo"}
          result (data/parse-institution institution)]
      (is (= "ins_123" (:institution/id result)))
      (is (= "Chase" (:institution/name result)))
      (is (= "https://www.chase.com" (:institution/url result))))))

(deftest test-parse-institution-minimal
  (testing "Handles institution with minimal fields"
    (let [institution {:institution_id "ins_456"
                      :name "Bank of America"
                      :url nil
                      :primary_color nil
                      :logo nil}
          result (data/parse-institution institution)]
      (is (= "ins_456" (:institution/id result)))
      (is (= "Bank of America" (:institution/name result)))
      (is (nil? (:institution/url result))))))

(deftest test-parse-account
  (testing "Transforms Plaid account to database format with lookup refs"
    (let [account {:account_id "acc-plaid-123"
                  :name "Plaid Checking"
                  :official_name "Plaid Gold Standard Checking"
                  :type "depository"
                  :subtype "checking"
                  :mask "0000"
                  :balance {:iso_currency_code "USD"
                           :current 1000.0}}
          user-id "test-user"
          institution-id "ins_123"
          result (data/parse-account account institution-id user-id)]
      (is (= "acc-plaid-123" (:account/external-id result)))
      (is (= "Plaid Checking" (:account/external-name result)))
      (is (= "depository" (:account/plaid-type result)))
      (is (= "checking" (:account/plaid-subtype result)))
      (is (= "0000" (:account/mask result)))
      (is (= "USD" (:account/currency result)))
      (is (= [:institution/id "ins_123"] (:account/institution result)))
      (is (= [:user/id "test-user"] (:account/user result))))))

(deftest test-parse-account-defaults-currency
  (testing "Defaults to USD when currency not provided"
    (let [account {:account_id "acc-no-currency"
                  :name "Test Account"
                  :type "depository"
                  :subtype "checking"
                  :balance {}}
          result (data/parse-account account "ins_123" "test-user")]
      (is (= "USD" (:account/currency result))))))

(deftest test-parse-transaction
  (testing "Transforms Plaid transaction with type conversions"
    (let [txn {:transaction_id "tx-plaid-001"
              :account_id "acc-plaid-123"
              :amount 100.50
              :date "2024-01-15"
              :name "STARBUCKS"
              :merchant_name "Starbucks"
              :pending false}
          user-id "test-user"
          result (data/parse-transaction txn user-id)]
      (is (= "tx-plaid-001" (:transaction/external-id result)))
      (is (= [:account/external-id "acc-plaid-123"] (:transaction/account result)))
      (is (= [:user/id "test-user"] (:transaction/user result)))
      (is (instance? Date (:transaction/date result)))
      (is (instance? java.math.BigDecimal (:transaction/amount result)))
      (is (= (bigdec "100.50") (:transaction/amount result)))
      (is (= "STARBUCKS" (:transaction/description result)))
      (is (= "Starbucks" (:transaction/payee result))))))

(deftest test-parse-transaction-skips-pending
  (testing "Returns nil for pending transactions"
    (let [txn {:transaction_id "tx-pending"
              :account_id "acc-123"
              :amount 50.0
              :date "2024-01-15"
              :name "Pending Transaction"
              :pending true}
          result (data/parse-transaction txn "test-user")]
      (is (nil? result) "Pending transactions should return nil"))))

(deftest test-parse-transaction-no-merchant-name
  (testing "Uses name as payee when merchant_name is nil"
    (let [txn {:transaction_id "tx-002"
              :account_id "acc-123"
              :amount 25.0
              :date "2024-01-15"
              :name "TRANSFER"
              :merchant_name nil
              :pending false}
          result (data/parse-transaction txn "test-user")]
      (is (= "TRANSFER" (:transaction/payee result))))))

(ns finance-aggregator.plaid.data-test
  "Unit tests for Plaid data transformation functions.
   Tests follow SimpleFIN pattern for data transformation."
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.plaid.data :as data]
   [finance-aggregator.utils :as u])
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

(deftest test-parse-institution-logo
  (testing "A present logo becomes a base64 PNG data URI"
    (let [institution {:institution_id "ins_123" :name "Chase" :logo "base64-logo"}
          result (data/parse-institution institution)]
      (is (= "data:image/png;base64,base64-logo" (:institution/logo result)))))
  (testing "A nil logo is omitted, not stored as nil"
    (let [institution {:institution_id "ins_456" :name "Bank of America" :logo nil}
          result (data/parse-institution institution)]
      (is (not (contains? result :institution/logo))))))

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
      (is (= "depository" (:account/provider-type result)))
      (is (= "checking" (:account/provider-subtype result)))
      (is (= "0000" (:account/mask result)))
      (is (= "USD" (:account/currency result)))
      (is (= :plaid (:account/provider result)))
      (is (= (bigdec 1000.0) (:account/reported-balance result)))
      (is (= [:institution/id "ins_123"] (:account/institution result)))
      (is (= [:user/id "test-user"] (:account/user result))))))

(deftest test-parse-account-extracts-balances
  (testing "Extracts reported (current) and available balances as bigdec"
    (let [account {:account_id "acc-bal"
                   :name "Checking"
                   :type "depository"
                   :subtype "checking"
                   :balance {:iso_currency_code "USD"
                             :current 1234.56
                             :available 1200.00}}
          result (data/parse-account account "ins_123" "test-user")]
      (is (= (bigdec 1234.56) (:account/reported-balance result)))
      (is (= (bigdec 1200.00) (:account/available-balance result))))))

(deftest test-parse-account-omits-absent-balances
  (testing "No balance keys when the institution doesn't report them"
    (let [account {:account_id "acc-nobal"
                   :name "Checking"
                   :type "depository"
                   :balance {:iso_currency_code "USD"}}
          result (data/parse-account account "ins_123" "test-user")]
      (is (not (contains? result :account/reported-balance)))
      (is (not (contains? result :account/available-balance))))))

(deftest test-parse-account-defaults-currency
  (testing "Defaults to USD when currency not provided"
    (let [account {:account_id "acc-no-currency"
                  :name "Test Account"
                  :type "depository"
                  :subtype "checking"
                  :balance {}}
          result (data/parse-account account "ins_123" "test-user")]
      (is (= "USD" (:account/currency result)))
      (is (= :plaid (:account/provider result))))))

(deftest test-parse-transaction
  (testing "Transaction date comes from authorized_date, posted-date from Plaid's date"
    (let [txn {:transaction_id "tx-plaid-001"
              :account_id "acc-plaid-123"
              :amount 100.50
              :authorized_date "2024-01-15" ; when the purchase happened
              :date "2024-01-17"            ; when it posted (a day or two later)
              :name "STARBUCKS"
              :merchant_name "Starbucks"
              :pending false}
          user-id "test-user"
          result (data/parse-transaction txn user-id)]
      (is (= "tx-plaid-001" (:transaction/external-id result)))
      (is (= [:account/external-id "acc-plaid-123"] (:transaction/account result)))
      (is (= [:user/id "test-user"] (:transaction/user result)))
      (is (= (u/string->date "2024-01-15") (:transaction/date result))
          "transaction date is the authorized_date")
      (is (= (u/string->date "2024-01-17") (:transaction/posted-date result))
          "posted-date is Plaid's date")
      (is (not= (:transaction/date result) (:transaction/posted-date result))
          "the two dates differ when a purchase posts on a later day")
      (is (instance? java.math.BigDecimal (:transaction/amount result)))
      ;; Plaid is positive=money-out; canonical convention negates so outflows
      ;; are negative.
      (is (= (bigdec "-100.50") (:transaction/amount result)))
      (is (= "STARBUCKS" (:transaction/description result)))
      (is (= "Starbucks" (:transaction/payee result)))))
  (testing "Falls back to posted date when Plaid omits authorized_date"
    (let [txn {:transaction_id "tx-plaid-002"
              :account_id "acc-plaid-123"
              :amount 10.0
              :date "2024-01-20"
              :authorized_date nil
              :name "NO AUTH DATE"
              :pending false}
          result (data/parse-transaction txn "test-user")]
      (is (instance? Date (:transaction/date result)))
      (is (= (u/string->date "2024-01-20") (:transaction/date result)))
      (is (= (:transaction/date result) (:transaction/posted-date result))
          "with no authorized_date, transaction date falls back to the posted date"))))

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

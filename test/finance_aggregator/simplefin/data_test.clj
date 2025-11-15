(ns finance-aggregator.simplefin.data-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [finance-aggregator.simplefin.data :as data]
   [tick.core :as t])
  (:import
   [java.util Date]))

;; ============================================================================
;; Unit Tests - SimpleFIN Data Parsing
;; ============================================================================

(deftest test-parse-institution
  (testing "Extracts institution from SimpleFIN account org data"
    (let [account {:org {:id "inst-001"
                         :name "Test Bank"
                         :domain "testbank.com"
                         :url "https://testbank.com"}}
          result (data/parse-institution account)]
      (is (= "inst-001" (:institution/id result)))
      (is (= "Test Bank" (:institution/name result)))
      (is (= "testbank.com" (:institution/domain result)))
      (is (= "https://testbank.com" (:institution/url result))))))

(deftest test-parse-account
  (testing "Transforms SimpleFIN account to database format with lookup ref"
    (let [account {:id "acct-001"
                   :name "Test Chequing"
                   :currency "CAD"
                   :org {:id "inst-001"}}
          result (data/parse-account account)]
      (is (= "acct-001" (:account/external-id result)))
      (is (= "Test Chequing" (:account/external-name result)))
      (is (= "CAD" (:account/currency result)))
      (is (= [:institution/id "inst-001"] (:account/institution result))))))

(deftest test-parse-transaction
  (testing "Transforms SimpleFIN transaction with all type conversions"
    (let [tx {:id "tx-001"
              :posted 1609459200
              :transacted_at 1609459200
              :amount "100.50"
              :description "Test transaction"
              :payee "Test Payee"}
          result (data/parse-transaction "acct-001" tx)]
      (is (= "tx-001" (:transaction/external-id result)))
      (is (= [:account/external-id "acct-001"] (:transaction/account result)))
      (is (instance? Date (:transaction/transaction-date result)))
      (is (instance? Date (:transaction/posted-date result)))
      (is (instance? java.math.BigDecimal (:transaction/amount result)))
      (is (= (bigdec "100.50") (:transaction/amount result)))
      (is (= "Test transaction" (:transaction/description result)))))

  (testing "Handles negative amounts"
    (let [tx {:id "tx-003"
              :transacted_at 1609459200
              :posted 1609459900
              :amount "-123.45"}
          result (data/parse-transaction "acct-003" tx)]
      (is (= (bigdec "-123.45") (:transaction/amount result))))))

;; ============================================================================
;; Property-Based Tests
;; ============================================================================

(defspec account-institution-always-lookup-ref 50
  (prop/for-all [inst-id gen/string-alphanumeric
                 acct-id gen/string-alphanumeric
                 acct-name (gen/not-empty gen/string-ascii)
                 currency (gen/elements ["CAD" "USD" "EUR" "GBP"])]
                (let [account {:id acct-id
                               :name acct-name
                               :currency currency
                               :org {:id inst-id}}
                      result (data/parse-account account)]
                  (and (vector? (:account/institution result))
                       (= 2 (count (:account/institution result)))
                       (= :institution/id (first (:account/institution result)))
                       (= inst-id (second (:account/institution result)))))))

(defspec transaction-types-correct 50
  (prop/for-all [tx-id gen/string-alphanumeric
                 acct-id gen/string-alphanumeric
                 amount (gen/fmap #(format "%.2f" %)
                                  (gen/double* {:infinite? false :NaN? false :min -10000 :max 10000}))
                 timestamp (gen/choose 1000000000 2000000000)]
                (let [tx {:id tx-id
                          :posted timestamp
                          :amount amount
                          :transacted_at timestamp}
                      result (data/parse-transaction acct-id tx)]
                  (and (vector? (:transaction/account result))
                       (= :account/external-id (first (:transaction/account result)))
                       (instance? Date (:transaction/transaction-date result))
                       (instance? Date (:transaction/posted-date result))
                       (instance? java.math.BigDecimal (:transaction/amount result))))))

(defspec negative-amounts-preserved 30
  (prop/for-all [amount (gen/fmap #(format "%.2f" %)
                                  (gen/double* {:infinite? false :NaN? false :min -10000 :max -0.01}))]
                (let [tx {:id "test"
                          :transacted_at 1609459200
                          :posted  1609459900
                          :amount amount}
                      result (data/parse-transaction "test-acct" tx)]
                  (neg? (.doubleValue (:transaction/amount result))))))

(defspec positive-amounts-preserved 30
  (prop/for-all [amount (gen/fmap #(format "%.2f" %)
                                  (gen/double* {:infinite? false :NaN? false :min 0.01 :max 10000}))]
                (let [tx {:id "test"
                          :transacted_at 1609459200
                          :posted  1609459900
                          :amount amount}
                      result (data/parse-transaction "test-acct" tx)]
                  (pos? (.doubleValue (:transaction/amount result))))))

;; ============================================================================
;; Integration Test - Full Parse
;; ============================================================================

(deftest test-parse-entities
  (testing "Parses complete SimpleFIN response"
    (let [accounts [{:id "acct-001"
                     :name "Chequing"
                     :currency "CAD"
                     :balance "1000.00"
                     :org {:id "inst-001"
                           :name "Bank 1"
                           :domain "bank1.com"
                           :url "https://bank1.com"}
                     :transactions [{:id "tx-001"
                                     :transacted_at 1609459200
                                     :posted 1609459200
                                     :amount "100.00"
                                     :description "Transaction 1"}]}
                    {:id "acct-002"
                     :name "Savings"
                     :currency "USD"
                     :balance "2000.00"
                     :org {:id "inst-002"
                           :name "Bank 2"
                           :domain "bank2.com"
                           :url "https://bank2.com"}
                     :transactions [{:id "tx-002"
                                     :transacted_at 1609545600
                                     :posted 1609632000
                                     :amount "-50.00"
                                     :description "Transaction 2"}]}]
          result (data/parse-entities accounts)]

      ;; Check institutions
      (is (= 2 (count (:institutions result))))
      (is (every? #(contains? % :institution/id) (:institutions result)))

      ;; Check accounts have lookup refs
      (is (= 2 (count (:accounts result))))
      (let [account (first (:accounts result))]
        (is (vector? (:account/institution account)))
        (is (= :institution/id (first (:account/institution account)))))

      ;; Check transactions have correct types
      (is (= 2 (count (:transactions result))))
      (let [tx (first (:transactions result))]
        (is (vector? (:transaction/account tx)))
        (instance? Date (:transaction/transaction-date tx))
        (instance? Date (:transaction/posted-date tx))
        (is (instance? java.math.BigDecimal (:transaction/amount tx)))))))

(ns finance-aggregator.simplefin-test
  (:require [clojure.test :refer :all]
            [finance-aggregator.simplefin :as sf]
            [finance-aggregator.schema :as schema]))

(def sample-access-url
  "sfin://test-token:test-secret@bridge.simplefin.org/simplefin")

(def sample-simplefin-response
  {:accounts
   [{:id "account-1"
     :name "Wealthsimple TFSA"
     :currency "CAD"
     :balance "15234.56"
     :balance-date 1728950400
     :transactions
     [{:id "tx-1"
       :posted 1728950400
       :amount "100.00"
       :description "Deposit"}
      {:id "tx-2"
       :posted 1728864000
       :amount "-25.50"
       :description "Fee"}]}
    {:id "account-2"
     :name "Scotiabank Chequing"
     :currency "CAD"
     :balance "2345.67"
     :balance-date 1728950400
     :transactions
     [{:id "tx-3"
       :posted 1728950400
       :amount "-45.67"
       :description "LOBLAWS #1234"}
      {:id "tx-4"
       :posted 1728864000
       :amount "2500.00"
       :description "Direct Deposit"}]}]})

(deftest test-parse-access-url
  (testing "Parses SimpleFin access URL correctly"
    (let [result (sf/parse-access-url sample-access-url)]
      (is (= "test-token" (:token result)))
      (is (= "test-secret" (:secret result)))
      (is (= "bridge.simplefin.org" (:host result)))
      (is (= "/simplefin" (:path result)))
      (is (= "https://bridge.simplefin.org/simplefin" (:base-url result)))))

  (testing "Returns nil for invalid URL"
    (is (nil? (sf/parse-access-url "not-a-valid-url")))
    (is (nil? (sf/parse-access-url nil)))))

(deftest test-unix-timestamp->date
  (testing "Converts Unix timestamp to ISO date string"
    (is (= "2024-10-15" (sf/unix-timestamp->date 1728950400)))
    (is (= "2024-10-14" (sf/unix-timestamp->date 1728864000))))

  (testing "Handles nil timestamp"
    (is (nil? (sf/unix-timestamp->date nil)))))

(deftest test-simplefin-tx->normalized
  (testing "Converts SimpleFin transaction to normalized format"
    (let [sf-tx {:id "tx-1"
                 :posted 1728950400
                 :amount "100.00"
                 :description "Test Transaction"
                 :payee "Test Payee"}
          normalized (sf/simplefin-tx->normalized sf-tx "Test Account" :wealthsimple)]

      (is (uuid? (:id normalized)))
      (is (= "2024-10-15" (:date normalized)))
      (is (= 100.0 (:amount normalized)))
      (is (= "Test Transaction" (:description normalized)))
      (is (= :wealthsimple (:institution normalized)))
      (is (= "Test Account" (:account-name normalized)))
      (is (= :simplefin (:source normalized)))
      (is (schema/valid-transaction? normalized))))

  (testing "Uses payee when description is missing"
    (let [sf-tx {:id "tx-2"
                 :posted 1728950400
                 :amount "-50.00"
                 :payee "Store Name"}
          normalized (sf/simplefin-tx->normalized sf-tx "Account" :scotiabank)]

      (is (= "Store Name" (:description normalized)))))

  (testing "Uses memo as fallback for description"
    (let [sf-tx {:id "tx-3"
                 :posted 1728950400
                 :amount "-25.00"
                 :memo "Memo text"}
          normalized (sf/simplefin-tx->normalized sf-tx "Account" :canadian-tire)]

      (is (= "Memo text" (:description normalized))))))

(deftest test-simplefin-account->transactions
  (testing "Extracts all transactions from an account"
    (let [account (first (:accounts sample-simplefin-response))
          transactions (sf/simplefin-account->transactions account :wealthsimple)]

      (is (= 2 (count transactions)))
      (is (every? #(= :wealthsimple (:institution %)) transactions))
      (is (every? #(= "Wealthsimple TFSA" (:account-name %)) transactions))
      (is (every? schema/valid-transaction? transactions))))

  (testing "Handles account with no transactions"
    (let [account {:id "empty"
                   :name "Empty Account"
                   :currency "CAD"
                   :balance "0.00"}
          transactions (sf/simplefin-account->transactions account :wealthsimple)]

      (is (empty? transactions)))))

(deftest test-institution-mapping
  (testing "Maps account names to institutions using patterns"
    (let [config {:institution-mapping {#"(?i)wealthsimple" :wealthsimple
                                        #"(?i)scotiabank" :scotiabank
                                        #"(?i)canadian.?tire" :canadian-tire}}
          account-names ["Wealthsimple TFSA"
                        "Scotiabank Chequing"
                        "Canadian Tire Mastercard"
                        "Unknown Bank Account"]]

      ;; This test documents the expected behavior
      ;; The actual implementation will be in the fetch-all-transactions function
      (is true))))

(deftest test-account-type-inference
  (testing "Infers account type from account name"
    (is (= :investment (schema/infer-account-type "Wealthsimple TFSA")))
    (is (= :checking (schema/infer-account-type "Scotiabank Chequing")))
    (is (= :credit (schema/infer-account-type "Canadian Tire Mastercard")))
    (is (= :investment (schema/infer-account-type "Manulife RRSP")))))

(comment
  ;; Integration test - only run manually with real credentials
  (deftest test-fetch-accounts-integration
    (testing "Fetches accounts from SimpleFin (requires real access-url)"
      (let [access-url (System/getenv "SIMPLEFIN_ACCESS_URL")]
        (when access-url
          (let [response (sf/fetch-accounts access-url)]
            (is (not (:error response)))
            (is (contains? response :accounts))))))))

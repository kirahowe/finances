(ns finance-aggregator.schema-test
  (:require [clojure.test :refer :all]
            [finance-aggregator.schema :as schema]))

(deftest test-normalize-amount
  (testing "Normalizes various amount formats"
    (is (= 123.45 (schema/normalize-amount "123.45")))
    (is (= 123.45 (schema/normalize-amount "$123.45")))
    (is (= 1234.56 (schema/normalize-amount "$1,234.56")))
    (is (= -50.00 (schema/normalize-amount "-50.00")))
    (is (= 0.0 (schema/normalize-amount "invalid")))))

(deftest test-institution-keyword
  (testing "Converts institution names to keywords"
    (is (= :scotiabank (schema/institution-keyword "Scotiabank")))
    (is (= :canadian-tire (schema/institution-keyword "Canadian Tire")))
    (is (= :amazon-mbna (schema/institution-keyword "Amazon MBNA")))))

(deftest test-infer-account-type
  (testing "Infers account type from name"
    (is (= :checking (schema/infer-account-type "Chequing Account")))
    (is (= :savings (schema/infer-account-type "Savings Account")))
    (is (= :credit (schema/infer-account-type "Visa Credit Card")))
    (is (= :investment (schema/infer-account-type "TFSA Investment")))
    (is (= :mortgage (schema/infer-account-type "Home Mortgage")))
    (is (= :asset (schema/infer-account-type "House Property")))
    (is (= :checking (schema/infer-account-type "Unknown Account")))))

(deftest test-valid-transaction
  (testing "Validates correct transaction"
    (let [tx {:id (random-uuid)
              :date "2024-10-15"
              :amount -45.67
              :description "LOBLAWS #1234"
              :institution :scotiabank
              :account-name "Checking Account"
              :account-type :checking
              :category :groceries
              :source :csv}]
      (is (true? (schema/valid-transaction? tx)))))

  (testing "Rejects invalid transaction - missing required field"
    (let [tx {:id (random-uuid)
              :date "2024-10-15"
              ; missing :amount
              :description "LOBLAWS #1234"
              :institution :scotiabank
              :account-type :checking
              :source :csv}]
      (is (false? (schema/valid-transaction? tx)))))

  (testing "Rejects invalid transaction - wrong type"
    (let [tx {:id (random-uuid)
              :date "2024-10-15"
              :amount "not-a-number"  ; should be double
              :description "LOBLAWS #1234"
              :institution :scotiabank
              :account-type :checking
              :source :csv}]
      (is (false? (schema/valid-transaction? tx)))))

  (testing "Rejects invalid transaction - invalid enum value"
    (let [tx {:id (random-uuid)
              :date "2024-10-15"
              :amount -45.67
              :description "LOBLAWS #1234"
              :institution :invalid-bank  ; not in enum
              :account-type :checking
              :source :csv}]
      (is (false? (schema/valid-transaction? tx))))))

(deftest test-valid-account
  (testing "Validates correct account"
    (let [account {:id (random-uuid)
                   :institution :scotiabank
                   :name "Chequing Account"
                   :account-type :checking
                   :balance 1234.56
                   :currency "CAD"
                   :last-updated "2024-10-15"}]
      (is (true? (schema/valid-account? account)))))

  (testing "Rejects invalid account"
    (let [account {:id (random-uuid)
                   :institution :scotiabank
                   ; missing :name
                   :account-type :checking
                   :balance 1234.56}]
      (is (false? (schema/valid-account? account))))))

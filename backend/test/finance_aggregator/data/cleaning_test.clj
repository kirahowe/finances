(ns finance-aggregator.data.cleaning-test
  (:require [clojure.test :refer :all]
            [finance-aggregator.data.cleaning :as cleaning]
            [tablecloth.api :as tc]))

(def test-date-1 (java.util.Date. 1700000000000))
(def test-date-2 (java.util.Date. 1700086400000))

(def test-transactions
  [{:db/id 1
    :transaction/external-id "tx-001"
    :transaction/account {:account/external-name "Account A"}
    :transaction/posted-date test-date-1
    :transaction/amount (bigdec "-100.00")
    :transaction/payee "Payee One"
    :transaction/description "Test description one"
    :transaction/memo ""}

   {:db/id 2
    :transaction/external-id "tx-002"
    :transaction/account {:account/external-name "Account B"}
    :transaction/posted-date test-date-1
    :transaction/amount (bigdec "-100.00")
    :transaction/payee "Payee One"
    :transaction/description "Test description one"
    :transaction/memo ""}

   {:db/id 3
    :transaction/external-id "tx-003"
    :transaction/account {:account/external-name "Account C"}
    :transaction/posted-date test-date-1
    :transaction/amount (bigdec "-100.00")
    :transaction/payee "Payee One"
    :transaction/description "Test description one"
    :transaction/memo ""}

   ;; Unique transaction - not a duplicate
   {:db/id 4
    :transaction/external-id "tx-004"
    :transaction/account {:account/external-name "Account A"}
    :transaction/posted-date test-date-2
    :transaction/amount (bigdec "-50.00")
    :transaction/payee "Payee Two"
    :transaction/description "Test description two"
    :transaction/memo "Test memo"}

   ;; Another pair of duplicates
   {:db/id 5
    :transaction/external-id "tx-005"
    :transaction/account {:account/external-name "Account D"}
    :transaction/posted-date test-date-2
    :transaction/amount (bigdec "200.00")
    :transaction/payee "Payee Three"
    :transaction/description "Test description three"
    :transaction/memo ""}

   {:db/id 6
    :transaction/external-id "tx-006"
    :transaction/account {:account/external-name "Account E"}
    :transaction/posted-date test-date-2
    :transaction/amount (bigdec "200.00")
    :transaction/payee "Payee Three"
    :transaction/description "Test description three"
    :transaction/memo ""}])

(deftest test-find-likely-dupes
  (testing "Returns only transactions that appear in duplicate groups"
    (let [dupes (cleaning/find-likely-dupes test-transactions)
          dupe-ids (set (:transaction/external-id dupes))]

      ;; Should return 5 transactions (3 from first group + 2 from second group)
      (is (= 5 (tc/row-count dupes)))

      ;; Should include all the duplicate transactions
      (is (contains? dupe-ids "tx-001"))
      (is (contains? dupe-ids "tx-002"))
      (is (contains? dupe-ids "tx-003"))
      (is (contains? dupe-ids "tx-005"))
      (is (contains? dupe-ids "tx-006"))

      ;; Should NOT include the unique transaction
      (is (not (contains? dupe-ids "tx-004")))))

  (testing "Returns empty dataset when no duplicates exist"
    (let [unique-txns [(first test-transactions)
                       (nth test-transactions 3)]
          result (cleaning/find-likely-dupes unique-txns)]
      (is (= 0 (tc/row-count result)))))

  (testing "Returns nil for empty input"
    (is (nil? (cleaning/find-likely-dupes [])))))

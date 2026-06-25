(ns finance-aggregator.db.snapshots-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datalevin.core :as d]
   [finance-aggregator.db.snapshots :as snapshots]
   [finance-aggregator.test-utils.setup :as setup])
  (:import
   [java.util Date]))

(use-fixtures :each setup/with-empty-db)

(defn- put-account! [ext-id]
  (d/transact! setup/*test-conn* [{:account/external-id ext-id :account/provider :plaid}]))

(defn- snapshots-for [ext-id]
  (d/q '[:find [(pull ?s [*]) ...]
         :in $ ?ext
         :where
         [?a :account/external-id ?ext]
         [?s :snapshot/account ?a]]
       (d/db setup/*test-conn*) ext-id))

(deftest records-balance-and-stamps-account
  (testing "Writes a reported snapshot and stamps :account/balance-as-of"
    (put-account! "acc-1")
    (let [now (Date.)]
      (snapshots/record-reported-balances!
       setup/*test-conn*
       [{:account/external-id "acc-1" :account/reported-balance (bigdec "100.00")}]
       now)
      (let [acct (d/pull (d/db setup/*test-conn*) '[:account/balance-as-of]
                         [:account/external-id "acc-1"])
            snaps (snapshots-for "acc-1")]
        (is (= now (:account/balance-as-of acct)))
        (is (= 1 (count snaps)))
        (is (= (bigdec "100.00") (:snapshot/balance (first snaps))))
        (is (= :reported (:snapshot/source (first snaps))))))))

(deftest skips-accounts-without-balance
  (put-account! "acc-1")
  (snapshots/record-reported-balances!
   setup/*test-conn* [{:account/external-id "acc-1"}] (Date.))
  (is (empty? (snapshots-for "acc-1"))))

(deftest idempotent-per-day
  (testing "Re-recording the same UTC day overwrites rather than duplicating"
    (put-account! "acc-1")
    (let [day (Date. 1700000000000)]
      (snapshots/record-reported-balances!
       setup/*test-conn* [{:account/external-id "acc-1" :account/reported-balance (bigdec "10")}] day)
      (snapshots/record-reported-balances!
       setup/*test-conn* [{:account/external-id "acc-1" :account/reported-balance (bigdec "20")}] day)
      (let [snaps (snapshots-for "acc-1")]
        (is (= 1 (count snaps)) "Same day = one snapshot row")
        (is (= (bigdec "20") (:snapshot/balance (first snaps))) "Latest value wins")))))

(deftest distinct-days-distinct-rows
  (put-account! "acc-1")
  (snapshots/record-reported-balances!
   setup/*test-conn* [{:account/external-id "acc-1" :account/reported-balance (bigdec "10")}]
   (Date. 1700000000000))
  (snapshots/record-reported-balances!
   setup/*test-conn* [{:account/external-id "acc-1" :account/reported-balance (bigdec "20")}]
   (Date. 1700200000000))
  (is (= 2 (count (snapshots-for "acc-1")))))

(deftest empty-accounts-is-noop
  (is (some? (snapshots/record-reported-balances! setup/*test-conn* [] (Date.)))))

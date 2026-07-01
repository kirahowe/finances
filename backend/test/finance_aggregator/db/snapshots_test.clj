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

;; --- reported-delta (monthly-close reading) --------------------------------

(defn- date [y m d]
  (-> (java.time.LocalDate/of y m d)
      (.atStartOfDay java.time.ZoneOffset/UTC)
      (.toInstant)
      (Date/from)))

(defn- account-eid [ext-id]
  (d/q '[:find ?a . :in $ ?ext :where [?a :account/external-id ?ext]]
       (d/db setup/*test-conn*) ext-id))

(defn- record! [ext-id balance ^Date as-of]
  (snapshots/record-reported-balances!
   setup/*test-conn* [{:account/external-id ext-id :account/reported-balance (bigdec balance)}] as-of))

(deftest reported-delta-is-end-minus-start
  (testing "balance at month end minus prior month-end balance"
    (put-account! "acc-1")
    (record! "acc-1" "100.00" (date 2026 2 28))   ; prior month-end
    (record! "acc-1" "170.00" (date 2026 3 31))   ; this month-end
    (is (= (bigdec "70.00") (snapshots/reported-delta setup/*test-conn* (account-eid "acc-1") "2026-03")))))

(deftest reported-delta-nil-without-start-boundary
  (testing "no snapshot before the month → can't auto-reconcile"
    (put-account! "acc-1")
    (record! "acc-1" "170.00" (date 2026 3 31))
    (is (nil? (snapshots/reported-delta setup/*test-conn* (account-eid "acc-1") "2026-03")))))

(deftest reported-delta-nil-when-only-stale-pre-month-snapshot
  (testing "a lone pre-month snapshot is not a real month-end reading"
    (put-account! "acc-1")
    (record! "acc-1" "100.00" (date 2026 2 28))
    (is (nil? (snapshots/reported-delta setup/*test-conn* (account-eid "acc-1") "2026-03")))))

(deftest reported-delta-nil-when-start-snapshot-predates-prior-month
  (testing "a start snapshot older than the immediately prior month → no auto-reconcile (sync gap, not a real month boundary)"
    (put-account! "acc-1")
    (record! "acc-1" "100.00" (date 2026 1 31))   ; January — two months before March
    (record! "acc-1" "170.00" (date 2026 3 31))   ; this month-end
    ;; January is not within February (the prior month of March), so the span
    ;; would cover multiple months → must be nil rather than a spurious delta.
    (is (nil? (snapshots/reported-delta setup/*test-conn* (account-eid "acc-1") "2026-03")))))

(deftest reported-delta-ignores-calculated-snapshots
  (testing "only :reported/:manual snapshots anchor a boundary; :calculated is ours, not the bank's"
    (put-account! "acc-1")
    (record! "acc-1" "100.00" (date 2026 2 28))
    (d/transact! setup/*test-conn*
                 [{:snapshot/id "acc-1:calc" :snapshot/account [:account/external-id "acc-1"]
                   :snapshot/date (date 2026 3 31) :snapshot/balance (bigdec "170.00")
                   :snapshot/source :calculated}])
    (is (nil? (snapshots/reported-delta setup/*test-conn* (account-eid "acc-1") "2026-03")))))

(deftest reported-deltas-omits-accounts-without-a-pair
  (put-account! "acc-1")
  (put-account! "acc-2")
  (record! "acc-1" "100.00" (date 2026 2 28))
  (record! "acc-1" "170.00" (date 2026 3 31))
  (record! "acc-2" "50.00"  (date 2026 3 15))   ; only one snapshot → no pair
  (let [a1 (account-eid "acc-1") a2 (account-eid "acc-2")]
    (is (= {a1 (bigdec "70.00")}
           (snapshots/reported-deltas setup/*test-conn* [a1 a2] "2026-03")))))

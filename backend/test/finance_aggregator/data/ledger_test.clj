(ns finance-aggregator.data.ledger-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.data.ledger :as ledger]))

(defn- tx [acct-eid acct-name amount]
  {:transaction/account {:db/id acct-eid :account/external-name acct-name}
   :transaction/amount  (bigdec amount)})

(deftest reconcile-period-verdict
  ;; A strict balance-delta period: does end − start match Σ of the span's transactions?
  (let [span [{:transaction/amount 40M} {:transaction/amount 100M}]]   ; Σ = 140
    (testing "reconciled when end − start = Σ (within tolerance)"
      (is (= :reconciled (:status (ledger/reconcile-period 500M 640M span)))))
    (testing "drift carries the reported/computed/signed-difference"
      (let [r (ledger/reconcile-period 500M 700M span)]
        (is (= :drift (:status r)))
        (is (= 200M (:reported r)))
        (is (= 140M (:computed r)))
        (is (= 60M  (:difference r)) "reported − computed")))
    (testing "no-snapshot until BOTH balances are entered"
      (is (= :no-snapshot (:status (ledger/reconcile-period nil 640M span))))
      (is (= :no-snapshot (:status (ledger/reconcile-period 500M nil span)))))
    (testing "an empty span sums to zero"
      (is (= :reconciled (:status (ledger/reconcile-period 500M 500M []))))
      (is (= :drift (:status (ledger/reconcile-period 500M 510M [])))))))

(deftest reconcile-statement-period-verdict
  (testing "accepts statement balances whose polarity is opposite tracked activity"
    (let [span [{:transaction/amount 44.02M}
                {:transaction/amount -31.92M}
                {:transaction/amount -4.56M}
                {:transaction/amount 36.48M}
                {:transaction/amount 90.15M}]
          r (ledger/reconcile-statement-period 44.02M -90.15M span)]
      (is (= :reconciled (:status r)))
      (is (= 134.17M (:computed r)))
      (is (= 134.17M (:reported r)))
      (is (= 0.00M (:difference r)))))
  (testing "keeps the synced-balance direction when that is the matching polarity"
    (let [r (ledger/reconcile-statement-period 0M -85M [{:transaction/amount -85M}])]
      (is (= :reconciled (:status r)))
      (is (= -85M (:reported r))))))

(deftest account-computed-deltas-sums-signed-amounts-per-account
  (testing "groups by account and sums signed amounts (inflows +, outflows -)"
    (let [deltas (ledger/account-computed-deltas
                  [(tx 1 "Chequing" "100.00")
                   (tx 1 "Chequing" "-30.00")
                   (tx 2 "Credit"   "-50.00")])]
      (is (= (bigdec "70.00") (get-in deltas [1 :computed-delta])))
      (is (= (bigdec "-50.00") (get-in deltas [2 :computed-delta])))
      (is (= "Chequing" (get-in deltas [1 :name])))
      (is (= 2 (get-in deltas [2 :account-id]))))))

(deftest account-computed-deltas-sums-split-part-rows
  (testing "a split family's part rows carry the account and sum exactly to the excluded
            parent's amount, so the account total is unchanged (splits re-attribute, not
            re-bank; the parent never reaches here — the list fns exclude it)"
    (let [part (fn [amount] (assoc (tx 1 "Chequing" amount) :transaction/split-parent {:db/id 9}))
          deltas (ledger/account-computed-deltas [(part "-60.00") (part "-30.00")])]
      (is (= (bigdec "-90.00") (get-in deltas [1 :computed-delta]))))))

(deftest account-computed-deltas-skips-accountless-rows
  (is (= {} (ledger/account-computed-deltas [{:transaction/amount (bigdec "5.00")}]))))

(deftest reconcile-classifies-each-account
  (let [computed (ledger/account-computed-deltas
                  [(tx 1 "Chequing" "70.00")
                   (tx 2 "Credit"   "-50.00")
                   (tx 3 "Savings"  "20.00")])
        ;; Chequing matches, Credit drifts by $5, Savings has no boundary snapshot.
        reported {1 (bigdec "70.00")
                  2 (bigdec "-45.00")}
        rows     (ledger/reconcile computed reported)
        by-name  (into {} (map (juxt :name identity) rows))]
    (testing "name-sorted"
      (is (= ["Chequing" "Credit" "Savings"] (map :name rows))))
    (testing "matching account reconciles"
      (is (= :reconciled (get-in by-name ["Chequing" :status])))
      (is (= (bigdec "0.00") (get-in by-name ["Chequing" :difference]))))
    (testing "mismatch is drift, difference is reported-computed"
      (is (= :drift (get-in by-name ["Credit" :status])))
      (is (= (bigdec "5.00") (get-in by-name ["Credit" :difference]))))
    (testing "missing snapshot is :no-snapshot with nil difference"
      (is (= :no-snapshot (get-in by-name ["Savings" :status])))
      (is (nil? (get-in by-name ["Savings" :difference]))))))

(deftest reconcile-tolerates-sub-cent-rounding
  (let [computed (ledger/account-computed-deltas [(tx 1 "Chequing" "100.00")])
        rows     (ledger/reconcile computed {1 (bigdec "100.004")})]
    (is (= :reconciled (:status (first rows))))))

(deftest all-reconciled?-gates-on-every-row
  (is (true? (ledger/all-reconciled? [])))
  (is (true? (ledger/all-reconciled? [{:status :reconciled} {:status :reconciled}])))
  (is (false? (ledger/all-reconciled? [{:status :reconciled} {:status :drift}])))
  (is (false? (ledger/all-reconciled? [{:status :no-snapshot}]))))

;; --- Coverage-strict closing -------------------------------------------------

(defn- txn [posted-date] {:transaction/posted-date posted-date})

(deftest covered?-half-open-boundaries
  (let [span {:start #inst "2025-05-01" :end #inst "2025-05-31"}]
    (testing "d == start is NOT covered (start is exclusive)"
      (is (false? (ledger/covered? #inst "2025-05-01" [span]))))
    (testing "d == end IS covered (end is inclusive)"
      (is (true? (ledger/covered? #inst "2025-05-31" [span]))))
    (testing "d strictly inside is covered"
      (is (true? (ledger/covered? #inst "2025-05-15" [span]))))
    (testing "d outside any span is not covered"
      (is (false? (ledger/covered? #inst "2025-06-01" [span]))))))

(deftest month-coverage-single-span-covers-all
  (let [txs [(txn #inst "2025-05-05") (txn #inst "2025-05-20")]
        spans [{:start #inst "2025-04-30" :end #inst "2025-05-31"}]]
    (is (= {:status :reconciled :uncovered 0 :first-uncovered nil}
           (ledger/month-coverage txs spans true)))))

(deftest month-coverage-partial-with-a-gap
  (let [txs [(txn #inst "2025-05-05") (txn #inst "2025-05-20") (txn #inst "2025-05-25")]
        ;; Covers only the first txn; the other two fall outside.
        spans [{:start #inst "2025-04-30" :end #inst "2025-05-10"}]
        cov (ledger/month-coverage txs spans true)]
    (is (= :partial (:status cov)))
    (is (= 2 (:uncovered cov)))
    (is (= #inst "2025-05-20" (:first-uncovered cov)) "earliest of the uncovered dates")))

(deftest month-coverage-two-adjacent-statements-cover-the-month
  (let [txs [(txn #inst "2025-04-20") (txn #inst "2025-05-01") (txn #inst "2025-05-16") (txn #inst "2025-05-31")]
        spans [{:start #inst "2025-04-16" :end #inst "2025-05-16"}
               {:start #inst "2025-05-16" :end #inst "2025-06-16"}]]
    (is (= {:status :reconciled :uncovered 0 :first-uncovered nil}
           (ledger/month-coverage txs spans true)))))

(deftest month-coverage-no-snapshot-when-nothing-on-file
  (let [txs [(txn #inst "2025-05-05")]]
    (is (= :no-snapshot (:status (ledger/month-coverage txs [] false))))))

(deftest month-coverage-partial-when-any-periods-but-no-reconciled-spans
  (testing "drift with txns present: periods exist (any-periods? true) but none reconciled"
    (let [txs [(txn #inst "2025-05-05")]]
      (is (= :partial (:status (ledger/month-coverage txs [] true))))
      (is (= 1 (:uncovered (ledger/month-coverage txs [] true))))))
  (testing "drift with zero txns: still partial, since a period on file didn't reconcile"
    (is (= :partial (:status (ledger/month-coverage [] [] true))))))

(deftest month-coverage-reconciled-when-zero-txns-and-a-reconciled-span
  (is (= {:status :reconciled :uncovered 0 :first-uncovered nil}
         (ledger/month-coverage [] [{:start #inst "2025-04-30" :end #inst "2025-05-31"}] true))))

(ns finance-aggregator.data.ledger-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.data.ledger :as ledger]))

(defn- tx [acct-eid acct-name amount & {:keys [splits]}]
  (cond-> {:transaction/account {:db/id acct-eid :account/external-name acct-name}
           :transaction/amount  (bigdec amount)}
    splits (assoc :transaction/splits splits)))

(deftest reconcile-period-verdict
  ;; A statement/period: does end − start match Σ of the span's transactions?
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

(deftest account-computed-deltas-ignores-splits
  (testing "a split tx contributes only its parent amount (splits re-attribute, not re-bank)"
    (let [deltas (ledger/account-computed-deltas
                  [(tx 1 "Chequing" "-90.00"
                       :splits [{:split/amount (bigdec "-60.00")}
                                {:split/amount (bigdec "-30.00")}])])]
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

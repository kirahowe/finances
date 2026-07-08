(ns finance-aggregator.data.ledger
  "Pure ledger math for the monthly close. Given a month's transactions and the
   bank-reported balances at the month boundaries, decide whether each account's
   tracked transactions fully explain the bank's balance change for that month —
   the *period-delta* confidence check:

     reported-delta = reported-balance(month-end) - reported-balance(month-start)
     computed-delta = Σ signed :transaction/amount over the month
     reconciled?    = |reported-delta - computed-delta| ≤ tolerance

   When the two agree, the month's transactions fully account for the bank's
   balance change — nothing missing, nothing extra. No opening-balance anchor is
   needed: only the change over the period is compared.

   No I/O here — the caller supplies pulled transactions and the reported deltas
   (read from the snapshot history); this namespace only sums and compares."
  (:import
   [java.util Date]))

(def default-tolerance
  "Half-a-cent of slack on the computed-vs-reported comparison, to absorb rounding
   noise without masking a real discrepancy."
  (bigdec "0.005"))

(defn- amount [tx] (or (:transaction/amount tx) 0M))

(defn- computed-total [txs]
  (reduce (fn [acc tx] (+ acc (amount tx))) 0M txs))

(defn- verdict [reported computed tolerance]
  (let [difference (when (some? reported) (- reported computed))]
    {:reported   reported
     :computed   computed
     :difference difference
     :status     (cond
                   (nil? reported)                :no-snapshot
                   (<= (abs difference) tolerance) :reconciled
                   :else                           :drift)}))

(defn account-computed-deltas
  "Σ signed :transaction/amount grouped by account, over `txs` (one month's pulled
   transactions). Splits never change an account's total — they only re-attribute
   the parent's amount across categories — so the parent amount is summed and split
   parts are ignored. Returns
   {account-eid {:account-id eid :name str :computed-delta bigdec}}."
  [txs]
  (reduce
   (fn [acc tx]
     (let [{eid :db/id nm :account/external-name} (:transaction/account tx)]
       (cond-> acc
         eid (update eid (fn [row]
                           (-> (or row {:account-id eid :name (or nm "Unknown") :computed-delta 0M})
                               (update :computed-delta + (amount tx))))))))
   {}
   txs))

(defn reconcile-period
  "Reconcile one strict balance-delta period — currently used for month-boundary
   snapshots — against the account's transactions in its span. `start-balance`/
   `end-balance` are bigdecs (nil = not yet entered); `span-txns` the account's
   transactions in (start, end]. Returns
   {:reported :computed :difference :status}: :reported = end − start (nil if a balance is
   missing), :computed = Σ signed amounts, :difference = reported − computed, and :status is
   :no-snapshot / :reconciled / :drift — the same period-delta verdict.
   Pure."
  [start-balance end-balance span-txns & {:keys [tolerance] :or {tolerance default-tolerance}}]
  (let [computed (computed-total span-txns)
        reported (when (and (some? start-balance) (some? end-balance)) (- end-balance start-balance))]
    (verdict reported computed tolerance)))

(defn reconcile-statement-period
  "Reconcile a user-entered statement against transactions in its span. Unlike synced
   month-boundary snapshots, statement balances are typed from institution statements whose
   sign convention can be opposite the app's signed transaction convention. Compare both
   possible balance deltas and keep the one closest to the tracked activity, so either
   `end − start` or `start − end` statement polarity can tie out without false drift."
  [start-balance end-balance span-txns & {:keys [tolerance] :or {tolerance default-tolerance}}]
  (let [computed (computed-total span-txns)]
    (if (and (some? start-balance) (some? end-balance))
      (let [forward  (- end-balance start-balance)
            reversed (- start-balance end-balance)
            reported (min-key #(abs (- % computed)) forward reversed)]
        (verdict reported computed tolerance))
      (verdict nil computed tolerance))))

(defn reconcile-row
  "Combine one account's computed delta with its reported delta (nil when a
   boundary snapshot is missing) into a display row:
   {:account-id :name :computed-delta :reported-delta :difference :status}, where
   :difference is reported-computed (nil without a reported delta) and :status is
   :no-snapshot / :reconciled / :drift."
  [{:keys [account-id name computed-delta]} reported-delta tolerance]
  (let [difference (when (some? reported-delta) (- reported-delta computed-delta))]
    {:account-id     account-id
     :name           name
     :computed-delta computed-delta
     :reported-delta reported-delta
     :difference     difference
     :status         (cond
                       (nil? reported-delta)              :no-snapshot
                       (<= (abs difference) tolerance)    :reconciled
                       :else                              :drift)}))

(defn reconcile
  "Join computed deltas (from `account-computed-deltas`) with `reported`, a map of
   account-eid -> reported-delta (bigdec, or absent/nil when a boundary snapshot is
   missing), into a name-sorted vector of reconcile rows. Driven by the accounts
   that have transactions this month; an account with activity but no reported
   snapshot surfaces as :status :no-snapshot rather than being dropped."
  [computed reported & {:keys [tolerance] :or {tolerance default-tolerance}}]
  (->> (vals computed)
       (map (fn [row] (reconcile-row row (get reported (:account-id row)) tolerance)))
       (sort-by :name)
       vec))

(defn all-reconciled?
  "True when every row is :reconciled (the balance half of the close gate). An empty
   set of rows is vacuously reconciled; any :drift or :no-snapshot row blocks."
  [rows]
  (every? #(= :reconciled (:status %)) rows))

;; --- Coverage-strict closing -------------------------------------------------
;; A month-boundary snapshot reconciling is only ONE way an account's activity can be fully
;; explained — a credit card whose statements each tie out never gets a month-boundary balance
;; at all. Coverage-strict closing generalizes: an account is done for the month when EVERY
;; transaction in it falls inside SOME reconciled period (month-boundary or statement), not
;; only when the single month-boundary period reconciles.

(defn covered?
  "True when posted-date `d` (java.util.Date) falls inside any reconciled span in `spans`.
   Each span is {:start Date :end Date}; membership is the ledger's half-open (start, end]
   convention (d > start AND d <= end), matching db.transactions/list-for-account-range so a
   txn is 'covered' by exactly the periods that would have summed it. Only RECONCILED periods
   are passed in spans — a drifting or blank period contributes no coverage."
  [^Date d spans]
  (boolean
   (some (fn [{:keys [^Date start ^Date end]}]
           (and (.after d start)
                (.before d (Date. (+ (.getTime end) 86400000)))))
         spans)))

(defn month-coverage
  "Coverage-strict account status for a month. `txs` = the account's month transactions (each
   with :transaction/posted-date); `spans` = the reconciled spans (see `covered?`) established by
   the month-boundary period and/or statements that tied out; `any-periods?` = whether the account
   has ANY period on file (month-boundary balances entered OR at least one statement), even if it
   doesn't reconcile. Returns
     {:status :reconciled|:partial|:no-snapshot :uncovered n :first-uncovered Date-or-nil}
   :no-snapshot — nothing on file to check against.
   :reconciled  — every month txn is inside a reconciled span (needs at least one reconciled span).
   :partial     — periods exist but the month isn't fully covered (uncovered txns, or an entered
                  period that doesn't reconcile)."
  [txs spans any-periods?]
  (let [uncovered (remove #(covered? (:transaction/posted-date %) spans) txs)
        n (count uncovered)]
    {:status (cond
               (and (empty? spans) (not any-periods?)) :no-snapshot
               (and (zero? n) (seq spans))             :reconciled
               :else                                    :partial)
     :uncovered n
     :first-uncovered (some->> (seq uncovered) (map :transaction/posted-date) (sort compare) first)}))

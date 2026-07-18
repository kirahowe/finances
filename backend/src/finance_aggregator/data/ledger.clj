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
  (:require
   [clojure.string :as str])
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
   transactions). Splits never change an account's total — a split parent is excluded
   from the list fns, and its part rows carry the same account and sum exactly to its
   amount — so summing the rows as given is already correct, with no split awareness
   needed here. `:name` prefers the user's rename overlay (:account/display-name) over
   the provider's :account/external-name — a data-layer-local `(or …)`, the same
   preference web.accounts/account-label applies in the view layer; this namespace is
   pure math and can't reach up to it. `:institution` is `{:name :logo}` lifted from the
   pulled account's :account/institution, nil when the account has none — carried
   through so the reconcile panel can render an avatar without a second query. Returns
   {account-eid {:account-id eid :name str :computed-delta bigdec :institution map-or-nil}}."
  [txs]
  (reduce
   (fn [acc tx]
     (let [{eid :db/id nm :account/external-name dn :account/display-name
            im :account/institution} (:transaction/account tx)
           label (or (when-not (str/blank? dn) dn) nm "Unknown")
           institution (when im {:name (:institution/name im) :logo (:institution/logo im)})]
       (cond-> acc
         eid (update eid (fn [row]
                           (-> (or row {:account-id eid :name label :computed-delta 0M
                                        :institution institution})
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

(defn effective-statement-polarity
  "The polarity to compare an account's statements against: an explicit
   :account/statement-polarity always wins; when absent, it defaults by account type —
   :credit accounts default :inverted (typical credit-card paperwork, printed opposite the
   app's signed-amount convention), everything else defaults :as-signed. Pure — `account` is
   any map carrying :account/type / :account/statement-polarity (a pulled account entity, or
   db.statements/->display's statement map, which carries those two fields through
   flattened-but-namespaced for exactly this call)."
  [{:account/keys [statement-polarity type]}]
  (or statement-polarity
      (if (= type :credit) :inverted :as-signed)))

(defn reconcile-statement-period
  "Reconcile a user-entered statement against transactions in its span, using the account's
   DECLARED (or type-defaulted — effective-statement-polarity) `polarity` rather than
   guessing: :as-signed statements run WITH the app's signed-amount convention (reported =
   end − start, same direction as reconcile-period); :inverted statements run AGAINST it
   (reported = start − end — typical credit-card paperwork, where a lower printed balance
   after a payment reads as a POSITIVE change on the statement even though the tracked
   activity is negative). Strict: no more comparing both directions and keeping whichever
   lands closer to tracked activity — that heuristic could MASK real drift on the correct
   polarity by preferring an accidentally-closer wrong one."
  [start-balance end-balance span-txns & {:keys [tolerance polarity]
                                          :or {tolerance default-tolerance polarity :as-signed}}]
  (let [computed (computed-total span-txns)
        reported (when (and (some? start-balance) (some? end-balance))
                   (if (= polarity :inverted)
                     (- start-balance end-balance)
                     (- end-balance start-balance)))]
    (verdict reported computed tolerance)))

(defn reconcile-row
  "Combine one account's computed delta with its reported delta (nil when a
   boundary snapshot is missing) into a display row:
   {:account-id :name :institution :computed-delta :reported-delta :difference :status},
   where :difference is reported-computed (nil without a reported delta) and :status is
   :no-snapshot / :reconciled / :drift."
  [{:keys [account-id name institution computed-delta]} reported-delta tolerance]
  (let [difference (when (some? reported-delta) (- reported-delta computed-delta))]
    {:account-id     account-id
     :name           name
     :institution    institution
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

(defn effective-posted-date
  "THE date bucketing/coverage/transfer-matching goes by for a transaction: the user's
   manual override when present, else the provider's posted-date guess, else the plain
   transaction date. Only Plaid supplies a genuinely independent posted date — lunchflow,
   CSV and manual imports hand `parse-transaction` a single date, which copies it into
   :transaction/posted-date as a guess (see doc/plans/manual-posted-dates.md). When that
   guess crosses a statement boundary the user corrects it via
   db.transactions/set-user-posted-date!, which writes :transaction/user-posted-date as
   an additive overlay — the imported :transaction/posted-date is never mutated, so this
   chain (not the raw posted-date) is the one true read path every caller must use.
   `tx` is a pulled transaction map carrying some subset of :transaction/user-posted-date,
   :transaction/posted-date, :transaction/date. Pure."
  ^Date [tx]
  (or (:transaction/user-posted-date tx)
      (:transaction/posted-date tx)
      (:transaction/date tx)))

(defn effective-transaction-date
  "THE TRANSACTION-DATE-basis analysis read for a transaction — the date the purchase
   actually happened (Plaid's authorized_date; see plaid/data.clj), used ONLY by the
   transactions page's `basis` lens (:transaction, an alternative to the default :posted
   bucketing) to re-bucket the table/rollup/counts by when the money moved rather than when
   the bank posted it. Deliberately IGNORES :transaction/user-posted-date — that override is
   a POSTED-date correction (moving a guessed posted-date across a statement boundary; see
   effective-posted-date), orthogonal to this basis, so it never wins here even when
   :transaction/date is also present. Falls back through the effective-posted chain
   (user-posted-date, then posted-date) when :transaction/date itself is absent — some
   seeds/imports/manual entries carry only a posted-date. Reconciliation, coverage and
   transfer-matching NEVER use this — they use effective-posted-date, always. Pure."
  ^Date [tx]
  (or (:transaction/date tx) (effective-posted-date tx)))

(defn covered?
  "True when effective posted date `d` (java.util.Date — see effective-posted-date) falls
   inside any reconciled span in `spans`. Each span is {:start Date :end Date}; membership
   is the ledger's half-open (start, end] convention (d > start AND d <= end), matching
   db.transactions/list-for-account-range so a txn is 'covered' by exactly the periods
   that would have summed it. Only RECONCILED periods are passed in spans — a drifting or
   blank period contributes no coverage."
  [^Date d spans]
  (boolean
   (some (fn [{:keys [^Date start ^Date end]}]
           (and (.after d start)
                (.before d (Date. (+ (.getTime end) 86400000)))))
         spans)))

(defn statement-opening-boundary
  "The EXCLUSIVE lower boundary — a `:start`/`from` value for the shared half-open (start, end]
   sum & coverage math (covered?, db.transactions/list-for-account-range) — for a statement
   whose PRINTED period starts on `start-date` (inclusive, as it reads off the statement). A
   statement's start-balance is the 'previous balance' carried in BEFORE its first day, so
   activity ON start-date belongs to the period; but the half-open convention treats the start
   as an already-counted balance day and excludes it. Handing back the day BEFORE start-date
   therefore makes start-date the first INCLUDED day. Contrast a month-boundary period, whose
   opening-date genuinely IS an already-counted prior-balance day and so passes straight through
   with no shift. UTC-midnight day granularity (see effective-posted-date)."
  ^Date [^Date start-date]
  (Date. (- (.getTime start-date) 86400000)))

(defn month-coverage
  "Coverage-strict account status for a month. `txs` = the account's month transactions
   (each resolvable to an effective posted date — see effective-posted-date); `spans` =
   the reconciled spans (see `covered?`) established by the month-boundary period and/or
   statements that tied out; `any-periods?` = whether the account has ANY period on file
   (month-boundary balances entered OR at least one statement), even if it doesn't
   reconcile. A manual posted-date override can move a txn into or out of coverage —
   coverage is always computed on the effective date, never the raw imported posted-date.
   Returns
     {:status :reconciled|:partial|:no-snapshot :uncovered n :first-uncovered Date-or-nil}
   :no-snapshot — nothing on file to check against.
   :reconciled  — every month txn is inside a reconciled span (needs at least one reconciled span).
   :partial     — periods exist but the month isn't fully covered (uncovered txns, or an entered
                  period that doesn't reconcile)."
  [txs spans any-periods?]
  (let [uncovered (remove #(covered? (effective-posted-date %) spans) txs)
        n (count uncovered)]
    {:status (cond
               (and (empty? spans) (not any-periods?)) :no-snapshot
               (and (zero? n) (seq spans))             :reconciled
               :else                                    :partial)
     :uncovered n
     :first-uncovered (some->> (seq uncovered) (map effective-posted-date) (sort compare) first)}))

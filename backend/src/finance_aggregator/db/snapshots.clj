(ns finance-aggregator.db.snapshots
  "Reported-balance history. Each provider sync records the institution-reported
   balance per account (one row per account per UTC day, idempotent) and stamps
   the account's :account/balance-as-of. Reconciliation (Phase 4) reads this
   history to detect drift between the ledger and what the bank reports.

   Data layer: touches datalevin. The `as-of` timestamp is passed in by the
   caller (the sync orchestrator) so this stays deterministic under test."
  (:require
   [datalevin.core :as d]
   [finance-aggregator.utils :as u])
  (:import
   [java.util Date]))

(defn- snapshot-id
  "Idempotency key: one snapshot per account per UTC calendar day."
  [external-id ^Date as-of]
  (str external-id ":" (u/date->local-date as-of)))

(defn record-reported-balances!
  "For each account map carrying :account/reported-balance, stamp
   :account/balance-as-of and upsert a daily reported-balance snapshot (keyed by
   account + UTC day, so re-syncing the same day overwrites rather than
   duplicates). Accounts without a reported balance are skipped. `as-of` is the
   sync timestamp. Accounts must already be persisted (the snapshot's account
   ref and the balance-as-of upsert both resolve by :account/external-id).
   Returns db-conn."
  [db-conn accounts ^Date as-of]
  (let [tx (mapcat
            (fn [{:account/keys [external-id reported-balance]}]
              (when (and external-id reported-balance)
                [{:account/external-id external-id
                  :account/balance-as-of as-of}
                 {:snapshot/id (snapshot-id external-id as-of)
                  :snapshot/account [:account/external-id external-id]
                  :snapshot/date as-of
                  :snapshot/balance reported-balance
                  :snapshot/source :reported}]))
            accounts)]
    (when (seq tx)
      (d/transact! db-conn (vec tx)))
    db-conn))

(defn month-end-instant
  "Last instant of `month` (YYYY-MM) — 1 ms before the next month begins. A manual
   statement-ending balance is recorded here, so it sits inside the month as that
   month's ending reading."
  ^Date [month]
  (Date. (dec (.getTime ^Date (:end-date (u/month-date-range month))))))

(defn record-manual-balance!
  "Record a user-entered statement ending balance for the account (entity id
   `account-eid`) for `month` (YYYY-MM) as a :manual snapshot at the month's last
   instant — the reported side of the close check when the bank sync has no
   month-boundary snapshot. Idempotent per account-month (re-entering overwrites).
   Kept in its own id namespace so it coexists with any :reported snapshot and wins
   the boundary tie-break (see reported-sources / source-rank). Returns db-conn."
  [conn account-eid month balance]
  (let [ext (:account/external-id (d/pull (d/db conn) [:account/external-id] account-eid))]
    (d/transact! conn [{:snapshot/id      (str ext ":manual:" month)
                        :snapshot/account account-eid
                        :snapshot/date    (month-end-instant month)
                        :snapshot/balance (bigdec balance)
                        :snapshot/source  :manual}])
    conn))

;; --- Reading: reported balance deltas for the monthly close ----------------

;; Sources that represent the institution's own reported truth (so they can anchor
;; a month boundary). :reported is written at sync time; :manual is a user-entered
;; statement balance (Phase 2). :calculated snapshots are our own figures and must
;; never be read back as "what the bank reported".
(def ^:private reported-sources [:reported :manual])

;; Tie-break for snapshots that share the exact same date: a user-entered :manual
;; statement balance is more authoritative than the auto-recorded :reported figure,
;; so it sorts last and wins under `last`.
(def ^:private source-rank {:reported 0 :manual 1})

(defn- latest-snapshot-before
  "The most recent reported [date balance] pair for `account-eid` strictly before
   `before` (a Date), or nil when no reported snapshot precedes it. When a
   :reported and a :manual snapshot share the exact same date, :manual wins — a
   user-entered statement balance outranks the auto-recorded reported figure."
  [db account-eid ^Date before]
  (when-let [[d bal] (->> (d/q '[:find ?d ?bal ?src
                                 :in $ ?acct ?before [?src ...]
                                 :where
                                 [?s :snapshot/account ?acct]
                                 [?s :snapshot/source ?src]
                                 [?s :snapshot/date ?d]
                                 [?s :snapshot/balance ?bal]
                                 [(< ?d ?before)]]
                               db account-eid before reported-sources)
                          (sort-by (fn [[d _ src]] [d (source-rank src 0)]))
                          last)]
    [d bal]))

(defn- prior-month-start
  "First instant of the calendar month before `month-str` (YYYY-MM) — the earliest a
   snapshot can sit and still count as that month's ending balance."
  ^java.util.Date [month-str]
  (let [{:keys [year month]} (u/parse-month-string month-str)
        prev (if (= month 1) {:year (dec year) :month 12}
                 {:year year :month (dec month)})]
    (:start-date (u/month-date-range (format "%04d-%02d" (:year prev) (:month prev))))))

(defn- reported-delta*
  "Reported-balance delta for `account-eid` across `month` against an already-
   deref'd `db`. See `reported-delta` for the full contract."
  [db account-eid month]
  (let [{:keys [start-date end-date]} (u/month-date-range month)
        prior-start (prior-month-start month)
        end   (latest-snapshot-before db account-eid end-date)
        start (latest-snapshot-before db account-eid start-date)]
    (when (and end start
               (not (.before ^Date (first end) start-date))
               (not (.before ^Date (first start) prior-start)))
      (- (second end) (second start)))))

(defn reported-delta
  "The change in the bank-reported balance for `account-eid` across `month`
   (a YYYY-MM string): the balance at the month end minus the balance at the month
   start (the prior month's ending balance). This is the reported side of the
   period-delta close check (see data.ledger).

   Returns a bigdec, or nil when the month can't be auto-reconciled from the
   snapshot history — either boundary lacks a reported snapshot, or a boundary
   reading falls outside its month: the 'end' must sit within `month`, and the
   'start' must sit within the immediately prior calendar month (otherwise a sync
   gap would make the delta span multiple months and read as spurious drift). A
   nil result means the month needs a manual statement balance instead. Reads the
   snapshot history."
  [conn account-eid month]
  (reported-delta* (d/db conn) account-eid month))

(defn reported-deltas
  "`reported-delta` for each account entity-id in `account-eids`, as a map
   {account-eid bigdec}. Derefs the db once and reuses it across all accounts.
   Accounts without a usable boundary pair are omitted (the caller treats a
   missing entry as :no-snapshot)."
  [conn account-eids month]
  (let [db (d/db conn)]
    (into {}
          (keep (fn [eid]
                  (when-let [d (reported-delta* db eid month)]
                    [eid d])))
          account-eids)))

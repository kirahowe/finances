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

;; --- Reading: reported balance deltas for the monthly close ----------------

;; Sources that represent the institution's own reported truth (so they can anchor
;; a month boundary). :reported is written at sync time; :manual is a user-entered
;; statement balance (Phase 2). :calculated snapshots are our own figures and must
;; never be read back as "what the bank reported".
(def ^:private reported-sources [:reported :manual])

(defn- latest-snapshot-before
  "The most recent reported [date balance] pair for `account-eid` strictly before
   `before` (a Date), or nil when no reported snapshot precedes it."
  [db account-eid ^Date before]
  (->> (d/q '[:find ?d ?bal
              :in $ ?acct ?before [?src ...]
              :where
              [?s :snapshot/account ?acct]
              [?s :snapshot/source ?src]
              [?s :snapshot/date ?d]
              [?s :snapshot/balance ?bal]
              [(< ?d ?before)]]
            db account-eid before reported-sources)
       (sort-by first)
       last))

(defn reported-delta
  "The change in the bank-reported balance for `account-eid` across `month`
   (a YYYY-MM string): the balance at the month end minus the balance at the month
   start (the prior month's ending balance). This is the reported side of the
   period-delta close check (see data.ledger).

   Returns a bigdec, or nil when the month can't be auto-reconciled from the
   snapshot history — either boundary lacks a reported snapshot, or the only
   snapshots predate the month (so the 'end' reading is the same stale figure as
   the 'start' and the delta would be meaningless). A nil result means the month
   needs a manual statement balance instead. Reads the snapshot history."
  [conn account-eid month]
  (let [{:keys [start-date end-date]} (u/month-date-range month)
        db    (d/db conn)
        end   (latest-snapshot-before db account-eid end-date)
        start (latest-snapshot-before db account-eid start-date)]
    (when (and end start (not (.before ^Date (first end) start-date)))
      (- (second end) (second start)))))

(defn reported-deltas
  "`reported-delta` for each account entity-id in `account-eids`, as a map
   {account-eid bigdec}. Accounts without a usable boundary pair are omitted (the
   caller treats a missing entry as :no-snapshot)."
  [conn account-eids month]
  (into {}
        (keep (fn [eid]
                (when-let [d (reported-delta conn eid month)]
                  [eid d])))
        account-eids))

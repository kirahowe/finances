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

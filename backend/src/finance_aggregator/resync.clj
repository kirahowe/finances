(ns finance-aggregator.resync
  "The trigger-decoupled resilient-sync core. Every trigger - `bb resync`, the
   `Sync now` setup action, a future cron - calls `resync-all!`; this namespace
   never schedules itself.

   A *connection* (`db.connections`) is the unit of work: one linked provider
   instance with an opaque, resumable sync-state. `resync-connection!` runs one
   resumable pass for a single connection (re-running resumes from the persisted
   sync-state); `resync-all!` reconciles the credential registry into connections
   and drives every one that is due, isolating failures so one broken connection
   never stalls the batch.

   Resilience lives here, not in a background poll: a transient failure backs the
   connection off (capped-exponential `provider.retry`) and the next pass retries
   when due; a re-auth failure parks it at :needs-reconnect; a historical backfill
   that is still incomplete parks at :backfilling and the next pass advances it
   from the stored cursor until Plaid reports it complete.

   Provider-agnostic: the only provider-aware spot is `connection-deps` (which
   assembles a provider's per-connection deps); driving and error classification
   both go through the provider seam (`provider/fetch-*`, `provider/classify-sync-
   error`), so adding a provider is new seam methods, not new orchestration. Plaid
   is the only wired provider today (its multimethods are registered by the
   load-only require below)."
  (:require
   [finance-aggregator.db.connections :as connections]
   [finance-aggregator.db.credentials :as creds]
   [finance-aggregator.lib.log :as log]
   ;; Load-only: registers the :plaid provider multimethods (fetch-accounts /
   ;; fetch-transactions / classify-sync-error). Without it the seam dispatch
   ;; would have no methods.
   [finance-aggregator.plaid.provider]
   [finance-aggregator.provider :as provider]
   [finance-aggregator.provider.retry :as retry]
   [finance-aggregator.provider.sync :as sync])
  (:import
   [java.util Date]))

;;; Registry reconciliation -------------------------------------------------

(defn- ensure-plaid-connections!
  "Ensure a :connection/* row exists for every Plaid Item credential, seeding the
   cursor from legacy sync fields on first creation (the lazy migration). Existing
   connections are untouched."
  [db-conn]
  (doseq [cred (creds/list-plaid-item-credential-entities db-conn)]
    (connections/ensure-from-credential! db-conn cred)))

;;; Per-connection deps (the one provider-aware spot) -----------------------

(defn- connection-deps
  "Assemble the per-connection deps the sync seam needs, dispatching on provider.
   The ONE provider-aware spot in the core - both the drive and the error
   classification downstream are generic.

   For :plaid: the decrypted access token (by external-id = item-id), the item
   context, the ws :status-key (item-id), :connection-id (enables per-page cursor
   persistence), and the starting :cursor seeded from the stored sync-state. A
   missing token leaves :access-token nil; the drive then fails naturally and the
   error path records it."
  [{:keys [db-conn secrets] :as deps}
   {:connection/keys [provider id external-id institution-name sync-state]}]
  (case provider
    :plaid (assoc deps
                  :connection-id id
                  :status-key external-id
                  :item-id external-id
                  :institution-name institution-name
                  :access-token (creds/get-plaid-item-credential db-conn secrets external-id)
                  :cursor sync-state)
    (throw (ex-info "No resync driver for provider" {:provider provider}))))

;;; Outcome handling --------------------------------------------------------

(def ^:private provider-status->connection-status
  "Map a provider's terminal status to the connection status vocabulary. Plaid's
   :syncing-historical (backfill incomplete) becomes the resumable :backfilling."
  {:synced :synced :syncing-historical :backfilling :pending :pending})

(defn- handle-error!
  "Classify a sync failure through the connection's provider and persist the
   resulting error/backoff state. The provider returns a generic
   {:action :error-code :error-message}; the core owns how that maps to
   connection state and backoff. Returns the resulting connection status keyword.
     :resolved  -> clear error, park :pending (e.g. Plaid LOGIN_REPAIRED self-heal)
     :retry     -> :stale with capped-exponential backoff until the budget
                   (attempt count OR elapsed wall-clock) is spent, then a slow
                   steady :stale retry cadence so a long outage still self-heals
     :reconnect -> :needs-reconnect (user must re-auth; never auto-retried)
     :fail      -> :failed (surfaced; retried next pass, no backoff)"
  [{:keys [db-conn]} conn-deps
   {:connection/keys [id provider retry-count first-failure-at]} ^Exception e]
  (let [{:keys [action error-code error-message]} (provider/classify-sync-error provider conn-deps e)
        base {:error-code error-code :error-message error-message}]
    (case action
      :resolved  (do (connections/clear-error! db-conn id)
                     (connections/set-status! db-conn id :pending)
                     :pending)
      :retry     (let [now (Date.)
                       started (or first-failure-at now)
                       elapsed (- (.getTime now) (.getTime ^Date started))
                       n (or retry-count 0)]
                   (if (retry/exhausted? retry/default-policy {:retry-count n :elapsed-ms elapsed})
                     ;; Budget spent: fall back to a slow steady retry, freezing
                     ;; the count + streak start (the broken-since signal).
                     (connections/set-error! db-conn id :stale
                                             (assoc base
                                                    :retry-count n
                                                    :first-failure-at started
                                                    :next-retry-at (retry/stale-retry-at retry/default-policy now)))
                     (connections/set-error! db-conn id :stale
                                             (assoc base
                                                    :retry-count (inc n)
                                                    :first-failure-at started
                                                    :next-retry-at (retry/next-retry-at retry/default-policy n now))))
                   :stale)
      :reconnect (do (connections/set-error! db-conn id :needs-reconnect base)
                     :needs-reconnect)
      :fail      (do (connections/set-error! db-conn id :failed base)
                     :failed))))

;;; Public entry points -----------------------------------------------------

(defn resync-connection!
  "Run one resumable sync pass for a single connection. Marks :syncing, drives the
   provider, then records the terminal connection status - or classifies the
   error into backoff/reconnect/fail. The provider sync itself never escapes:
   any failure is captured into the connection's state. (A failure to even record
   the attempt or assemble deps propagates to resync-all!'s per-connection
   isolation.) Returns the resulting connection status keyword."
  [{:keys [db-conn] :as deps} {:connection/keys [id] :as connection}]
  (connections/record-attempt! db-conn id)
  (let [conn-deps (connection-deps deps connection)]
    (try
      (let [terminal (sync/sync-provider! conn-deps (:connection/provider connection))]
        (if (= :synced terminal)
          (do (connections/record-success! db-conn id :synced) :synced)
          ;; A page succeeded -> clear any prior error, then park at the mapped
          ;; non-terminal status (:backfilling / :pending) for the next pass.
          (let [status (get provider-status->connection-status terminal terminal)]
            (connections/clear-error! db-conn id)
            (connections/set-status! db-conn id status)
            status)))
      (catch Exception e
        (log/error "Resync failed for connection" {:connection-id id :error (.getMessage e)})
        (handle-error! deps conn-deps connection e)))))

(def ^:private stuck-syncing-ms
  "A resync pass should finish well within this; a connection still :syncing past
   it is presumed crashed mid-pass and is eligible to run again."
  600000) ; 10 minutes

(defn- due?
  "A connection is due for an automatic pass unless it is awaiting user re-auth
   (:needs-reconnect), still backing off (next-retry-at in the future), or already
   in flight - :syncing with a recent last-attempt-at (another overlapping
   trigger). A long-stuck :syncing is presumed crashed and becomes due again."
  [^Date now {:connection/keys [status next-retry-at last-attempt-at]}]
  (and (not= status :needs-reconnect)
       (not (and (= status :syncing)
                 last-attempt-at
                 (< (- (.getTime now) (.getTime ^Date last-attempt-at)) stuck-syncing-ms)))
       (or (nil? next-retry-at) (not (.after ^Date next-retry-at now)))))

(defn resync-all!
  "Reconcile the credential registry into connections, then resync every due
   connection. Skips connections that are backing off, awaiting re-auth, or
   already syncing. Per-connection isolated (one failure never stalls the batch).
   Returns a summary {:total :skipped [ids] :results [{:id :status} ...]}.

   deps: {:db-conn .. :secrets .. :plaid-config ..}"
  [{:keys [db-conn] :as deps}]
  (ensure-plaid-connections! db-conn)
  (let [now (Date.)
        conns (connections/list-connections db-conn)
        grouped (group-by #(boolean (due? now %)) conns)
        due (get grouped true [])
        skipped (get grouped false [])
        results (doall
                 (for [c due]
                   (try
                     {:id (:connection/id c) :status (resync-connection! deps c)}
                     (catch Exception e
                       (log/error "Unexpected resync error"
                                  {:connection-id (:connection/id c) :error (.getMessage e)})
                       {:id (:connection/id c) :status :failed :error (.getMessage e)}))))]
    (log/info "Resync pass complete"
              {:total (count conns)
               :due (count due)
               :skipped (count skipped)
               :by-status (frequencies (map :status results))})
    {:total (count conns)
     :skipped (mapv :connection/id skipped)
     :results results}))

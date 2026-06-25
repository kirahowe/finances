(ns finance-aggregator.db.connections
  "Provider-agnostic sync-state for a linked institution connection.

   A *connection* is one linked provider instance whose transactions we sync: a
   Plaid Item, a Lunchflow link, a future Open-Banking grant. It holds only sync
   bookkeeping - status, an opaque per-provider sync-state (Plaid's cursor),
   freshness timestamps, error/backoff state. The encrypted access token lives
   separately on :credential/* (different lifecycle, different blast radius);
   providers with no stored secret (Lunchflow reads its API key from secrets)
   still get a connection row, so sync status is uniform across providers.

   Generic on purpose: nothing here knows what :connection/sync-state *means* -
   the provider interprets it. Status vocabulary:
     :pending :syncing :backfilling :synced :stale :needs-reconnect :failed

   Data layer: the only layer (besides db/*) that touches datalevin. Single-user
   (auth/user-id); the :connection/user ref gives the eventual multi-user
   migration one place to change."
  (:require
   [datalevin.core :as d]
   [finance-aggregator.auth :as auth])
  (:import
   [java.util Date]))

(defn- ensure-user!
  "Create the hardcoded test user if absent (the :connection/user lookup ref
   must resolve to an existing entity)."
  [conn]
  (when-not (d/entity (d/db conn) [:user/id auth/user-id])
    (d/transact! conn [{:user/id auth/user-id :user/created-at (Date.)}])))

(defn- eid
  "Entity id of the connection with `id`, or nil if it doesn't exist."
  [conn id]
  (:db/id (d/pull (d/db conn) '[:db/id] [:connection/id id])))

(defn ensure-connection!
  "Idempotently create the connection row keyed by :connection/id. An existing
   row is left untouched (re-asserting only the identity attr would be a no-op,
   but we skip it to avoid clobbering live sync-state). `attrs` supplies
   :provider (required) and optional :external-id / :institution-name. New rows
   start :pending. Returns the connection entity."
  [conn {:keys [id provider external-id institution-name]}]
  (ensure-user! conn)
  (when-not (eid conn id)
    (d/transact! conn [(cond-> {:connection/id id
                                :connection/user [:user/id auth/user-id]
                                :connection/provider provider
                                :connection/status :pending
                                :connection/created-at (Date.)}
                         external-id      (assoc :connection/external-id external-id)
                         institution-name (assoc :connection/institution-name institution-name))]))
  (d/pull (d/db conn) '[*] [:connection/id id]))

(def ^:private legacy-status->status
  "Map a credential's legacy :credential/sync-status to the connection status
   vocabulary when seeding (the lazy migration). The cursor is always carried
   over when present (so a pre-existing item resumes incrementally rather than
   re-fetching); only the *status* is remapped - interrupted legacy states
   (:syncing/:failed) seed :pending so the next pass drives them, while a clean
   :synced or a mid-backfill :syncing-historical carries its meaning over."
  {:synced              :synced
   :syncing-historical  :backfilling
   :pending             :pending
   :syncing             :pending
   :failed              :pending})

(defn ensure-from-credential!
  "Idempotently ensure the Plaid connection for a credential entity, seeding its
   sync-state from the credential's legacy sync fields on FIRST creation only -
   the lazy cursor migration. The cursor carries over when present, so a
   pre-existing item resumes from where it left off rather than re-fetching from
   scratch. `cred` is a raw pulled :credential/* map (item-id, institution-name,
   and the legacy :credential/sync-cursor / sync-status / last-sync-at /
   transaction-count). An existing connection is left untouched. Returns the
   connection entity."
  [conn {:credential/keys [item-id institution-name sync-cursor sync-status
                           last-sync-at transaction-count]}]
  (ensure-user! conn)
  (let [id (str "plaid:" item-id)]
    (when-not (eid conn id)
      (d/transact! conn [(cond-> {:connection/id id
                                  :connection/user [:user/id auth/user-id]
                                  :connection/provider :plaid
                                  :connection/external-id item-id
                                  :connection/status (get legacy-status->status sync-status :pending)
                                  :connection/created-at (Date.)}
                           institution-name  (assoc :connection/institution-name institution-name)
                           sync-cursor       (assoc :connection/sync-state sync-cursor)
                           last-sync-at      (assoc :connection/last-success-at last-sync-at)
                           transaction-count (assoc :connection/transaction-count transaction-count))]))
    (d/pull (d/db conn) '[*] [:connection/id id])))

(defn get-connection
  "Pull the connection entity by id, or nil if absent."
  [conn id]
  (when (eid conn id)
    (d/pull (d/db conn) '[*] [:connection/id id])))

(defn list-connections
  "All connections for the user, optionally filtered by provider keyword."
  ([conn] (list-connections conn nil))
  ([conn provider]
   (vec
    (if provider
      (d/q '[:find [(pull ?e [*]) ...]
             :in $ ?uid ?prov
             :where
             [?u :user/id ?uid]
             [?e :connection/user ?u]
             [?e :connection/provider ?prov]]
           (d/db conn) auth/user-id provider)
      (d/q '[:find [(pull ?e [*]) ...]
             :in $ ?uid
             :where
             [?u :user/id ?uid]
             [?e :connection/user ?u]]
           (d/db conn) auth/user-id)))))

(defn get-sync-state
  "Opaque per-provider sync-state string (e.g. Plaid cursor), or nil."
  [conn id]
  (:connection/sync-state
   (d/pull (d/db conn) '[:connection/sync-state] [:connection/id id])))

(defn set-sync-state!
  "Persist the opaque sync-state. Returns true if the connection exists."
  [conn id sync-state]
  (when-let [e (eid conn id)]
    (d/transact! conn [{:db/id e :connection/sync-state sync-state}])
    true))

(defn set-status!
  "Set status, with optional :transaction-count. Returns true if it exists."
  ([conn id status] (set-status! conn id status {}))
  ([conn id status {:keys [transaction-count]}]
   (when-let [e (eid conn id)]
     (d/transact! conn [(cond-> {:db/id e :connection/status status}
                          transaction-count (assoc :connection/transaction-count transaction-count))])
     true)))

(defn record-attempt!
  "Mark the start of a sync attempt: :syncing + last-attempt-at now. Returns
   true if the connection exists."
  [conn id]
  (when-let [e (eid conn id)]
    (d/transact! conn [{:db/id e
                        :connection/status :syncing
                        :connection/last-attempt-at (Date.)}])
    true))

(defn clear-error!
  "Retract error/backoff fields (error code/message, the failure-streak start,
   the next-retry schedule) and reset retry-count to 0. Safe to call when no
   error is present. Returns true if the connection exists."
  [conn id]
  (when-let [e (eid conn id)]
    (d/transact! conn [[:db/retract e :connection/error-code]
                       [:db/retract e :connection/error-message]
                       [:db/retract e :connection/first-failure-at]
                       [:db/retract e :connection/next-retry-at]
                       {:db/id e :connection/retry-count 0}])
    true))

(defn record-success!
  "Mark a successful sync: status (default :synced) + last-success-at now, clear
   error/backoff, optional :transaction-count. Returns true if it exists."
  ([conn id] (record-success! conn id :synced {}))
  ([conn id status] (record-success! conn id status {}))
  ([conn id status {:keys [transaction-count]}]
   (when-let [e (eid conn id)]
     (clear-error! conn id)
     (d/transact! conn [(cond-> {:db/id e
                                 :connection/status status
                                 :connection/last-success-at (Date.)}
                          transaction-count (assoc :connection/transaction-count transaction-count))])
     true)))

(defn set-error!
  "Record a sync error: status (:stale / :needs-reconnect / :failed) plus
   optional :error-code, :error-message, and backoff (:retry-count /
   :first-failure-at / :next-retry-at). Returns true if the connection exists."
  [conn id status {:keys [error-code error-message retry-count first-failure-at next-retry-at]}]
  (when-let [e (eid conn id)]
    (d/transact! conn [(cond-> {:db/id e :connection/status status}
                         error-code       (assoc :connection/error-code error-code)
                         error-message    (assoc :connection/error-message error-message)
                         retry-count      (assoc :connection/retry-count retry-count)
                         first-failure-at (assoc :connection/first-failure-at first-failure-at)
                         next-retry-at    (assoc :connection/next-retry-at next-retry-at))])
    true))

(defn delete-connection!
  "Delete the connection row. Returns true if it existed."
  [conn id]
  (when-let [e (eid conn id)]
    (d/transact! conn [[:db/retractEntity e]])
    true))

(ns finance-aggregator.web.accounts
  "Pure presentation-data helpers + the /setup view-model presenter — kept out of
   the view so the display rules (source label, provider-native type, ordering,
   connection grouping, sync status/freshness) are unit-testable rather than
   buried in hiccup."
  (:require
   [clojure.string :as str]
   [finance-aggregator.web.format :as fmt]))

(defn provider-label
  "Capitalized provider label for an account's source badge, or \"Unknown\"."
  [provider]
  (if provider (str/capitalize (name provider)) "Unknown"))

(defn display-type
  "The type to show for an account: the provider-native type[/subtype] when present, else the
   internal account type, else a dash."
  [{:account/keys [provider-type provider-subtype type]}]
  (cond
    provider-type (if provider-subtype (str provider-type " / " provider-subtype) provider-type)
    type          (name type)
    :else         "—"))

(defn sort-accounts
  "Accounts ordered for display (by external name)."
  [accounts]
  (sort-by :account/external-name accounts))

(def ^:private status-presentation
  "Connection status keyword -> {:label :tone} for the setup status pill. :tone
   drives the pill color class (.status-pill--<tone>)."
  {:synced          {:label "Synced"          :tone "ok"}
   :syncing         {:label "Syncing…"        :tone "active"}
   :backfilling     {:label "Backfilling…"    :tone "active"}
   :pending         {:label "Not synced yet"  :tone "muted"}
   :stale           {:label "Retrying"        :tone "warn"}
   :needs-reconnect {:label "Needs reconnect" :tone "error"}
   :failed          {:label "Failed"          :tone "error"}})

(defn connection-status
  "Presentation {:label :tone} for a connection status keyword (unknown -> muted)."
  [status]
  (or (get status-presentation status)
      {:label (if status (str/capitalize (name status)) "Unknown") :tone "muted"}))

(defn- account-display
  "Flatten an account entity into the structural map the dumb setup view lays out."
  [{:account/keys [external-name mask currency] :as acct}]
  {:name     (or external-name "—")
   :type     (display-type acct)
   :mask     (if mask (str "••••" mask) "—")
   :currency (or currency "—")})

(defn- connection-id-of [account]
  (get-in account [:account/connection :connection/id]))

(defn- resync-url
  "POST URL for resyncing one connection — the id rides as a query param so the
   colon in 'plaid:<item>' needs no path-segment decoding (wrap-params decodes it)."
  [id]
  (str "/setup/resync?connection-id=" (java.net.URLEncoder/encode (str id) "UTF-8")))

(defn- connection-group
  "Presentation map for one connection: its badge, status pill, humanized
   last-synced, error, and the (display-shaped) accounts stamped to it."
  [now accounts-by-conn
   {:connection/keys [id provider institution-name status last-success-at error-message]}]
  {:id               id
   :provider         provider
   :badge-label      (provider-label provider)
   :badge-class      (str "badge-" (if provider (name provider) "unknown"))
   :institution-name (or institution-name (provider-label provider))
   :status-kw        status
   :status           (connection-status status)
   :last-synced      (fmt/relative-time last-success-at now)
   :error-message    error-message
   :resync-url       (resync-url id)
   :accounts         (mapv account-display (sort-accounts (get accounts-by-conn id [])))})

(defn connection-groups
  "Build the setup view's connection groups: one per connection (ordered by
   institution name) carrying its presentation status + last-synced + the accounts
   stamped to it. Accounts not yet linked to a connection (legacy rows predating
   :account/connection, which their next sync populates) come back under
   :unlinked. `now` drives the relative last-synced string."
  [connections accounts now]
  (let [by-conn (group-by connection-id-of accounts)
        known   (set (map :connection/id connections))]
    {:groups   (->> connections
                    (sort-by #(or (:connection/institution-name %)
                                  (name (:connection/provider %))))
                    (mapv #(connection-group now by-conn %)))
     :unlinked (mapv account-display
                     (sort-accounts (remove #(contains? known (connection-id-of %)) accounts)))}))

(defn present
  "The single /setup view-model entry point (presenter): stats + connection groups
   + any unlinked accounts. `now` is injected so the relative last-synced strings
   stay testable."
  [{:keys [stats connections accounts now]}]
  (assoc (connection-groups connections accounts now) :stats stats))

(defn provider-selection
  "Group a provider's available accounts by institution for the link/selection UI,
   flagging the ones already imported (connected). `available` is the provider's
   available-accounts output [{:external-id :name :institution-id :institution-name
   :institution-logo}]; `connected-ids` the set of already-imported external-ids.
   Returns [{:institution-name :institution-logo
             :accounts [{:external-id :name :connected?}]}] ordered by institution,
   accounts by name."
  [available connected-ids]
  (->> available
       (group-by :institution-name)
       (sort-by key)
       (mapv (fn [[institution-name accts]]
               {:institution-name institution-name
                :institution-logo (some :institution-logo accts)
                :accounts (->> accts
                               (sort-by :name)
                               (mapv (fn [{:keys [external-id name]}]
                                       {:external-id external-id
                                        :name name
                                        :connected? (contains? connected-ids external-id)})))}))))

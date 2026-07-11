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

(defn account-label
  "THE display name for an account, everywhere: the user's rename overlay
   (:account/display-name) when set, else the provider's canonical
   :account/external-name, else a dash. The one home for this preference — every
   user-facing account-name read in the web layer (the /setup accounts table, the
   transactions table's account column/sort/funnel, transfer legs, the reconcile
   panel) routes through this, so a rename shows up everywhere at once. The
   provider's own name is never touched; this only decides which of the two to
   show."
  [{:account/keys [display-name external-name]}]
  (or (when-not (str/blank? display-name) display-name)
      (when-not (str/blank? external-name) external-name)
      "—"))

(defn sort-accounts
  "Accounts ordered for display (by their shown label — a rename overlay sorts by
   its new name, not the provider's original)."
  [accounts]
  (sort-by account-label accounts))

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

(defn- accounts-sync-url
  "POST URL for an account-scoped Lunchflow resync — each external-id rides as a
   repeated query param, mirroring resync-url. One id for the per-row Sync button;
   a card's ids for a per-institution Resync."
  [external-ids]
  (->> external-ids
       (map #(str "external-id=" (java.net.URLEncoder/encode (str %) "UTF-8")))
       (str/join "&")
       (str "/setup/sync-account?")))

(defn- account-name-url
  "PUT URL for the account-rename inline-edit commit — the external-id rides as a path
   segment (URL-encoded, so a provider id with odd characters still round-trips; reitit's
   router decodes it back into :path-params on the way in)."
  [external-id]
  (str "/setup/account/" (java.net.URLEncoder/encode (str external-id) "UTF-8") "/name"))

(defn account-display
  "Flatten an account entity into the structural map the dumb setup view lays out: the
   shown label, the current rename override (blank when none) + the provider's own name
   (the fallback shown once the override is cleared, and the muted caption alongside an
   active override) + the rename cell's @put url, the provider-native type/mask/currency,
   and — for a Lunchflow account only — the per-row Sync button's target url and whether
   its connection is mid-sync (`syncing?`, supplied by the caller, which owns the
   connection-level status). Public (not just an internal connection-groups step) — the
   rename SSE handler (web.pages.setup/set-account-name) also calls this to re-present a
   single freshly-written account for its patch, rather than duplicating this shape."
  [syncing? {:account/keys [external-name display-name mask currency external-id provider] :as acct}]
  {:name          (account-label acct)
   :display-name  (or display-name "")
   :external-name (or external-name "—")
   :external-id   external-id
   :name-url      (account-name-url external-id)
   :type          (display-type acct)
   :mask          (if mask (str "••••" mask) "—")
   :currency      (or currency "—")
   :lunchflow?    (= :lunchflow provider)
   :sync-url      (accounts-sync-url [external-id])
   :syncing?      syncing?})

(defn- connection-id-of [account]
  (get-in account [:account/connection :connection/id]))

(defn- resync-url
  "POST URL for resyncing one connection — the id rides as a query param so the
   colon in 'plaid:<item>' needs no path-segment decoding (wrap-params decodes it)."
  [id]
  (str "/setup/resync?connection-id=" (java.net.URLEncoder/encode (str id) "UTF-8")))

(defn- card-resync-url
  "The card-level Resync target. Lunchflow is one shared connection whose accounts
   span institutions, so its cards scope the resync to the card's own accounts
   (the /setup/sync-account :only-account-ids path); every other provider — and an
   account-less card — resyncs the whole connection."
  [provider id external-ids]
  (if (and (= :lunchflow provider) (seq external-ids))
    (accounts-sync-url external-ids)
    (resync-url id)))

(defn- institution-cards
  "Presentation cards for one connection, one per institution among its accounts:
   the provider badge, the connection-level status pill / last-synced / error
   (shared across a split — the accounts sync together), and that institution's
   (display-shaped) accounts + logo. A connection whose accounts span institutions
   (Lunchflow: one shared connection) splits into a card per institution, matching
   Plaid's one-Item-per-institution cards. Accounts without an institution ref —
   and a connection with no accounts yet — fall back to a card named for the
   connection (its institution name, else the provider label)."
  [now accounts-by-conn
   {:connection/keys [id provider institution-name status last-success-at error-message]}]
  (let [fallback-name (or institution-name (provider-label provider))
        base {:id            id
              :provider      provider
              :badge-label   (provider-label provider)
              :badge-class   (str "badge-" (if provider (name provider) "unknown"))
              :status-kw     status
              :status        (connection-status status)
              :last-synced   (fmt/relative-time last-success-at now)
              :error-message error-message}
        card (fn [inst-name raw-accounts]
               (let [sorted (sort-accounts raw-accounts)]
                 (assoc base
                        :institution-name (or inst-name fallback-name)
                        :institution-logo (some #(get-in % [:account/institution :institution/logo]) sorted)
                        :resync-url       (card-resync-url provider id (mapv :account/external-id sorted))
                        :accounts         (mapv (partial account-display (= :syncing status)) sorted))))]
    (if-let [by-inst (not-empty (group-by #(get-in % [:account/institution :institution/name])
                                          (get accounts-by-conn id)))]
      (mapv (fn [[inst-name raw-accounts]] (card inst-name raw-accounts)) by-inst)
      [(card nil [])])))

(defn connection-groups
  "Build the setup view's cards: one per institution per connection (see
   institution-cards), ordered by institution name across every connection — so a
   shared multi-institution connection reads like Plaid's per-institution Items.
   Accounts not yet linked to a connection (legacy rows predating
   :account/connection, which their next sync populates) come back under
   :unlinked. `now` drives the relative last-synced string."
  [connections accounts now]
  (let [by-conn (group-by connection-id-of accounts)
        known   (set (map :connection/id connections))]
    {:groups   (->> connections
                    (mapcat #(institution-cards now by-conn %))
                    (sort-by (juxt :institution-name :id))
                    vec)
     :unlinked (mapv (partial account-display false)
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

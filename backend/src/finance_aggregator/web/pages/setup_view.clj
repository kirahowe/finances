(ns finance-aggregator.web.pages.setup-view
  "Dumb, presentational hiccup for /setup. Data in, hiccup out: every value is
   precomputed by the handler's presenter (web.accounts/present) — this namespace
   never fetches, transforms, or reads ambient state. The handler
   (web.pages.setup) wires the model in.

   Surfaces: the stats strip, the action bar (Sync all + the link affordances),
   and one card per sync connection (status pill, humanized last-synced, a Resync
   action, and the accounts the connection owns)."
  (:require
   [finance-aggregator.web.format :as fmt]
   [finance-aggregator.web.shell :as shell]))

(defn- stat-card [value label]
  [:div.stat-card
   [:div.stat-value value]
   [:div.stat-label label]])

(defn- action-bar
  "Top actions. Sync all is a Datastar @post that live-patches the connections
   list (no page reload); Connect Lunchflow links out; Link Bank Account is the
   Plaid-link island trigger; Add Manual Account stays deferred."
  []
  [:div.button-group
   [:button.button {"data-on:click" "@post('/setup/sync')"} "Sync all"]
   [:button.button.button-secondary {:disabled true :title "Coming soon"} "Add Manual Account"]
   ;; The plaid-link island (loaded on /setup) wires this button to the embedded
   ;; Plaid Link flow; it stays inert (no handler) if the island fails to load.
   [:button.button {:id "plaid-link-btn"} "Link Bank Account"]
   [:a.button.button-secondary {:href "/setup/lunchflow"} "Connect Lunchflow"]])

(defn- accounts-table [accounts]
  [:table.table
   [:thead
    [:tr [:th "Name"] [:th "Type"] [:th "Mask"] [:th "Currency"]]]
   [:tbody
    (for [{:keys [name type mask currency]} accounts]
      [:tr
       [:td name]
       [:td type]
       [:td [:span.numeric mask]]
       [:td currency]])]])

(defn- connection-card
  "One sync connection: institution + provider badge, status pill, last-synced,
   a Resync action (Datastar @post — patches the card live, no page reload), an
   optional error line, and the accounts it owns. Disabled while :syncing."
  [{:keys [badge-label badge-class institution-name status-kw status last-synced
           error-message resync-url accounts]}]
  (let [syncing? (= :syncing status-kw)]
    [:div.card.connection-card
     [:div.connection-head
      [:div.connection-id
       [:span {:class (str "badge " badge-class)} badge-label]
       [:span.connection-name institution-name]]
      [:div.connection-meta
       [:span {:class (str "status-pill status-pill--" (:tone status))} (:label status)]
       [:span.connection-synced "Last synced " last-synced]
       [:button.button.button-secondary.button-small
        (cond-> {"data-on:click" (str "@post('" resync-url "')")}
          syncing? (assoc :disabled true))
        "Resync"]]]
     (when error-message
       [:p.connection-error error-message])
     (if (seq accounts)
       (accounts-table accounts)
       [:p.connection-empty "No accounts on this connection yet."])]))

(defn- unlinked-card
  "Accounts not yet stamped to a connection (legacy rows; their next sync links
   them). Shown so nothing silently disappears."
  [accounts]
  [:div.card.connection-card
   [:div.connection-head
    [:div.connection-id [:span.connection-name "Not yet linked to a sync"]]]
   [:p.connection-empty "These accounts will attach to their connection on the next sync."]
   (accounts-table accounts)])

(defn connections-section
  "The #connections container: a card per connection (+ any unlinked accounts), or
   the empty state. This is the fragment the sync SSE actions morph in place, so it
   carries the stable #connections id and is rendered both on first load and on
   every live patch."
  [{:keys [groups unlinked]}]
  [:div#connections
   (if (and (empty? groups) (empty? unlinked))
     [:div.card
      [:div.empty-state
       [:div.empty-state-title "No connections yet"]
       [:p "Link a bank through Plaid or connect Lunchflow to start importing transactions."]]]
     (list
      (for [group groups] (connection-card group))
      (when (seq unlinked) (unlinked-card unlinked))))])

(defn body
  "The full /setup page body for the given view-model {:stats :groups :unlinked}."
  [{:keys [stats groups] :as model}]
  [:div.container
   (shell/masthead {:active :setup :stats stats})
   [:div.page-head
    [:span.eyebrow "Configuration"]
    [:h2.page-title "Setup"]
    [:p.page-lede
     "Connect institutions, manage accounts, and keep your transactions in sync."]]
   [:div.stats-grid
    (stat-card (fmt/integer (:institutions stats)) "Institutions")
    (stat-card (fmt/integer (:accounts stats)) "Accounts")
    (stat-card (fmt/integer (:transactions stats)) "Transactions")]
   [:div.section-head
    [:h2 "Connections " [:span.section-count (count groups)]]
    (action-bar)]
   (connections-section model)])

;;; Lunchflow account selection -------------------------------------------

(defn- lunchflow-institution [{:keys [institution-name institution-logo accounts]}]
  [:div.provider-institution
   [:h3.provider-institution-name
    (when institution-logo [:img.provider-institution-logo {:src institution-logo :alt ""}])
    institution-name]
   [:ul.provider-account-list
    (for [{:keys [external-id name connected?]} accounts]
      [:li.provider-account-row
       [:label.provider-account-label
        [:input (cond-> {:type "checkbox" :name "account-id" :value external-id}
                  ;; Connected accounts are already imported: shown checked +
                  ;; disabled (a disabled box doesn't re-submit, and fetch-accounts
                  ;; keeps connected ∪ selected, so they stay connected).
                  connected? (assoc :checked true :disabled true))]
        [:span.provider-account-name name]]
       (when connected? [:span.provider-connected-tag "Connected"])])]])

(defn lunchflow-body
  "Full-page Lunchflow account selection: institutions with per-account checkboxes,
   already-connected accounts pre-marked. Model {:stats :groups :error}."
  [{:keys [stats groups error]}]
  [:div.container
   (shell/masthead {:active :setup :stats stats})
   [:div.page-head
    [:span.eyebrow "Configuration"]
    [:h2.page-title "Connect Lunchflow"]
    [:p.page-lede
     "Choose which Lunchflow accounts to import. Already-connected accounts stay connected."]]
   [:div.card
    (cond
      error
      [:div.error-banner [:span error]]

      (empty? groups)
      [:div.empty-state
       [:div.empty-state-title "No Lunchflow accounts found"]
       [:p "Lunchflow returned no accounts. Check that your API key is set and that "
        "accounts are connected in Lunchflow."]]

      :else
      [:form {:method "post" :action "/setup/lunchflow"}
       [:div.provider-connection-scroll
        (map lunchflow-institution groups)]
       [:div.button-group
        [:button.button {:type "submit"} "Connect selected accounts"]
        [:a.button.button-secondary {:href "/setup"} "Cancel"]]])]])

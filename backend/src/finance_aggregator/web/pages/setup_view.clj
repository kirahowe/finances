(ns finance-aggregator.web.pages.setup-view
  "Dumb, presentational hiccup for /setup. Data in, hiccup out: every value is
   precomputed by the handler's presenter (web.accounts/present) — this namespace
   never fetches, transforms, or reads ambient state. The handler
   (web.pages.setup) wires the model in.

   Surfaces: the stats strip, the action bar (Sync all + the link affordances),
   and one card per sync connection (status pill, humanized last-synced, a Resync
   action, and the accounts the connection owns)."
  (:require
   [clojure.string :as str]
   [finance-aggregator.web.format :as fmt]
   [finance-aggregator.web.inline-edit :as inline-edit]
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

;; --- Inline account rename (server-confirmed) -------------------------------
;; The Name cell reuses the transactions description cell's click-to-edit grammar
;; (web.inline-edit): resting text, click swaps in an input sharing the same box, Enter/blur
;; @put's the new name and the server morphs this cell back. Not a grid page (no grid-nav
;; here), so Escape just closes the editor — no gridedit dispatch to hand focus back to.

(def ^:private name-opts
  {:cell-class "account-name-cell" :courier "nameValue" :grid? false
   :button-class "account-name-button" :input-class "account-name-input"
   :add-aria-label nil})

(defn account-name-cell
  "The Name cell's content: the editable button+input pair (resting text = the rename
   override when set, else the provider's own name — same fallback web.accounts/
   account-label already computes into `:name`), plus a transient saved-confirmation ✓ when
   `saved?` (true ONLY on the setup.clj SSE response right after a successful save, never on
   a full page render) and the muted provider-name caption once an override is active, so
   the mapping to the provider's own name stays visible. A blank commit clears the override
   (existing db.accounts/set-display-name! semantics) — the resting text and the optimistic
   client-side fallback both then read the provider name, never a bare dash, so `empty-label`
   carries it rather than the generic inline-edit default."
  [{:keys [external-name display-name name name-url]} & {:keys [saved?]}]
  (list
   (inline-edit/editable-cell
    (assoc name-opts :put-url name-url :empty-label (or external-name "—")
           :input-aria-label (str "Rename " external-name))
    name)
   (when saved? [:span.name-saved-check {:aria-hidden "true"} "✓"])
   (when-not (str/blank? display-name)
     [:span.account-original-name {:title (str "Provider name: " external-name)} external-name])))

(defn account-name-td
  "The `<td>` wrapping one account's Name cell: the `.account-name-cell` class the
   inline-edit CSS/JS keys off, plus a stable id (`account-name-<external-id>`) that's the
   setup.clj rename handler's SSE morph target — the smallest fragment a save needs to
   re-render. `saved?` forwards to account-name-cell (see there)."
  [{:keys [external-id] :as account} & {:keys [saved?]}]
  [:td.account-name-cell {:id (str "account-name-" external-id)}
   (account-name-cell account :saved? saved?)])

(defn- account-sync-button
  "The per-row Lunchflow Sync action (Datastar @post, live-patches the card — same
   pattern as the card-level Resync button). Only Lunchflow accounts get one: Plaid's
   transaction sync is item-level (cursor-based), so there's no per-account scope for
   it. Disabled while the shared Lunchflow connection is already mid-sync."
  [{:keys [lunchflow? sync-url syncing?]}]
  (when lunchflow?
    [:button.button.button-secondary.button-small
     (cond-> {"data-on:click" (str "@post('" sync-url "')")}
       syncing? (assoc :disabled true))
     "Sync"]))

(defn- accounts-table [accounts]
  [:table.table
   [:thead
    [:tr [:th "Name"] [:th "Type"] [:th "Mask"] [:th "Currency"] [:th.actions-th {:aria-hidden "true"}]]]
   [:tbody
    (for [{:keys [type mask currency] :as account} accounts]
      [:tr
       (account-name-td account)
       [:td type]
       [:td [:span.numeric mask]]
       [:td currency]
       [:td.account-sync-cell (account-sync-button account)]])]])

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

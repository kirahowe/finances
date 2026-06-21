(ns finance-aggregator.web.pages.setup
  "Server-rendered /setup page. Phase 2 covers the read-only surfaces: the stats
   bar/cards and the account list. The write actions (add/link/connect accounts,
   CSV import) and the category manager are deferred to later phases and rendered
   disabled so the layout matches the React original."
  (:require
   [finance-aggregator.db.accounts :as db-accounts]
   [finance-aggregator.db.stats :as db-stats]
   [finance-aggregator.web.accounts :as accounts]
   [finance-aggregator.web.format :as fmt]
   [finance-aggregator.web.layout :as layout]
   [finance-aggregator.web.shell :as shell]))

(defn- account-row [{:account/keys [external-name currency provider mask institution] :as acct}]
  [:tr
   [:td [:span {:class (str "badge badge-" (if provider (name provider) "unknown"))}
         (accounts/provider-label provider)]]
   [:td (or (:institution/name institution) "—")]
   [:td external-name]
   [:td (accounts/display-type acct)]
   [:td [:span.numeric (if mask (str "••••" mask) "—")]]
   [:td currency]
   [:td (when (= provider :manual)
          [:button.button.button-secondary.button-small
           {:disabled true :title "CSV import — coming in a later migration phase"}
           "Import CSV"])]])

(defn- accounts-section [accounts]
  [:div.card
   [:div.section-head
    [:h2 "Accounts " [:span.section-count (count accounts)]]
    ;; Connect actions are write operations, deferred; disabled to keep the layout.
    [:div.button-group
     [:button.button.button-secondary {:disabled true} "Add Manual Account"]
     [:button.button {:disabled true} "Link Bank Account"]
     [:button.button.button-secondary {:disabled true} "Connect Lunchflow"]]]
   (if (empty? accounts)
     [:div.empty-state
      [:div.empty-state-title "No accounts yet"]
      [:p "Link a bank through Plaid, connect Lunchflow, or add a manual account "
       "to start importing transactions."]]
     [:table.table
      [:thead
       [:tr [:th "Source"] [:th "Institution"] [:th "Name"] [:th "Type"]
        [:th "Mask"] [:th "Currency"] [:th "Actions"]]]
      [:tbody (map account-row (accounts/sort-accounts accounts))]])])

(defn- stat-card [value label]
  [:div.stat-card
   [:div.stat-value value]
   [:div.stat-label label]])

(defn page
  "Factory: GET /setup handler. Renders the stats bar/cards + account list."
  [{:keys [db-conn]}]
  (fn [_req]
    (let [stats (db-stats/entity-counts db-conn)
          accounts (db-accounts/list-with-institution db-conn)]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body
       (layout/document
        {:title "Setup · Finance Aggregator"}
        [:div.container
         (shell/masthead {:active :setup :stats stats})
         [:div.page-head
          [:span.eyebrow "Configuration"]
          [:h2.page-title "Setup"]
          [:p.page-lede
           "Connect institutions, manage accounts, and curate the category system "
           "your transactions are sorted into."]]
         [:div.stats-grid
          (stat-card (fmt/integer (:institutions stats)) "Institutions")
          (stat-card (fmt/integer (:accounts stats)) "Accounts")
          (stat-card (fmt/integer (:transactions stats)) "Transactions")]
         (accounts-section accounts)])})))

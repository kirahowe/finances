(ns finance-aggregator.web.pages.setup
  "Server-rendered /setup page. Currently shows the read-only surfaces: the stats
   bar/cards and the account list. The write actions (add/link/connect accounts,
   CSV import) and the category manager aren't wired yet and are rendered disabled
   pending future work."
  (:require
   [finance-aggregator.db.accounts :as db-accounts]
   [finance-aggregator.db.stats :as db-stats]
   [finance-aggregator.lib.log :as log]
   [finance-aggregator.resync :as resync]
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
    ;; Sync now is the one live action; the connect/link actions are deferred.
    [:div.button-group
     [:form {:method "post" :action "/setup/sync"}
      [:button.button {:type "submit"} "Sync now"]]
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

(defn sync-now
  "Factory: POST /setup/sync handler. Fires one resilient-sync pass over all
   connections in the background (statuses persist; refreshing /setup shows
   progress) and redirects back. The engine is internally isolated per
   connection, so the future never needs the request to wait on it; a failure
   outside that isolation (e.g. registry reconciliation) is logged, not lost."
  [deps]
  (fn [_req]
    (future
      (try
        (resync/resync-all! deps)
        (catch Throwable t
          (log/error "Background resync pass failed" {:error (.getMessage t)}))))
    {:status 303 :headers {"Location" "/setup"}}))

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

(ns finance-aggregator.web.pages.rows-spike
  "SPIKE — server-authoritative filtering over SSE, and the first Replicant-free page
   (renders entirely through the hiccup2 seam, web.render). Side-by-side latency test
   against the client-side `/` page; wired at `/spike` (+ `/spike/rows`, the fragment).

   The whole client/server contract:
     - Persistent state ($scope, $month) survives reload via the URL; the server seeds
       it on load. Datastar ships every (non _-prefixed) signal with each action, so
       there is no URL to assemble and no per-row state to track.
     - The scope toggle sets $scope and @get's the fragment; the reviewed checkbox @put's
       its new value in the path. The server filters server-side and re-renders
       `<tbody id=tx-tbody>`, which Datastar morphs in by id.

   No per-row signals, no baked `data-show` expressions, no JS-string escaping (data lives
   in HTML, never in client expressions), no `h/a` coercion (hiccup2 takes string/colon
   attribute keys directly). GET also sidesteps the body-consumption gotcha — no body.

   Delete after the latency test (see doc/plans/datastar-server-authoritative-rewrite.md)."
  (:require
   [clojure.string :as str]
   [finance-aggregator.db.stats :as db-stats]
   [finance-aggregator.db.transactions :as db-transactions]
   [finance-aggregator.web.format :as fmt]
   [finance-aggregator.web.month :as month]
   [finance-aggregator.web.render :as r]
   [finance-aggregator.web.shell :as shell]
   [starfederation.datastar.clojure.adapter.http-kit :as hk]
   [starfederation.datastar.clojure.api :as d*]))

;; --- The whole filter: server-side, one line --------------------------------

(defn- needs-review?
  "Unreviewed when the (effective, split-rolled-up) reviewed flag isn't true — exactly
   what db-transactions/month-counts keys :unreviewed off."
  [tx]
  (not (true? (:transaction/reviewed tx))))

(defn- apply-scope [scope txs]
  (if (= scope "needs-review") (filter needs-review? txs) txs))

(defn- normalize-scope [s]
  (if (= "needs-review" s) "needs-review" "all"))

;; --- Document shell (hiccup2; replaces the Replicant layout for this page) ---

(defn- document
  "A full HTML document via the hiccup2 seam. `signals` is the initial data-signals map."
  [{:keys [title signals]} & body]
  (r/render-page
   [:html {:lang "en"}
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title (or title "Finance Aggregator")]
     [:link {:rel "preconnect" :href "https://fonts.googleapis.com"}]
     [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin "anonymous"}]
     [:link {:rel "stylesheet"
             :href (str "https://fonts.googleapis.com/css2?"
                        "family=Fraunces:ital,opsz,wght@0,9..144,400;0,9..144,500;0,9..144,600;1,9..144,400"
                        "&family=Hanken+Grotesk:wght@400;500;600;700"
                        "&family=IBM+Plex+Mono:wght@400;500;600&display=swap")}]
     [:link {:rel "stylesheet" :href "/css/app.css"}]
     [:script {:type "module" :src "/js/datastar.js"}]]
    (into [:body {"data-signals" (r/signals signals)}] body)]))

;; --- Rendering (clean-room minimal table; splits collapse to one row) --------

(def ^:private columns
  [["Date" 120] ["Account" 150] ["Institution" 160] ["Payee" 200]
   ["Description" 240] ["Amount" 120] ["Category" 180] ["Reviewed" 96]])

(defn- amount-span [amt]
  [:span {:class (str "numeric " (if (pos? amt) "positive" "negative"))} (fmt/amount amt)])

(defn- reviewed-checkbox
  "Server-confirmed reviewed toggle: `change` @put's the new state in the path (`el.checked`
   — read straight off the event target, no per-row signal); the server persists and
   re-renders the (filtered) tbody, and the morph reconciles the checkbox to the truth.
   Split rows have a rolled-up flag with no single checkbox, so they render disabled."
  [tx-id reviewed? split?]
  [:input
   (if split?
     {:type "checkbox" :class "reviewed-checkbox" :checked (boolean reviewed?) :disabled true}
     {:type "checkbox" :class "reviewed-checkbox" :checked (boolean reviewed?)
      "data-on:change" (str "@put('/spike/tx/" tx-id "/reviewed/' + el.checked)")})])

(defn- row [{:transaction/keys [posted-date account payee effective-description
                                amount category reviewed splits] :as tx}]
  [:tr {:role "row"}
   [:td [:span.numeric (fmt/date posted-date)]]
   [:td (or (:account/external-name account) "—")]
   [:td (or (get-in account [:account/institution :institution/name]) "—")]
   [:td payee]
   [:td.description-cell (if (str/blank? effective-description) "—" effective-description)]
   [:td.amount-cell (amount-span amount)]
   [:td.category-cell (or (:category/name category) "Uncategorized")]
   [:td.reviewed-cell (reviewed-checkbox (:db/id tx) reviewed (boolean (seq splits)))]])

(defn- tbody
  "The morph target. Stable id so the SSE patch morphs it in by id (default mode)."
  [scope txs]
  (into [:tbody {:id "tx-tbody"}] (map row (apply-scope scope txs))))

(defn- table [scope txs]
  [:div.transactions-table-scroll {:tabindex "0"}
   [:table.table {:role "grid"}
    [:colgroup (for [[_ w] columns] [:col {:style (str "width:" w "px")}])]
    [:thead [:tr (for [[label] columns] [:th label])]]
    (tbody scope txs)]])

(defn- scope-toggle
  "Two buttons. Each sets the $scope signal (instant active-state via data-class) and
   @get's the fragment — the only persistent client state on the page."
  [{:keys [unreviewed total]}]
  [:div.scope-toggle {:role "group" :aria-label "Review scope"}
   [:button.scope-toggle-btn
    {"type" "button"
     "data-on:click" "$scope = 'needs-review'; @get('/spike/rows')"
     "data-class" "{'is-active': $scope === 'needs-review'}"}
    "Needs review" [:span.filter-count unreviewed]]
   [:button.scope-toggle-btn
    {"type" "button"
     "data-on:click" "$scope = 'all'; @get('/spike/rows')"
     "data-class" "{'is-active': $scope === 'all'}"}
    "All" [:span.filter-count total]]])

;; --- Routes -----------------------------------------------------------------

(defn page
  "GET /spike — the full page. Seeds $scope from ?scope= so a reload / shared link
   restores the view (the URL is the state)."
  [{:keys [db-conn]}]
  (fn [req]
    (let [m (month/parse (get-in req [:query-params "month"]))
          month-str (month/serialize m)
          txs (db-transactions/list-for-month db-conn month-str)
          counts (db-transactions/month-counts txs)
          stats (db-stats/entity-counts db-conn)
          scope (normalize-scope (get-in req [:query-params "scope"]))]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body
       (document
        {:title "Spike · server-side filter"
         :signals {:scope scope :month month-str}}
        [:div.container.container--workspace
         (shell/masthead {:active :transactions :stats stats})
         [:div.transactions-layout
          [:div.card
           [:div.toolbar
            [:div.toolbar-controls
             [:span.month-navigator-display (month/display m)]
             [:span.toolbar-divider {:aria-hidden "true"}]
             (scope-toggle counts)
             [:span.toolbar-divider {:aria-hidden "true"}]
             [:span.spike-note "server-side filter spike (hiccup2, Replicant-free)"]]]
           (table scope txs)]]])})))

(defn- patch-filtered-tbody
  "The shared write path for every spike action: read $scope + $month off the request
   signals (query param for GET, body for PUT — both via read-signals), re-render the
   filtered tbody, and morph it in by id. One SSE event, then close."
  [db-conn req]
  (let [signals (r/read-signals req)
        month-str (month/serialize (month/parse (:month signals)))
        scope (normalize-scope (:scope signals))
        txs (db-transactions/list-for-month db-conn month-str)]
    (hk/->sse-response
     req
     {hk/on-open
      (fn [sse]
        (d*/patch-elements! sse (r/render (tbody scope txs)))
        (d*/close-sse! sse))})))

(defn rows
  "GET /spike/rows — re-render the filtered tbody after a scope change."
  [{:keys [db-conn]}]
  (fn [req] (patch-filtered-tbody db-conn req)))

(defn toggle-reviewed
  "PUT /spike/tx/:id/reviewed/:v — persist the row's reviewed flag (server-authoritative),
   then re-render the filtered tbody: in Needs-review scope the just-reviewed row simply
   drops out. The new value rides in the path, so no per-row signal exists."
  [{:keys [db-conn]}]
  (fn [req]
    (db-transactions/set-reviewed! db-conn
                                   (-> req :path-params :id parse-long)
                                   (= "true" (-> req :path-params :v)))
    (patch-filtered-tbody db-conn req)))

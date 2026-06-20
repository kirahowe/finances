(ns finance-aggregator.web.pages.transactions2
  "Server-authoritative transactions workspace (Phase R2), served at /v2 while the old
   client-heavy / page stays up for comparison (both deleted/renamed at R4).

   Checkpoint 1 (this file): read-only table + toolbar, all view changes server-driven.
   Every control (search / scope / chips / sort / paginate) sets its signal and
   @get('/v2/rows'); the server runs the pure view engine (web.view) and morphs
   `#tx-tbody` + `#pagination-bar` by id, patching $page back to the clamped value. No
   per-row signals, no baked data-show, no client-side filter/sort/paginate islands.

   Deferred to later checkpoints: header-filter funnels (cp1b — view.clj already supports
   account/institution/category, just needs the popover UI), inline edits + lingering +
   undo (cp2), column visibility/width + URL write-back (R3). The view-state seeds from
   the URL on load; until R3's URL reflector lands, interacting then reloading resets it."
  (:require
   [clojure.string :as str]
   [finance-aggregator.db.categories :as db-categories]
   [finance-aggregator.db.stats :as db-stats]
   [finance-aggregator.db.transactions :as db-transactions]
   [finance-aggregator.web.format :as fmt]
   [finance-aggregator.web.layout2 :as layout]
   [finance-aggregator.web.month :as month]
   [finance-aggregator.web.render :as r]
   [finance-aggregator.web.shell :as shell]
   [finance-aggregator.web.view :as view]
   [starfederation.datastar.clojure.adapter.http-kit :as hk]
   [starfederation.datastar.clojure.api :as d*]))

;; ---------------------------------------------------------------------------
;; Columns
;; ---------------------------------------------------------------------------

(def ^:private columns
  [{:id "date"        :label "Date"        :w 120 :sortable true}
   {:id "account"     :label "Account"     :w 150 :sortable true}
   {:id "institution" :label "Institution" :w 160 :sortable true}
   {:id "payee"       :label "Payee"       :w 200 :sortable true}
   {:id "description" :label "Description" :w 240 :sortable false}
   {:id "amount"      :label "Amount"      :w 120 :sortable true}
   {:id "category"    :label "Category"    :w 180 :sortable true}
   {:id "reviewed"    :label "Reviewed"    :w 96  :sortable false}])

(def ^:private page-size-options [25 50 100 250])

;; ---------------------------------------------------------------------------
;; View-state <-> signals/query mapping
;; ---------------------------------------------------------------------------

(defn- view-state
  "Build the web.view view-state from a generic accessor `g` (the signals map's keyword
   keys, or a query-param getter). Funnels are cp1b; omitting them = no category/account
   filter."
  [{:keys [search scope hide-transfers uncat sort-col sort-dir page page-size]}]
  {:search        (or search "")
   :scope         (if (= "needs-review" scope) :needs-review :all)
   :hide-transfers (boolean hide-transfers)
   :uncat         (boolean uncat)
   :sort          (when (not (str/blank? sort-col))
                    {:col (keyword sort-col) :dir (if (= "desc" sort-dir) :desc :asc)})
   :page          (or page 0)
   :page-size     (or page-size 25)})

(defn- signals->view-state [s]
  (view-state {:search (:search s) :scope (:scope s) :hide-transfers (:hideTransfers s)
               :uncat (:uncat s) :sort-col (:sortCol s) :sort-dir (:sortDir s)
               :page (:page s) :page-size (:pageSize s)}))

(defn- query->view-state [qp]
  (view-state {:search (get qp "q") :scope (get qp "scope")
               :hide-transfers (= "1" (get qp "ht")) :uncat (= "1" (get qp "uncat"))
               :sort-col (get qp "sortCol") :sort-dir (get qp "sortDir")
               :page (some-> (get qp "page") parse-long)
               :page-size (some-> (get qp "pageSize") parse-long)}))

(defn- vs->signals
  "Initial client signals derived from a view-state. page/page-size are taken from the
   clamped view result so the signal matches what's rendered."
  [vs month-str result]
  {:search        (:search vs)
   :scope         (if (= :needs-review (:scope vs)) "needs-review" "all")
   :hideTransfers (:hide-transfers vs)
   :uncat         (:uncat vs)
   :sortCol       (if-let [s (:sort vs)] (name (:col s)) "")
   :sortDir       (if-let [s (:sort vs)] (name (:dir s)) "asc")
   :page          (:page result)
   :pageSize      (:page-size result)
   :month         month-str})

;; ---------------------------------------------------------------------------
;; Rows (read-only in cp1; editors arrive in cp2)
;; ---------------------------------------------------------------------------

(defn- amount-span
  "Signed amount with sign class. `split?` matches the split rule (0 reads positive) vs
   the normal-row rule (0 reads negative)."
  [amt split?]
  (let [negative? (if split? (neg? amt) (not (pos? amt)))]
    [:span {:class (str "numeric " (if negative? "negative" "positive"))} (fmt/amount amt)]))

(defn- ro-checkbox [reviewed?]
  [:input {:type "checkbox" :class "reviewed-checkbox" :checked (boolean reviewed?) :disabled true}])

(defn- normal-row [{:transaction/keys [posted-date account payee effective-description
                                       amount category reviewed]}]
  [:tr {:role "row"}
   [:td [:span.numeric (fmt/date posted-date)]]
   [:td (or (:account/external-name account) "—")]
   [:td (or (get-in account [:account/institution :institution/name]) "—")]
   [:td payee]
   [:td.description-cell (if (str/blank? effective-description) "—" effective-description)]
   [:td.amount-cell (amount-span amount false)]
   [:td.category-cell (or (:category/name category) "Uncategorized")]
   [:td.reviewed-cell (ro-checkbox reviewed)]])

(defn- split-parent-row [{:transaction/keys [posted-date account payee effective-description]}]
  [:tr {:role "row" :class "is-split-parent"}
   [:td [:span.numeric (fmt/date posted-date)]]
   [:td (or (:account/external-name account) "—")]
   [:td (or (get-in account [:account/institution :institution/name]) "—")]
   [:td payee]
   [:td.description-cell (if (str/blank? effective-description) "—" effective-description)]
   [:td.amount-cell] [:td.category-cell] [:td.reviewed-cell]])

(defn- split-child-row [{:split/keys [amount memo category reviewed]}]
  [:tr {:role "row" :class "split-child-row"}
   [:td] [:td] [:td] [:td]
   [:td.description-cell (if (str/blank? memo) "—" memo)]
   [:td.amount-cell (amount-span amount true)]
   [:td.category-cell (or (:category/name category) "Uncategorized")]
   [:td.reviewed-cell (ro-checkbox reviewed)]])

(defn- tx-rows [tx]
  (if-let [parts (seq (:transaction/splits tx))]
    (into [(split-parent-row tx)] (map split-child-row (sort-by :split/order parts)))
    [(normal-row tx)]))

(defn- tbody [rows]
  (into [:tbody {:id "tx-tbody"}] (mapcat tx-rows rows)))

;; ---------------------------------------------------------------------------
;; Header (sortable columns; cycle asc → desc → cleared, server-side)
;; ---------------------------------------------------------------------------

(defn- sort-click-js [col]
  ;; Static literal (the column name is known at render time, never user/server data).
  (str "$sortCol === '" col "'"
       " ? ($sortDir === 'asc' ? ($sortDir = 'desc') : ($sortCol = '', $sortDir = 'asc'))"
       " : ($sortCol = '" col "', $sortDir = 'asc');"
       " $page = 0; @get('/v2/rows')"))

(defn- th [{:keys [id label sortable]}]
  (if sortable
    [:th {"class" "th-sortable" "data-col-id" id
          "data-on:click" (sort-click-js id)
          "data-attr" (str "{'aria-sort': $sortCol === '" id "'"
                           " ? ($sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}")}
     [:span.th-content
      [:span.th-label label]
      [:span.th-sort-indicator
       {"data-text" (str "$sortCol === '" id "' ? ($sortDir === 'asc' ? ' ↑' : ' ↓') : ''")}]]]
    [:th {:data-col-id id} [:span.th-content [:span.th-label label]]]))

(defn- table [rows]
  [:div.transactions-table-scroll {:tabindex "0"}
   [:table.table {:role "grid"}
    [:colgroup (for [{:keys [w]} columns] [:col {:style (str "width:" w "px")}])]
    [:thead [:tr (map th columns)]]
    (tbody rows)]])

;; ---------------------------------------------------------------------------
;; Toolbar
;; ---------------------------------------------------------------------------

(defn- month-navigator [m]
  ;; Full navigation (anchor). URL-state write-back (preserving filters across month
  ;; change) lands in R3; until then a month change resets the view.
  [:div.month-navigator
   [:div.month-navigator-controls
    [:a.button.button-secondary.month-nav-button
     {:href (str "/v2?month=" (month/serialize (month/prev-month m))) :title "Previous month"} "‹"]
    [:span.month-navigator-display (month/display m)]
    [:a.button.button-secondary.month-nav-button
     {:href (str "/v2?month=" (month/serialize (month/next-month m))) :title "Next month"} "›"]]])

(defn- search-box []
  [:div.table-search
   [:input.table-search-input
    {:type "search" :placeholder "Search payee, description…" :aria-label "Search transactions"
     "data-bind" "search"
     "data-on:input__debounce.300ms" "$page = 0; @get('/v2/rows')"}]])

(defn- scope-toggle [{:keys [unreviewed total]}]
  [:div.scope-toggle {:role "group" :aria-label "Review scope"}
   [:button.scope-toggle-btn
    {"type" "button" "data-on:click" "$scope = 'needs-review'; $page = 0; @get('/v2/rows')"
     "data-class" "{'is-active': $scope === 'needs-review'}"}
    "Needs review" [:span#count-unreviewed.filter-count unreviewed]]
   [:button.scope-toggle-btn
    {"type" "button" "data-on:click" "$scope = 'all'; $page = 0; @get('/v2/rows')"
     "data-class" "{'is-active': $scope === 'all'}"}
    "All" [:span#count-total.filter-count total]]])

(defn- count-chip [label signal span-id count]
  [:button.count-chip
   {"type" "button"
    "data-on:click" (str "$" signal " = !$" signal "; $page = 0; @get('/v2/rows')")
    "data-class" (str "{'is-active': $" signal "}")}
   label [:span.filter-count {:id span-id} count]])

(defn- toolbar [m counts]
  [:div.toolbar
   [:div.toolbar-controls
    (month-navigator m)
    [:span.toolbar-divider {:aria-hidden "true"}]
    (search-box)
    (scope-toggle counts)
    (count-chip "Uncategorized" "uncat" "count-uncategorized" (:uncategorized counts))
    (count-chip "Hide transfers" "hideTransfers" "count-transfers" (:transfers-hidden counts))]])

;; ---------------------------------------------------------------------------
;; Pagination (fully server-rendered each response → disabled states stay correct)
;; ---------------------------------------------------------------------------

(defn- nav-btn [{:keys [title js disabled?]} glyph]
  [:button (cond-> {:class "button button-secondary pagination-nav-button"
                    :type "button" :title title :aria-label title
                    "data-on:click" js}
             disabled? (assoc :disabled true))
   glyph])

(defn- pagination-bar [{:keys [page page-count page-size]}]
  (let [first? (zero? page)
        last?  (>= page (dec page-count))]
    [:div.pagination {:id "pagination-bar"}
     [:div.pagination-size-controls
      (for [n page-size-options]
        [:button {:type "button"
                  :class (str "button pagination-size-button "
                              (if (= n page-size) "button-primary" "button-secondary"))
                  "data-on:click" (str "$pageSize = " n "; $page = 0; @get('/v2/rows')")}
         (str n)])]
     [:div.pagination-navigation
      (nav-btn {:title "First page"    :disabled? first? :js "$page = 0; @get('/v2/rows')"} "«")
      (nav-btn {:title "Previous page" :disabled? first? :js "$page = Math.max(0, $page - 1); @get('/v2/rows')"} "‹")
      [:span.pagination-status (str "Page " (inc page) " of " page-count)]
      (nav-btn {:title "Next page" :disabled? last? :js "$page = $page + 1; @get('/v2/rows')"} "›")
      (nav-btn {:title "Last page" :disabled? last? :js (str "$page = " (dec page-count) "; @get('/v2/rows')")} "»")]]))

;; ---------------------------------------------------------------------------
;; Routes
;; ---------------------------------------------------------------------------

(defn- empty-state []
  [:div.empty-state
   [:div.empty-state-title "No transactions this month"]
   [:p "Use the month controls to browse another period, or import from Setup."]])

(defn page
  "GET /v2 — full page. Seeds the view-state from the URL, renders the first page."
  [{:keys [db-conn]}]
  (fn [req]
    (let [m (month/parse (get-in req [:query-params "month"]))
          month-str (month/serialize m)
          txs (db-transactions/list-for-month db-conn month-str)
          counts (db-transactions/month-counts txs)
          stats (db-stats/entity-counts db-conn)
          _categories (db-categories/list-all db-conn) ; (combobox model — used in cp2)
          vs (query->view-state (:query-params req))
          result (view/view txs vs)]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body
       (layout/document
        {:title "Finance Aggregator"
         :signals (vs->signals vs month-str result)}
        [:div.container.container--workspace
         (shell/masthead {:active :transactions :stats stats})
         [:div.transactions-layout
          [:div.card
           (toolbar m counts)
           (if (empty? txs)
             (empty-state)
             (list (table (:rows result)) (pagination-bar result)))]]])})))

(defn rows
  "GET /v2/rows — re-run the view for the current signals and morph the tbody +
   pagination bar, patching $page back to the clamped value."
  [{:keys [db-conn]}]
  (fn [req]
    (let [signals (r/read-signals req)
          month-str (month/serialize (month/parse (:month signals)))
          txs (db-transactions/list-for-month db-conn month-str)
          result (view/view txs (signals->view-state signals))]
      (hk/->sse-response
       req
       {hk/on-open
        (fn [sse]
          (d*/patch-elements! sse (r/render (tbody (:rows result))))
          (d*/patch-elements! sse (r/render (pagination-bar result)))
          (d*/patch-signals! sse (r/signals {:page (:page result)}))
          (d*/close-sse! sse))}))))

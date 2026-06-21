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
   [finance-aggregator.auth :as auth]
   [finance-aggregator.db.categories :as db-categories]
   [finance-aggregator.db.stats :as db-stats]
   [finance-aggregator.db.transactions :as db-transactions]
   [finance-aggregator.web.commands :as commands]
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
  [{:id "date"        :label "Date"        :w 120 :sortable true  :min 80  :protected true}
   {:id "account"     :label "Account"     :w 150 :sortable true  :min 90}
   {:id "institution" :label "Institution" :w 160 :sortable true  :min 90}
   {:id "payee"       :label "Payee"       :w 200 :sortable true  :min 100}
   {:id "description" :label "Description" :w 240 :sortable false :min 200}
   {:id "amount"      :label "Amount"      :w 120 :sortable true  :min 90  :protected true}
   {:id "category"    :label "Category"    :w 180 :sortable true  :min 200 :protected true}
   {:id "reviewed"    :label "Reviewed"    :w 96  :sortable false :min 80  :protected true}])

;; Columns the resize island gives a drag handle (reviewed is fixed-width).
(def ^:private resizable-cols #{"date" "account" "institution" "payee" "description" "amount" "category"})

(def ^:private page-size-options [25 50 100 250])

;; Column visibility is persistent-but-client-applied (Lane A, row 2): the `cols.<id>`
;; signals toggle `hide-<id>` classes on the table (pure CSS, no round-trip), and persist to
;; the URL. Every column is hideable — the interactive ones (description/category/reviewed)
;; carry data-cell; grid-nav rebuilds its model from the visible cells, so hiding one just
;; drops it from keyboard navigation too.
(def ^:private hideable-columns
  [["date" "Date"] ["account" "Account"] ["institution" "Institution"]
   ["payee" "Payee"] ["description" "Description"] ["amount" "Amount"]
   ["category" "Category"] ["reviewed" "Reviewed"]])

(defn- cols-hide-class []
  (str "{" (str/join ", " (map (fn [[id _]] (str "'hide-" id "': !$cols." id)) hideable-columns)) "}"))

(declare undo-redo-controls column-picker) ; defined later, used by the toolbar/table

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

(defn- ->id-set [strs]
  (into #{} (keep #(some-> % str not-empty parse-long) strs)))

(defn- csv-param [qp k]
  (let [v (get qp k)] (if (str/blank? v) [] (str/split v #","))))

(defn- with-funnels
  "Add the header-funnel selections (account/institution/category id sets) to a view-state."
  [vs accounts institutions categories]
  (assoc vs
         :accounts (->id-set accounts)
         :institutions (->id-set institutions)
         :categories (->id-set categories)))

(defn- signals->view-state [s]
  (with-funnels
    (view-state {:search (:search s) :scope (:scope s) :hide-transfers (:hideTransfers s)
                 :uncat (:uncat s) :sort-col (:sortCol s) :sort-dir (:sortDir s)
                 :page (:page s) :page-size (:pageSize s)})
    (get-in s [:filter :account]) (get-in s [:filter :institution]) (get-in s [:filter :category])))

(defn- query->view-state [qp]
  (with-funnels
    (view-state {:search (get qp "q") :scope (get qp "scope")
                 :hide-transfers (= "1" (get qp "ht")) :uncat (= "1" (get qp "uncat"))
                 :sort-col (get qp "sortCol") :sort-dir (get qp "sortDir")
                 :page (some-> (get qp "page") parse-long)
                 :page-size (some-> (get qp "pageSize") parse-long)})
    (csv-param qp "fa") (csv-param qp "fi") (csv-param qp "fc")))

(defn- parse-cols
  "The `cols.<id>` visibility signal map (true = visible) from the URL's `hidecols` csv."
  [qp]
  (let [hidden (set (csv-param qp "hidecols"))]
    (into {} (map (fn [[id _]] [(keyword id) (not (contains? hidden id))]) hideable-columns))))

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
   :month         month-str
   :editValue     ""
   :catValue      ""})

(defn- client-signals
  "Full initial signal set: persistent view-state + column visibility + header-funnel
   selections (persistent) + ephemeral UI signals (underscore-prefixed → never sent)."
  [vs month-str result qp]
  (assoc (vs->signals vs month-str result)
         :cols (parse-cols qp)
         :filter {:account (csv-param qp "fa")
                  :institution (csv-param qp "fi")
                  :category (csv-param qp "fc")}
         :_colsOpen false
         :_openFunnel ""
         :_funnelX 0
         :_funnelY 0
         :_funnelQuery ""))

;; ---------------------------------------------------------------------------
;; Rows (read-only in cp1; editors arrive in cp2)
;; ---------------------------------------------------------------------------

(defn- amount-span
  "Signed amount with sign class. `split?` matches the split rule (0 reads positive) vs
   the normal-row rule (0 reads negative)."
  [amt split?]
  (let [negative? (if split? (neg? amt) (not (pos? amt)))]
    [:span {:class (str "numeric " (if negative? "negative" "positive"))} (fmt/amount amt)]))

(defn- reviewed-checkbox
  "Editable rows: a server-confirmed toggle (`change` @put's the new state in the path —
   `el.checked`, no per-row signal). Split children have a rolled-up flag and stay read-only
   (split-reviewed editing is later)."
  [tx-id reviewed? editable?]
  (if editable?
    [:input {:type "checkbox" :class "reviewed-checkbox" :checked (boolean reviewed?)
             "data-on:change" (str "@put('/v2/tx/" tx-id "/reviewed/' + el.checked)")}]
    [:input {:type "checkbox" :class "reviewed-checkbox" :checked (boolean reviewed?) :disabled true}]))

(defn- row-class [base stale?]
  (str/trim (str base (when stale? " is-stale"))))

;; --- Inline description edit (server-confirmed) ----------------------------
;; Click the button → class-swap to an input (reusing the carried-over
;; .description-cell.editing CSS). Enter/blur optimistically set the button text, copy the
;; input value into the single $editValue courier signal, and @put it; the server applies a
;; command and morphs the row back (reconciling blank → imported description). Escape reverts
;; the input to its server value. No per-row signals — the input holds its own text.

(def ^:private desc-open-js
  (str "el.closest('.description-cell').classList.add('editing');"
       " el.nextElementSibling.focus(); el.nextElementSibling.select()"))

(defn- desc-commit-js [tx-id]
  (str "el.previousElementSibling.textContent = el.value || '—',"
       " $editValue = el.value, @put('/v2/tx/" tx-id "/description'),"
       " el.closest('.description-cell').classList.remove('editing')"))

(defn- desc-keydown-js [tx-id]
  (str "evt.key === 'Enter' && (" (desc-commit-js tx-id) "); "
       "evt.key === 'Escape' && (el.value = el.defaultValue,"
       " el.closest('.description-cell').classList.remove('editing'))"))

(defn- desc-blur-js [tx-id]
  ;; A genuine click-away commits; Enter/Escape already removed `editing`, so their trailing
  ;; blur is a no-op (guards the double-commit).
  (str "el.closest('.description-cell').classList.contains('editing') && (" (desc-commit-js tx-id) ")"))

(defn- editable-description [tx-id text]
  (list
   [:button.description-button
    {:type "button" :tabindex "-1" "data-on:click" desc-open-js
     :aria-label (when (str/blank? text) "Add description")}
    (if (str/blank? text) "—" text)]
   [:input.description-input
    {:type "text" :value text :aria-label "Edit description"
     "data-on:keydown" (desc-keydown-js tx-id)
     "data-on:blur" (desc-blur-js tx-id)}]))

;; --- Inline category edit (Zag combobox island + server-confirmed command) -------------
;; The category cell keeps a .category-button.combo-cell (the combobox island opens it on
;; click) plus a hidden input courier: the island writes the chosen id there and dispatches
;; change, which copies it into the single $catValue signal and @put's. The server records an
;; :update-category command (capturing the prior category id for undo) and morphs the row +
;; counts (a category change moves the uncategorized count). The category model travels in a
;; hidden #category-options DOM list (the island reconstructs Category[] from data-attrs).

(defn- editable-category [tx-id category]
  (list
   [:button.category-button.combo-cell
    {:type "button" :tabindex "-1" :id (str "cat-view-tx" tx-id) :aria-haspopup "listbox"}
    (or (:category/name category) "Uncategorized")]
   [:input {:type "hidden"
            "data-on:change" (str "$catValue = el.value; @put('/v2/tx/" tx-id "/category')")}]))

(defn- category-options
  "Hidden source-of-truth list the combobox island reconstructs its Category[] from
   (id/parent/sort-order as data-attrs — Replicant escaped JSON in a <script>; the DOM-carried
   model is renderer-agnostic and we keep it)."
  [categories]
  [:ul#category-options {:hidden true :aria-hidden "true"}
   (for [c categories]
     [:li (cond-> {:data-id (:db/id c)}
            (:category/type c)                   (assoc :data-type (name (:category/type c)))
            (get-in c [:category/parent :db/id]) (assoc :data-parent (get-in c [:category/parent :db/id]))
            (:category/sort-order c)             (assoc :data-sort (:category/sort-order c)))
      (:category/name c)])])

(defn- grid-cell
  "Attrs marking a <td> as a keyboard-navigable grid cell (txId:tx:col, matching the ported
   gridNavigation reducer). Normal rows only; split cells aren't navigable yet."
  [tx-id col]
  {:data-cell (str tx-id ":tx:" col) :role "gridcell" :tabindex "-1"})

(defn- normal-row [stale? {:transaction/keys [posted-date account payee effective-description
                                              amount category reviewed] :as tx}]
  [:tr {:role "row" :class (row-class "" stale?)}
   [:td [:span.numeric (fmt/date posted-date)]]
   [:td (or (:account/external-name account) "—")]
   [:td (or (get-in account [:account/institution :institution/name]) "—")]
   [:td payee]
   [:td.description-cell (grid-cell (:db/id tx) "description") (editable-description (:db/id tx) effective-description)]
   [:td.amount-cell (amount-span amount false)]
   [:td.category-cell (grid-cell (:db/id tx) "category") (editable-category (:db/id tx) category)]
   [:td.reviewed-cell (grid-cell (:db/id tx) "reviewed") (reviewed-checkbox (:db/id tx) reviewed true)]])

(defn- split-parent-row [stale? {:transaction/keys [posted-date account payee effective-description]}]
  [:tr {:role "row" :class (row-class "is-split-parent" stale?)}
   [:td [:span.numeric (fmt/date posted-date)]]
   [:td (or (:account/external-name account) "—")]
   [:td (or (get-in account [:account/institution :institution/name]) "—")]
   [:td payee]
   [:td.description-cell (if (str/blank? effective-description) "—" effective-description)]
   [:td.amount-cell] [:td.category-cell] [:td.reviewed-cell]])

(defn- split-child-row [stale? {:split/keys [amount memo category reviewed]}]
  [:tr {:role "row" :class (row-class "split-child-row" stale?)}
   [:td] [:td] [:td] [:td]
   [:td.description-cell (if (str/blank? memo) "—" memo)]
   [:td.amount-cell (amount-span amount true)]
   [:td.category-cell (or (:category/name category) "Uncategorized")]
   [:td.reviewed-cell (reviewed-checkbox nil reviewed false)]])

(defn- tx-rows [stale-ids tx]
  (let [stale? (contains? stale-ids (:db/id tx))]
    (if-let [parts (seq (:transaction/splits tx))]
      (into [(split-parent-row stale? tx)] (map #(split-child-row stale? %) (sort-by :split/order parts)))
      [(normal-row stale? tx)])))

(defn- tbody
  ([rows] (tbody rows #{}))
  ([rows stale-ids]
   (into [:tbody {:id "tx-tbody"}] (mapcat #(tx-rows stale-ids %) rows))))

;; ---------------------------------------------------------------------------
;; Header (sortable columns; cycle asc → desc → cleared, server-side)
;; ---------------------------------------------------------------------------

(defn- sort-click-js [col]
  ;; Static literal (the column name is known at render time, never user/server data).
  (str "$sortCol === '" col "'"
       " ? ($sortDir === 'asc' ? ($sortDir = 'desc') : ($sortCol = '', $sortDir = 'asc'))"
       " : ($sortCol = '" col "', $sortDir = 'asc');"
       " $page = 0; @get('/v2/rows')"))

;; --- Header-filter funnels -------------------------------------------------
;; Account/Institution/Category headers carry a funnel that opens a floating popover
;; (rendered outside the table so its clicks don't reach the sort handler). Selecting boxes
;; updates the persistent filter.<col> array and @get's the rows (the view engine filters
;; server-side). Open/query state is ephemeral (_-prefixed). Options + counts are computed
;; server-side per load.

(def ^:private funnel-cols #{"account" "institution" "category"})

(defn- tx-account-id [tx] (get-in tx [:transaction/account :db/id]))
(defn- tx-institution-id [tx] (get-in tx [:transaction/account :account/institution :db/id]))
(defn- tx-category-ids [tx]
  (distinct (if-let [parts (seq (:transaction/splits tx))]
              (keep #(get-in % [:split/category :db/id]) parts)
              (when-let [id (get-in tx [:transaction/category :db/id])] [id]))))

(defn- count-options [txs id-fn label-fn]
  (->> txs
       (keep (fn [tx] (when-let [id (id-fn tx)] [id (label-fn tx)])))
       (reduce (fn [m [id label]]
                 (-> m (assoc-in [id :label] label) (update-in [id :count] (fnil inc 0)))) {})
       (map (fn [[id {:keys [label count]}]] {:id id :label label :count count}))
       (sort-by :label)))

(defn- account-options [txs]
  (count-options txs tx-account-id #(or (get-in % [:transaction/account :account/external-name]) "Unknown")))
(defn- institution-options [txs]
  (count-options txs tx-institution-id #(or (get-in % [:transaction/account :account/institution :institution/name]) "Unknown")))
(defn- category-funnel-options
  "Real categories present this month with the number of transactions touching each
   (split-aware). Uncategorized is excluded — the toolbar chip owns it."
  [txs]
  (let [name-of (fn [tx]
                  (concat (when-let [c (:transaction/category tx)] (when (:db/id c) [[(:db/id c) (:category/name c)]]))
                          (mapcat (fn [p] (when-let [c (:split/category p)] (when (:db/id c) [[(:db/id c) (:category/name c)]])))
                                  (:transaction/splits tx))))
        names (into {} (mapcat name-of txs))
        counts (frequencies (mapcat tx-category-ids txs))]
    (->> counts (map (fn [[id n]] {:id id :label (get names id "Unknown") :count n})) (sort-by :label))))

(defn- funnel-icon []
  [:svg.th-filter-icon {:width "12" :height "12" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
                        :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden "true"}
   [:polygon {:points "22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"}]])

(defn- funnel-open-js [col]
  (str "$_openFunnel = ($_openFunnel === '" col "' ? '' : '" col "'); $_funnelQuery = ''; "
       "$_funnelX = Math.max(8, Math.min(el.getBoundingClientRect().left, window.innerWidth - 300)); "
       "$_funnelY = el.getBoundingClientRect().bottom + 4"))

(defn- funnel-button [col label]
  (let [active (str "$filter." col ".filter(x=>x).length")]
    [:button.th-filter-btn
     {:type "button" :aria-haspopup "dialog" :aria-label (str "Filter " label) :aria-expanded "false"
      "data-on:click__stop" (funnel-open-js col)
      "data-attr" (str "{'aria-expanded': $_openFunnel === '" col "' ? 'true' : 'false'}")
      "data-class" (str "{'is-active': " active " > 0}")}
     (funnel-icon)
     [:span.th-filter-count {"data-show" (str active " > 0") "data-text" active}]]))

(defn- resize-handle [] [:div.col-resize-handle {:aria-hidden "true"}])

(defn- th [{:keys [id label sortable min protected]}]
  (let [meta {"data-col-id" id "data-min" (str min) "data-protected" (str (boolean protected))}
        handle (when (resizable-cols id) (resize-handle))]
    (if sortable
      [:th (merge meta {"class" "th-sortable"
                        "data-on:click" (sort-click-js id)
                        "data-attr" (str "{'aria-sort': $sortCol === '" id "'"
                                         " ? ($sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}")})
       [:span.th-content
        [:span.th-label label]
        [:span.th-sort-indicator
         {"data-text" (str "$sortCol === '" id "' ? ($sortDir === 'asc' ? ' ↑' : ' ↓') : ''")}]
        (when (funnel-cols id) (funnel-button id label))]
       handle]
      [:th meta [:span.th-content [:span.th-label label]] handle])))

(defn- table [rows]
  ;; .table-resizable = fixed layout; the <colgroup> widths are authoritative and the v2-resize
  ;; island refines them (auto-fit on load + drag handles). Density/sticky come with the class.
  [:div.transactions-table-scroll {:tabindex "0"}
   [:table.table.table-resizable {:role "grid" "data-class" (cols-hide-class)}
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
    (count-chip "Hide transfers" "hideTransfers" "count-transfers" (:transfers-hidden counts))]
   [:div.toolbar-actions (undo-redo-controls) (column-picker)]])

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
;; Lingering + edit fragments
;; ---------------------------------------------------------------------------

(defn- view-with-linger
  "Compose filter → linger-inject → sort → paginate. Lingered txs (touched since the last
   view change, no longer matching the filter) are kept in the result and reported as
   `:stale-ids` so an edit doesn't make a row vanish under you."
  [txs vs linger-set]
  (let [matched     (view/filter-txs txs vs)
        matched-ids (set (map :db/id matched))
        lingered    (filter #(and (linger-set (:db/id %)) (not (matched-ids (:db/id %)))) txs)
        combined    (view/sort-txs (concat matched lingered) (:sort vs))]
    {:result    (view/paginate combined (:page vs) (:page-size vs))
     :stale-ids (set (map :db/id lingered))}))

(defn- counts-fragment
  "The toolbar count badges as one HTML string (each morphed by id) — re-patched after an
   edit, since reviewing/categorizing a row moves these server-authoritative counts."
  [{:keys [unreviewed total uncategorized transfers-hidden]}]
  (str (r/render [:span#count-unreviewed.filter-count unreviewed])
       (r/render [:span#count-total.filter-count total])
       (r/render [:span#count-uncategorized.filter-count uncategorized])
       (r/render [:span#count-transfers.filter-count transfers-hidden])))

(defn- undo-redo-controls
  "Undo/redo buttons for the toolbar (stable #undo-redo morph target, re-rendered after every
   edit so the enabled state + tooltip track the command log). The keyboard shortcuts
   (Cmd/Ctrl+Z / +Shift) do the same thing."
  []
  (let [user auth/user-id
        undoable (commands/undo-label user)
        redoable (commands/redo-label user)]
    [:div.undo-redo {:id "undo-redo" :role "group" :aria-label "Undo and redo"}
     [:button (cond-> {:class "button button-secondary undo-redo-btn" :type "button"
                       :aria-label "Undo" :title (if undoable (str "Undo: " undoable) "Nothing to undo")
                       "data-on:click" "@post('/v2/undo')"}
                (not undoable) (assoc :disabled true))
      "↶"]
     [:button (cond-> {:class "button button-secondary undo-redo-btn" :type "button"
                       :aria-label "Redo" :title (if redoable (str "Redo: " redoable) "Nothing to redo")
                       "data-on:click" "@post('/v2/redo')"}
                (not redoable) (assoc :disabled true))
      "↷"]]))

(defn- column-picker
  "Toolbar dropdown toggling which columns show. The `cols.<id>` checkboxes flip the table's
   `hide-<id>` class via data-class (pure CSS, no round-trip); the URL reflector persists them.
   The footer's \"Reset widths\" hands every column back to auto-fit via the v2-resize island's
   window hook (it clears the user-dragged widths and re-measures). `__stop` so the open-click
   isn't also seen as a click-outside."
  []
  [:div.filter-button-container.column-picker
   [:button.button.button-secondary.filter-button
    {:type "button" :aria-haspopup "true"
     "data-on:click__stop" "$_colsOpen = !$_colsOpen"
     "data-attr" "{'aria-expanded': $_colsOpen}"}
    "Columns"
    [:span.filter-button-arrow {"data-text" "$_colsOpen ? '▲' : '▼'"} "▼"]]
   [:div.filter-dropdown
    {"data-show" "$_colsOpen" "data-on:click__outside" "$_colsOpen = false"}
    [:ul.filter-dropdown-list
     (for [[id label] hideable-columns]
       [:li.filter-dropdown-item
        [:label.filter-dropdown-checkbox-label
         [:input.filter-dropdown-checkbox {:type "checkbox" "data-bind" (str "cols." id)}]
         [:span.filter-dropdown-label-text label]]])]
    [:div.filter-dropdown-footer
     [:button.button.button-secondary.filter-dropdown-clear
      {:type "button" "data-on:click" "window.__v2ResetWidths && window.__v2ResetWidths()"}
      "Reset widths"]]]])

;; ---------------------------------------------------------------------------
;; Routes
;; ---------------------------------------------------------------------------

(defn- empty-state []
  [:div.empty-state
   [:div.empty-state-title "No transactions this month"]
   [:p "Use the month controls to browse another period, or import from Setup."]])

(defn- url-sync
  "Reflect the persistent view-state into the URL on change (the v2-url island owns the
   serialization). Scoped by the signal-patch filter to the persistent signals so it ignores
   edit + ephemeral-UI signals. The READ side is server-side (query->view-state on load)."
  []
  [:div {:hidden true
         "data-on-signal-patch-filter"
         "{include: /^(search|scope|hideTransfers|uncat|sortCol|sortDir|page|pageSize)$|^(cols|filter)\\./}"
         "data-on-signal-patch"
         (str "window.__v2syncUrl && window.__v2syncUrl({q: $search, scope: $scope,"
              " ht: $hideTransfers, uncat: $uncat, sortCol: $sortCol, sortDir: $sortDir,"
              " page: $page, pageSize: $pageSize, cols: $cols,"
              " fa: $filter.account, fi: $filter.institution, fc: $filter.category})")}])

(defn- funnel-popover
  "One header-filter popover (floating, position:fixed via .header-filter-popover so it
   escapes the table's overflow). Rendered outside the table so clicks inside don't reach the
   sort handler. Each checkbox binds the persistent $filter.<col> array and @get's the rows;
   the in-funnel search filters the options client-side (label JSON-encoded so a name with a
   quote can't break the expression). Clear empties the selection."
  [col options]
  [:div.header-filter-popover
   {"data-show" (str "$_openFunnel === '" col "'")
    "data-style" "{left: $_funnelX + 'px', top: $_funnelY + 'px'}"
    "data-on:click__outside" (str "$_openFunnel === '" col "' && ($_openFunnel = '')")}
   [:div.filter-dropdown.filter-dropdown--bare
    [:div.filter-dropdown-header
     [:input.filter-dropdown-search
      {:type "search" :placeholder "Search…" "data-bind" "_funnelQuery" :aria-label "Filter options"}]]
    [:ul.filter-dropdown-list
     (if (empty? options)
       [:li.filter-dropdown-item.empty "No values"]
       (for [{:keys [id label count]} options]
         [:li.filter-dropdown-item
          {"data-show" (str "$_funnelQuery === '' || "
                            (r/signals (str/lower-case label)) ".includes($_funnelQuery.toLowerCase())")}
          [:label.filter-dropdown-checkbox-label
           [:input.filter-dropdown-checkbox
            {:type "checkbox" :value (str id) "data-bind" (str "filter." col)
             "data-on:change" "$page = 0; @get('/v2/rows')"}]
           [:span.filter-dropdown-label-text label]
           [:span.filter-dropdown-count count]]]))]
    [:div.filter-dropdown-footer
     [:button.button.button-secondary.filter-dropdown-clear
      {:type "button" "data-on:click" (str "$filter." col " = []; $page = 0; @get('/v2/rows')")} "Clear"]]]])

(defn- funnel-popovers [account-opts institution-opts category-opts]
  (list (funnel-popover "account" account-opts)
        (funnel-popover "institution" institution-opts)
        (funnel-popover "category" category-opts)))

(def ^:private undo-key-js
  ;; Cmd/Ctrl+Z = undo, +Shift = redo. Static literal (no server data).
  (str "(evt.metaKey || evt.ctrlKey) && (evt.key === 'z' || evt.key === 'Z')"
       " && (evt.preventDefault(), evt.shiftKey ? @post('/v2/redo') : @post('/v2/undo'))"))

(defn page
  "GET /v2 — full page. Seeds the view-state from the URL; a fresh load clears lingering."
  [{:keys [db-conn]}]
  (fn [req]
    (commands/clear-linger! auth/user-id)
    (let [m (month/parse (get-in req [:query-params "month"]))
          month-str (month/serialize m)
          txs (db-transactions/list-for-month db-conn month-str)
          counts (db-transactions/month-counts txs)
          stats (db-stats/entity-counts db-conn)
          categories (db-categories/list-all db-conn)
          vs (query->view-state (:query-params req))
          result (view/view txs vs)]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body
       (layout/document
        {:title "Finance Aggregator"
         :islands ["combobox" "v2-url" "grid-nav" "v2-resize"]
         :signals (client-signals vs month-str result (:query-params req))}
        [:div.container.container--workspace {"data-on:keydown__window" undo-key-js}
         (shell/masthead {:active :transactions :stats stats})
         [:div.transactions-layout
          [:div.card
           (toolbar m counts)
           (if (empty? txs)
             (empty-state)
             (list (table (:rows result)) (pagination-bar result)))]]
         (when (seq txs)
           (funnel-popovers (account-options txs) (institution-options txs) (category-funnel-options txs)))
         (category-options categories)
         (url-sync)])})))

(defn rows
  "GET /v2/rows — a pure view change: clear lingering, re-run the view, morph the tbody +
   pagination bar, patch $page back to the clamped value."
  [{:keys [db-conn]}]
  (fn [req]
    (commands/clear-linger! auth/user-id)
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

(defn- edit-response
  "Shared SSE response after any edit/undo/redo: re-render the tbody (lingering the touched
   rows), the pagination bar, the server-authoritative counts, and the undo toast."
  [db-conn req signals]
  (let [user auth/user-id
        month-str (month/serialize (month/parse (:month signals)))
        txs (db-transactions/list-for-month db-conn month-str)
        {:keys [result stale-ids]} (view-with-linger txs (signals->view-state signals) (commands/linger user))
        counts (db-transactions/month-counts txs)]
    (hk/->sse-response
     req
     {hk/on-open
      (fn [sse]
        (d*/patch-elements! sse (r/render (tbody (:rows result) stale-ids)))
        (d*/patch-elements! sse (r/render (pagination-bar result)))
        (d*/patch-elements! sse (counts-fragment counts))
        (d*/patch-elements! sse (r/render (undo-redo-controls)))
        (d*/patch-signals! sse (r/signals {:page (:page result)}))
        (d*/close-sse! sse))})))

(defn toggle-reviewed
  "PUT /v2/tx/:id/reviewed/:v — record + apply a reviewed command, then re-render."
  [{:keys [db-conn]}]
  (fn [req]
    (let [tx-id (-> req :path-params :id parse-long)
          after (= "true" (-> req :path-params :v))]
      (commands/apply! db-conn auth/user-id
                       {:type :set-reviewed :tx-id tx-id :before (not after) :after after
                        :label (if after "Marked reviewed" "Marked unreviewed")})
      (edit-response db-conn req (r/read-signals req)))))

(defn set-description
  "PUT /v2/tx/:id/description — record + apply an inline-description-edit command (the new
   value rides in the $editValue courier signal), then re-render."
  [{:keys [db-conn]}]
  (fn [req]
    (let [tx-id (-> req :path-params :id parse-long)
          signals (r/read-signals req)
          before (db-transactions/user-description db-conn tx-id)
          after (str/trim (or (:editValue signals) ""))]
      (commands/apply! db-conn auth/user-id
                       {:type :set-description :tx-id tx-id :before before :after after
                        :label "Edited description"})
      (edit-response db-conn req signals))))

(defn set-category
  "PUT /v2/tx/:id/category — record + apply an :update-category command (the chosen id rides
   in the $catValue courier; empty = clear), then re-render (counts move)."
  [{:keys [db-conn]}]
  (fn [req]
    (let [tx-id (-> req :path-params :id parse-long)
          signals (r/read-signals req)
          before (db-transactions/category-id db-conn tx-id)
          raw (:catValue signals)
          after (cond (number? raw) (long raw)
                      (string? raw) (some-> raw not-empty parse-long)
                      :else nil)]
      (commands/apply! db-conn auth/user-id
                       {:type :set-category :tx-id tx-id :before before :after after
                        :label "Recategorized"})
      (edit-response db-conn req signals))))

(defn undo
  "POST /v2/undo — reverse the last edit (keeping the row lingering), then re-render."
  [{:keys [db-conn]}]
  (fn [req]
    (commands/undo! db-conn auth/user-id)
    (edit-response db-conn req (r/read-signals req))))

(defn redo
  "POST /v2/redo — re-apply the last undone edit, then re-render."
  [{:keys [db-conn]}]
  (fn [req]
    (commands/redo! db-conn auth/user-id)
    (edit-response db-conn req (r/read-signals req))))

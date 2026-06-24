(ns finance-aggregator.web.pages.transactions
  "Server-authoritative transactions workspace at `/` (the dumb-view layer — all data logic
   lives in the pure, tested web.view + web.view-state).

   Every control (search / scope / chips / funnels / sort / paginate) sets its signal and
   @get('/transactions/rows'); the server runs the pure view engine (web.view) and morphs
   `#tx-tbody` + `#pagination-bar` by id, patching $page back to the clamped value. Edits
   (reviewed/description/category) @put a command (web.commands) and morph the tbody (with
   lingering) + counts + the undo/redo controls. No per-row signals, no baked data-show,
   no client-side filter/sort/paginate islands. Persistent view-state lives in the URL;
   ephemeral UI state is `_`-prefixed. Islands: combobox (Zag), grid-nav, url, resize."
  (:require
   [clojure.string :as str]
   [finance-aggregator.auth :as auth]
   [finance-aggregator.db.categories :as db-categories]
   [finance-aggregator.db.stats :as db-stats]
   [finance-aggregator.db.transactions :as db-transactions]
   [finance-aggregator.db.transfers :as db-transfers]
   [finance-aggregator.web.commands :as commands]
   [finance-aggregator.web.format :as fmt]
   [finance-aggregator.web.layout :as layout]
   [finance-aggregator.web.month :as month]
   [finance-aggregator.web.render :as r]
   [finance-aggregator.web.shell :as shell]
   [finance-aggregator.web.view :as view]
   [finance-aggregator.web.view-state :as vs]
   [starfederation.datastar.clojure.adapter.http-kit :as hk]
   [starfederation.datastar.clojure.api :as d*]))

;; ---------------------------------------------------------------------------
;; Columns
;; ---------------------------------------------------------------------------
;; The column config + view-state codec live in web.view-state (pure, tested). This page
;; consumes `vs/columns` (render order/widths/headers) and `vs/hideable-columns` (the picker).

(defn- cols-hide-class []
  ;; Static Datastar attribute value — toggles `hide-<id>` classes from the `cols.<id>`
  ;; signals (no data manipulation; the column ids are render-time literals).
  (str "{" (str/join ", " (map (fn [[id _]] (str "'hide-" id "': !$cols." id)) vs/hideable-columns)) "}"))

(declare undo-redo-controls column-picker) ; defined later, used by the toolbar/table

;; --- Toolbar / row icons (inline SVG, sized by CSS) ------------------------
;; Stroke icons centred by the button's flex box — no font-glyph baseline to fight, so the
;; toolbar carets/arrows and the row caret sit dead-centre and read as one controlled set.

(defn- icon [& body]
  (into [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"
               :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden "true"}]
        body))

(defn- chevron-left  [] (icon [:polyline {:points "15 18 9 12 15 6"}]))
(defn- chevron-right [] (icon [:polyline {:points "9 18 15 12 9 6"}]))
(defn- undo-icon     [] (icon [:path {:d "M9 14 4 9l5-5"}] [:path {:d "M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5 5.5 5.5 0 0 1-5.5 5.5H11"}]))
(defn- redo-icon     [] (icon [:path {:d "m15 14 5-5-5-5"}] [:path {:d "M20 9H9.5A5.5 5.5 0 0 0 4 14.5 5.5 5.5 0 0 0 9.5 20H13"}]))

;; ---------------------------------------------------------------------------
;; Rows (editable normal rows + read-only split children)
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
             "data-on:change" (str "@put('/transactions/" tx-id "/reviewed/' + el.checked)")}]
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
       " $editValue = el.value, @put('/transactions/" tx-id "/description'),"
       " el.closest('.description-cell').classList.remove('editing')"))

(defn- desc-keydown-js [tx-id]
  ;; Escape reverts the input and closes the editor, then hands the keyboard back to grid-nav:
  ;; the `gridedit` cancel event returns focus to the cell (otherwise the hidden input blurs to
  ;; <body> and arrow-key navigation dies — grid-nav's keydown listener only fires within the
  ;; scroll container). stopPropagation keeps this same Escape from also bubbling to grid-nav's
  ;; navigation handler, which would clear the active cell now that the editor is closed.
  (str "evt.key === 'Enter' && (" (desc-commit-js tx-id) "); "
       "evt.key === 'Escape' && (evt.stopPropagation(), el.value = el.defaultValue,"
       " el.closest('.description-cell').classList.remove('editing'),"
       " el.closest('[data-cell]').dispatchEvent(new CustomEvent('gridedit',"
       " {detail: {action: 'cancel'}, bubbles: true})))"))

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
   [:input {:type "hidden" :id (str "cat-courier-tx" tx-id)
            "data-on:change" (str "$catValue = el.value; @put('/transactions/" tx-id "/category')")}]))

(defn- transfer-status-marker
  "Inline transfer affordance beside the category (transfer rows only): a matched row shows a
   quiet ⇄ glyph; an unmatched transfer-typed row shows an ochre \"⇄ Match\" pill. Both open the
   match modal (the same GET the row-actions Match item uses). nil for non-transfer rows.
   `__stop` so a click opens the modal without reaching the cell's combobox / grid-nav."
  [tx]
  (let [open (str "@get('/transactions/" (:db/id tx) "/match')")]
    (if-let [pair (:transaction/transfer-pair tx)]
      (let [partner (or (get-in pair [:transaction/account :account/external-name]) "another account")]
        [:button.transfer-status.transfer-status-matched
         {:type "button" :tabindex "-1" "data-on:click__stop" open
          :title (str "Matched transfer with " partner " (" (fmt/amount (:transaction/amount pair)) ")")
          :aria-label (str "Matched transfer with " partner " — view or unmatch")}
         [:span.transfer-status-glyph {:aria-hidden "true"} "⇄"]])
      (when (= :transfer (get-in tx [:transaction/category :category/type]))
        [:button.transfer-status.transfer-status-unmatched
         {:type "button" :tabindex "-1" "data-on:click__stop" open
          :title "Transfer with no matched counterpart — click to match"}
         [:span.transfer-status-glyph {:aria-hidden "true"} "⇄"] "Match"]))))

(defn- category-cell-inner
  "The category cell's content: the editable combobox button + its hidden courier, with the
   transfer marker tucked in beside them (wrapped in .category-cell-row) for transfer rows.
   The hidden input stays a sibling of the combo button either way, so the combobox island's
   `cell.parentElement.querySelector` still finds it."
  [tx]
  (let [[btn hidden] (editable-category (:db/id tx) (:transaction/category tx))]
    (if-let [marker (transfer-status-marker tx)]
      [:div.category-cell-row btn marker hidden]
      (list btn hidden))))

(defn- category-options
  "Hidden source-of-truth list the combobox island reconstructs its Category[] from
   (id/parent/sort-order as data-attrs — the model is carried in the DOM as data-attrs)."
  [categories]
  [:ul#category-options {:hidden true :aria-hidden "true"}
   (for [c categories]
     [:li (cond-> {:data-id (:db/id c)}
            (:category/type c)                   (assoc :data-type (name (:category/type c)))
            (get-in c [:category/parent :db/id]) (assoc :data-parent (get-in c [:category/parent :db/id]))
            (:category/sort-order c)             (assoc :data-sort (:category/sort-order c)))
      (:category/name c)])])

(defn- grid-cell
  "Attrs marking a <td> as a keyboard-navigable grid cell (txId:tx:col, consumed by the
   gridNavigation reducer). Normal rows only; split cells aren't navigable yet."
  [tx-id col]
  {:data-cell (str tx-id ":tx:" col) :role "gridcell" :tabindex "-1"})

;; --- Row-actions menu ------------------------------------------------------
;; A trailing always-on chrome column (NOT a hideable data column, so it stays out of the
;; cols/URL/picker machinery). A quiet caret opens one shared floating menu (row-actions-menu,
;; rendered outside the table so it escapes overflow), carrying the row's id + split state +
;; position into the ephemeral _rowMenu signals.

(defn- row-actions-cell [tx-id split? matched?]
  [:td.actions-cell
   [:div.row-actions
    [:button.row-actions-trigger
     {:type "button" :aria-haspopup "menu" :aria-label "Row actions"
      "data-attr" (str "{'aria-expanded': $_rowMenu === " tx-id " ? 'true' : 'false'}")
      "data-on:click__stop"
      (str "$_rowMenu = ($_rowMenu === " tx-id " ? 0 : " tx-id ");"
           " $_rowMenuSplit = " (boolean split?) "; $_rowMenuMatched = " (boolean matched?) ";"
           " $_rowMenuX = Math.max(8, window.innerWidth - el.getBoundingClientRect().right);"
           " $_rowMenuY = el.getBoundingClientRect().bottom + 4")}
     (chevron-right)]]])

(defn- normal-row [stale? {:transaction/keys [posted-date account payee effective-description
                                              amount reviewed] :as tx}]
  [:tr {:role "row" :class (row-class "" stale?)}
   [:td [:span.numeric (fmt/date posted-date)]]
   [:td (or (:account/external-name account) "—")]
   [:td (or (get-in account [:account/institution :institution/name]) "—")]
   [:td payee]
   [:td.description-cell (grid-cell (:db/id tx) "description") (editable-description (:db/id tx) effective-description)]
   [:td.amount-cell (amount-span amount false)]
   [:td.category-cell (grid-cell (:db/id tx) "category") (category-cell-inner tx)]
   [:td.reviewed-cell (grid-cell (:db/id tx) "reviewed") (reviewed-checkbox (:db/id tx) reviewed true)]
   (row-actions-cell (:db/id tx) false (some? (:transaction/transfer-pair tx)))])

(defn- split-parent-row [stale? {:transaction/keys [posted-date account payee effective-description] :as tx}]
  [:tr {:role "row" :class (row-class "is-split-parent" stale?)}
   [:td [:span.numeric (fmt/date posted-date)]]
   [:td (or (:account/external-name account) "—")]
   [:td (or (get-in account [:account/institution :institution/name]) "—")]
   [:td payee]
   [:td.description-cell (if (str/blank? effective-description) "—" effective-description)]
   [:td.amount-cell] [:td.category-cell] [:td.reviewed-cell]
   (row-actions-cell (:db/id tx) true (some? (:transaction/transfer-pair tx)))])

(defn- split-child-row [stale? {:split/keys [amount memo category reviewed]}]
  [:tr {:role "row" :class (row-class "split-child-row" stale?)}
   [:td] [:td] [:td] [:td]
   [:td.description-cell (if (str/blank? memo) "—" memo)]
   [:td.amount-cell (amount-span amount true)]
   [:td.category-cell (or (:category/name category) "Uncategorized")]
   [:td.reviewed-cell (reviewed-checkbox nil reviewed false)]
   [:td.actions-cell]])

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
       " $page = 0; @get('/transactions/rows')"))

;; --- Header-filter funnels -------------------------------------------------
;; Account/Institution/Category headers carry a funnel that opens a floating popover
;; (rendered outside the table so its clicks don't reach the sort handler). Selecting boxes
;; updates the persistent filter.<col> array and @get's the rows (the view engine filters
;; server-side). Open/query state is ephemeral (_-prefixed). Options + counts are computed
;; server-side per load.

(def ^:private funnel-cols #{"account" "institution" "category"})

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
        handle (when (vs/resizable-cols id) (resize-handle))]
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
  ;; .table-resizable = fixed layout; the <colgroup> widths are authoritative and the resize
  ;; island refines them (auto-fit on load + drag handles). Density/sticky come with the class.
  [:div.transactions-table-scroll {:tabindex "0"}
   [:table.table.table-resizable {:role "grid" "data-class" (cols-hide-class)}
    [:colgroup
     (concat (for [{:keys [w]} vs/columns] [:col {:style (str "width:" w "px")}])
             [[:col.actions-col {:aria-hidden "true"}]])]
    [:thead [:tr (concat (map th vs/columns) [[:th.actions-th {:aria-hidden "true"}]])]]
    (tbody rows)]])

;; ---------------------------------------------------------------------------
;; Toolbar
;; ---------------------------------------------------------------------------

(defn- month-navigator [m]
  ;; Full navigation (anchor). URL-state write-back (preserving filters across month
  ;; change) isn't wired yet, so a month change resets the view.
  [:div.month-navigator
   [:div.month-navigator-controls
    [:a.button.button-secondary.month-nav-button
     {:href (str "/?month=" (month/serialize (month/prev-month m))) :title "Previous month"
      :aria-label "Previous month"} (chevron-left)]
    [:span.month-navigator-display (month/display m)]
    [:a.button.button-secondary.month-nav-button
     {:href (str "/?month=" (month/serialize (month/next-month m))) :title "Next month"
      :aria-label "Next month"} (chevron-right)]]])

(defn- search-box []
  [:div.table-search
   [:svg.table-search-icon {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"
                            :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden "true"}
    [:circle {:cx "11" :cy "11" :r "8"}] [:line {:x1 "21" :y1 "21" :x2 "16.65" :y2 "16.65"}]]
   [:input.table-search-input
    {:type "search" :placeholder "Search payee, description…" :aria-label "Search transactions"
     "data-bind" "search"
     "data-on:input__debounce.300ms" "$page = 0; @get('/transactions/rows')"}]])

(defn- scope-toggle [{:keys [unreviewed total]}]
  [:div.scope-toggle {:role "group" :aria-label "Review scope"}
   [:button.scope-toggle-btn
    {"type" "button" "data-on:click" "$scope = 'needs-review'; $page = 0; @get('/transactions/rows')"
     "data-class" "{'is-active': $scope === 'needs-review'}"}
    "Needs review" [:span#count-unreviewed.filter-count unreviewed]]
   [:button.scope-toggle-btn
    {"type" "button" "data-on:click" "$scope = 'all'; $page = 0; @get('/transactions/rows')"
     "data-class" "{'is-active': $scope === 'all'}"}
    "All" [:span#count-total.filter-count total]]])

(defn- count-chip [label signal span-id count]
  [:button.count-chip
   {"type" "button"
    "data-on:click" (str "$" signal " = !$" signal "; $page = 0; @get('/transactions/rows')")
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
   [:div.toolbar-actions
    [:button.button.button-secondary.filter-button
     {:type "button" :aria-haspopup "dialog" "data-on:click" "@get('/transactions/review-transfers')"}
     "Review transfers"]
    (undo-redo-controls) (column-picker)]])

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
      (for [n vs/page-size-options]
        [:button {:type "button"
                  :class (str "button pagination-size-button "
                              (if (= n page-size) "button-primary" "button-secondary"))
                  "data-on:click" (str "$pageSize = " n "; $page = 0; @get('/transactions/rows')")}
         (str n)])]
     [:div.pagination-navigation
      (nav-btn {:title "First page"    :disabled? first? :js "$page = 0; @get('/transactions/rows')"} "«")
      (nav-btn {:title "Previous page" :disabled? first? :js "$page = Math.max(0, $page - 1); @get('/transactions/rows')"} "‹")
      [:span.pagination-status (str "Page " (inc page) " of " page-count)]
      (nav-btn {:title "Next page" :disabled? last? :js "$page = $page + 1; @get('/transactions/rows')"} "›")
      (nav-btn {:title "Last page" :disabled? last? :js (str "$page = " (dec page-count) "; @get('/transactions/rows')")} "»")]]))

;; ---------------------------------------------------------------------------
;; Lingering + edit fragments
;; ---------------------------------------------------------------------------

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
                       "data-on:click" "@post('/transactions/undo')"}
                (not undoable) (assoc :disabled true))
      (undo-icon)]
     [:button (cond-> {:class "button button-secondary undo-redo-btn" :type "button"
                       :aria-label "Redo" :title (if redoable (str "Redo: " redoable) "Nothing to redo")
                       "data-on:click" "@post('/transactions/redo')"}
                (not redoable) (assoc :disabled true))
      (redo-icon)]]))

(defn- column-picker
  "Toolbar dropdown toggling which columns show. The `cols.<id>` checkboxes flip the table's
   `hide-<id>` class via data-class (pure CSS, no round-trip); the URL reflector persists them.
   The footer's \"Reset widths\" hands every column back to auto-fit via the resize island's
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
     (for [[id label] vs/hideable-columns]
       [:li.filter-dropdown-item
        [:label.filter-dropdown-checkbox-label
         [:input.filter-dropdown-checkbox {:type "checkbox" "data-bind" (str "cols." id)}]
         [:span.filter-dropdown-label-text label]]])]
    [:div.filter-dropdown-footer
     [:button.button.button-secondary.filter-dropdown-clear
      {:type "button" "data-on:click" "window.__resetWidths && window.__resetWidths()"}
      "Reset widths"]]]])

;; ---------------------------------------------------------------------------
;; Routes
;; ---------------------------------------------------------------------------

(defn- empty-state []
  [:div.empty-state
   [:div.empty-state-title "No transactions this month"]
   [:p "Use the month controls to browse another period, or import from Setup."]])

(defn- error-banner
  "The dismissable error banner — the stable #error-bar morph target. Empty on load (and
   re-emptied by the next successful action), so it takes no vertical space; a failed
   mutation patches it with `msg`. role=alert (present from load) so a patched-in message
   is announced. Dismiss empties it client-side (the wrapper + role persist)."
  ([] (error-banner nil))
  ([msg]
   [:div {:id "error-bar" :role "alert"}
    (when msg
      [:div.error-banner
       [:span msg]
       [:button {:type "button" :aria-label "Dismiss error"
                 "data-on:click" "document.getElementById('error-bar').replaceChildren()"}
        "×"]])]))

(defn- sr-status
  "Visually-hidden polite live region (#sr-status). After an edit or view change it's
   re-patched with a short spoken summary, so the SSE-morphed counts — which only sighted
   users can watch tick — are announced to screen-reader users too."
  ([] (sr-status nil))
  ([msg] [:div {:id "sr-status" :class "sr-only" :role "status" :aria-live "polite"} msg]))

(defn- review-status-message
  "The spoken summary after an edit: the faceted needs-review count, plus the uncategorized
   count when any remain. Gives a screen-reader user confirmation an action landed and where
   the counts now stand."
  [{:keys [unreviewed uncategorized]}]
  (str unreviewed (if (= 1 unreviewed) " transaction" " transactions") " to review"
       (when (pos? uncategorized) (str ", " uncategorized " uncategorized"))))

(defn- url-sync
  "Reflect the persistent view-state into the URL on change (the url island owns the
   serialization). Scoped by the signal-patch filter to the persistent signals so it ignores
   edit + ephemeral-UI signals. The READ side is server-side (query->view-state on load)."
  []
  [:div {:hidden true
         "data-on-signal-patch-filter"
         "{include: /^(search|scope|hideTransfers|uncat|sortCol|sortDir|page|pageSize)$|^(cols|filter)\\./}"
         "data-on-signal-patch"
         (str "window.__syncUrl && window.__syncUrl({q: $search, scope: $scope,"
              " ht: $hideTransfers, uncat: $uncat, sortCol: $sortCol, sortDir: $sortDir,"
              " page: $page, pageSize: $pageSize, cols: $cols,"
              " fa: $filter.account, fi: $filter.institution, fc: $filter.category})")}])

(defn- funnel-list
  "A header funnel's option list. Its own #funnel-list-<col> id is the morph target so a view
   change can re-patch the FACETED counts (each = rows matching the OTHER filters with that
   value). Each checkbox binds $filter.<col>; the in-funnel search filters client-side (label
   JSON-encoded so a quote can't break the expression)."
  [col options]
  [:ul.filter-dropdown-list {:id (str "funnel-list-" col)}
   (if (empty? options)
     [:li.filter-dropdown-item.empty "No values"]
     (for [{:keys [id label count]} options]
       [:li.filter-dropdown-item
        {"data-show" (str "$_funnelQuery === '' || "
                          (r/signals (str/lower-case label)) ".includes($_funnelQuery.toLowerCase())")}
        [:label.filter-dropdown-checkbox-label
         [:input.filter-dropdown-checkbox
          {:type "checkbox" :value (str id) "data-bind" (str "filter." col)
           "data-on:change" "$page = 0; @get('/transactions/rows')"}]
         [:span.filter-dropdown-label-text label]
         [:span.filter-dropdown-count count]]]))])

(defn- funnel-popover
  "One header-filter popover (floating, position:fixed via .header-filter-popover so it
   escapes the table's overflow). Rendered outside the table so clicks inside don't reach the
   sort handler. Clear empties the selection."
  [col options]
  [:div.header-filter-popover
   {"data-show" (str "$_openFunnel === '" col "'")
    "data-style" "{left: $_funnelX + 'px', top: $_funnelY + 'px'}"
    "data-on:click__outside" (str "$_openFunnel === '" col "' && ($_openFunnel = '')")}
   [:div.filter-dropdown.filter-dropdown--bare
    [:div.filter-dropdown-header
     [:input.filter-dropdown-search
      {:type "search" :placeholder "Search…" "data-bind" "_funnelQuery" :aria-label "Filter options"}]]
    (funnel-list col options)
    [:div.filter-dropdown-footer
     [:button.button.button-secondary.filter-dropdown-clear
      {:type "button" "data-on:click" (str "$filter." col " = []; $page = 0; @get('/transactions/rows')")} "Clear"]]]])

;; --- Active-filter chips (removable tokens for the header-funnel selections) ------------------

(defn- active-filter-chip [col field label remove-id]
  [:span.active-chip
   [:span.active-chip-field field]
   [:span.active-chip-value (or label "—")]
   [:button.active-chip-remove
    {:type "button" :aria-label (str "Remove " label " filter")
     "data-on:click" (str "$filter." col " = $filter." col ".filter(x => x !== '" remove-id "');"
                          " $page = 0; @get('/transactions/rows')")}
    "×"]])

(defn- active-filters
  "The active header-funnel selections as removable chips (#active-filters morph target,
   re-patched on every view change). Labels come from the funnel options. Hidden when empty so
   it takes no vertical space."
  [account-opts institution-opts category-opts {:keys [accounts institutions categories]}]
  (let [label-of (fn [opts id] (some #(when (= (:id %) id) (:label %)) opts))
        chips (concat
               (for [id accounts]     (active-filter-chip "account"     "Account"     (label-of account-opts id) id))
               (for [id institutions] (active-filter-chip "institution" "Institution" (label-of institution-opts id) id))
               (for [id categories]   (active-filter-chip "category"    "Category"    (label-of category-opts id) id)))]
    (into [:div.active-chips (cond-> {:id "active-filters"} (empty? chips) (assoc :hidden true))]
          chips)))

(defn- funnel-popovers [account-opts institution-opts category-opts]
  (list (funnel-popover "account" account-opts)
        (funnel-popover "institution" institution-opts)
        (funnel-popover "category" category-opts)))

(def ^:private undo-key-js
  ;; Cmd/Ctrl+Z = undo, +Shift = redo. Static literal (no server data).
  (str "(evt.metaKey || evt.ctrlKey) && (evt.key === 'z' || evt.key === 'Z')"
       " && (evt.preventDefault(), evt.shiftKey ? @post('/transactions/redo') : @post('/transactions/undo'))"))

(defn- patch-filter-feedback!
  "Re-patch every filter-dependent fragment after a view change or edit: the faceted count
   badges, the three funnel option lists (faceted counts), and the active-filter chips."
  [sse txs view-st]
  (let [acct (view/account-options txs view-st)
        inst (view/institution-options txs view-st)
        cat  (view/category-funnel-options txs view-st)]
    (d*/patch-elements! sse (counts-fragment (view/facet-counts txs view-st)))
    (d*/patch-elements! sse (r/render (funnel-list "account" acct)))
    (d*/patch-elements! sse (r/render (funnel-list "institution" inst)))
    (d*/patch-elements! sse (r/render (funnel-list "category" cat)))
    (d*/patch-elements! sse (r/render (active-filters acct inst cat view-st)))))

;; --- Row-actions menu + split-editor modal ---------------------------------

(def ^:private close-modal-js
  ;; Pure-client modal close: empty #modal-root. Used by modals without an island (the match +
  ;; review modals); the split modal's island wipes it the same way.
  "document.getElementById('modal-root').replaceChildren()")

(defn- backdrop-attrs
  "Backdrop close behaviour for an island-less modal: a click on the backdrop itself
   (not a bubbled click from inside the dialog — `evt.target === el`) closes, as does Escape."
  []
  {:role "presentation"
   "data-on:click" (str "evt.target === el && (" close-modal-js ")")
   "data-on:keydown__window" (str "evt.key === 'Escape' && (" close-modal-js ")")})

(defn- row-actions-menu
  "The single floating menu shared by every row's caret (rendered outside the table so it
   escapes overflow). $_rowMenu holds the open row's id (0 = closed); $_rowMenuSplit and
   $_rowMenuMatched carry that row's split/matched state so the items read \"Edit split\" vs
   \"Split transaction\" and \"Matched transfer\" vs \"Match transfer\". Each item @get's the
   relevant modal for $_rowMenu (the url is built before $_rowMenu is cleared)."
  []
  [:ul.row-actions-menu {:id "row-actions-menu" :role "menu" :aria-label "Row actions"
                         "data-show" "$_rowMenu"
                         "data-style" "{right: $_rowMenuX + 'px', top: $_rowMenuY + 'px'}"
                         "data-on:click__outside" "$_rowMenu = 0"
                         "data-on:keydown__window" "evt.key === 'Escape' && ($_rowMenu = 0)"}
   [:li {:role "none"}
    [:button.row-actions-item
     {:type "button" :role "menuitem"
      "data-text" "$_rowMenuSplit ? 'Edit split' : 'Split transaction'"
      "data-on:click" "@get('/transactions/' + $_rowMenu + '/split-editor'); $_rowMenu = 0"}
     "Split transaction"]]
   [:li {:role "none"}
    [:button.row-actions-item
     {:type "button" :role "menuitem"
      "data-text" "$_rowMenuMatched ? 'Matched transfer' : 'Match transfer'"
      "data-on:click" "@get('/transactions/' + $_rowMenu + '/match'); $_rowMenu = 0"}
     "Match transfer"]]])

(defn- split-editor-modal
  "The split-editor modal, patched into #modal-root by GET /transactions/:id/split-editor.
   The dialog carries the editor's data for the island (tx id, parent amount, JSON seed rows,
   already-split?); the island fills .split-rows-container, runs the live balance math
   (islands/lib/splitMath), and on save writes the JSON payload into #split-courier (whose
   change @put's). Cancel/Esc/backdrop close client-side (the island wipes #modal-root); save
   closes server-side (the PUT response re-patches #modal-root empty)."
  [tx]
  (let [tx-id (:db/id tx)
        amount (:transaction/amount tx)
        split? (boolean (seq (:transaction/splits tx)))]
    [:div {:id "modal-root"}
     [:div.modal-backdrop.split-modal-backdrop {:role "presentation"}
      [:div.modal-content.split-modal-content
       {:data-split-editor "" :data-tx-id (str tx-id) :data-amount (str amount)
        :data-seed (r/signals (view/split-editor-seed tx)) :data-split (str split?)
        :role "dialog" :aria-modal "true" :aria-labelledby "split-modal-title"}
       [:h2#split-modal-title (if split? "Edit split" "Split transaction")]
       [:div.split-modal-sub
        [:span.split-modal-payee (:transaction/payee tx)]
        [:span.numeric (fmt/amount amount)]]
       [:p.split-modal-hint
        "Divide this transaction into parts that add up to the total. Each part gets its own category."]
       [:div.split-row.split-row-head {:aria-hidden "true"}
        [:span "Amount"] [:span "Category"] [:span "Description"] [:span]]
       [:div.split-rows-container]
       [:button.split-add-button {:type "button"} "+ Add part"]
       [:div.split-remaining]
       [:div.split-modal-actions
        (if split?
          [:button.button.button-secondary.split-unsplit {:type "button"} "Un-split"]
          [:span])
        [:div.split-modal-actions-right
         [:button.button.button-secondary.split-cancel {:type "button"} "Cancel"]
         [:button.button.button-primary.split-save {:type "button" :disabled true} "Save split"]]]
       [:input {:id "split-courier" :type "hidden"
                "data-on:change" (str "$splitValue = el.value; @put('/transactions/" tx-id "/splits')")}]]]]))

(defn- match-modal
  "The transfer match/unmatch modal, patched into #modal-root by GET /transactions/:id/match.
   No island needed: a matched row shows its partner + an Unmatch button; an unmatched row
   lists candidate counterparts (each a button that @put's the confirm). Both @put's apply a
   :set-match command (so undo/redo works) and close the modal (re-patch #modal-root empty).
   Cancel/Esc/backdrop close client-side."
  [tx candidates]
  (let [tx-id (:db/id tx)
        partner (:transaction/transfer-pair tx)
        acct (fn [t] (or (get-in t [:transaction/account :account/external-name]) "—"))
        leg (fn [t static?]
              (let [body [:span.transfer-suggestion-body
                          [:span.transfer-suggestion-route (acct t)]
                          [:span.transfer-suggestion-meta
                           (if static?
                             (fmt/date (:transaction/posted-date t))
                             (str (:transaction/payee t) " · " (fmt/date (:transaction/posted-date t))))]]
                    amt [:span.transfer-suggestion-amount.numeric (fmt/amount (:transaction/amount t))]]
                (if static?
                  [:div.transfer-candidate.is-static body amt]
                  [:button.transfer-candidate
                   {:type "button" :role "listitem"
                    "data-on:click" (str "@put('/transactions/" tx-id "/match/" (:db/id t) "')")}
                   body amt])))]
    [:div {:id "modal-root"}
     [:div.modal-backdrop.transfer-modal-backdrop (backdrop-attrs)
      [:div.modal-content.transfer-modal-content
       {:role "dialog" :aria-modal "true" :aria-labelledby "match-modal-title"}
       (if partner
         (list
          [:h2#match-modal-title "Matched transfer"]
          [:p.transfer-modal-hint "This transaction is linked to its counterpart on another account."]
          (leg partner true)
          [:div.transfer-modal-actions
           [:button.button.button-secondary.transfer-unmatch-button
            {:type "button" "data-on:click" (str "@put('/transactions/" tx-id "/unmatch')")}
            "Unmatch transfer"]
           [:button.button.button-secondary {:type "button" "data-on:click" close-modal-js} "Close"]])
         (list
          [:h2#match-modal-title "Match transfer"]
          [:p.transfer-modal-hint "Pick the matching transaction on another account."]
          (if (empty? candidates)
            [:div.transfer-empty "No matching transactions found."]
            (into [:div.transfer-suggestion-list {:role "list"}] (map #(leg % false) candidates)))
          [:div.transfer-modal-actions
           [:span]
           [:button.button.button-secondary {:type "button" "data-on:click" close-modal-js} "Close"]]))]]]))

;; --- Bulk transfer-review modal --------------------------------------------

(defn- suggestion-row
  "One suggested pair: Confirm links it; \"Not a transfer\" rejects it. Both @put a review
   action that refreshes #review-list in place (the acted-on pair drops out)."
  [{:keys [outflow inflow amount day-diff]}]
  (let [out-id (:db/id outflow)
        in-id (:db/id inflow)
        acct (fn [t] (or (get-in t [:transaction/account :account/external-name]) "—"))]
    [:div.transfer-suggestion
     [:button.button.button-primary.transfer-confirm-button
      {:type "button" "data-on:click" (str "@put('/transactions/review/" out-id "/confirm/" in-id "')")}
      "Confirm"]
     [:div.transfer-suggestion-body
      [:div.transfer-suggestion-route
       [:span (acct outflow)] [:span.transfer-arrow "→"] [:span (acct inflow)]]
      [:div.transfer-suggestion-meta
       (str (fmt/date (:transaction/posted-date outflow)) " · "
            day-diff (if (= 1 day-diff) " day apart" " days apart"))]]
     [:span.transfer-suggestion-amount.numeric (fmt/amount amount)]
     [:button.transfer-reject-button
      {:type "button" "data-on:click" (str "@put('/transactions/review/" out-id "/reject/" in-id "')")}
      "Not a transfer"]]))

(defn- review-list
  "The suggestion list (its own #review-list id is the morph target so a confirm/reject can
   re-patch the now-smaller list in place without closing the modal)."
  [suggestions]
  [:div {:id "review-list"}
   (if (empty? suggestions)
     [:div.transfer-empty "No suggested transfers — you're all caught up."]
     (into [:div.transfer-suggestion-list {:role "list"}] (map suggestion-row suggestions)))])

(defn- review-modal
  "GET /transactions/review-transfers patches this into #modal-root: the auto-suggested transfer
   pairs, each confirmable/rejectable in place. No island — actions are plain @put's."
  [suggestions]
  [:div {:id "modal-root"}
   [:div.modal-backdrop.transfer-modal-backdrop (backdrop-attrs)
    [:div.modal-content.transfer-modal-content
     {:role "dialog" :aria-modal "true" :aria-labelledby "review-modal-title"}
     [:h2#review-modal-title "Review transfers"]
     [:p.transfer-modal-hint
      "Likely transfer pairs across your accounts. Confirm the real ones; mark the rest \"Not a transfer\"."]
     (review-list suggestions)
     [:div.transfer-modal-actions
      [:span]
      [:button.button.button-secondary {:type "button" "data-on:click" close-modal-js} "Done"]]]]])

;; --- Category rollup pane --------------------------------------------------
;; Whole-month breakdown (web.view/category-rollup) rendered beside the table. A row's filter is
;; "active" when $filter.category exactly matches its ids (or $uncat for the Uncategorized row);
;; clicking toggles that filter (set/clear) — pure reuse of the funnel filter signals. The pane
;; reflects edits (a recategorize moves money between rows), so it's re-patched by id on edits,
;; not on filter/sort changes (whole-month → a filter change leaves the breakdown unchanged; the
;; active highlight updates client-side via data-class).

(defn- rollup-active-expr [{:keys [uncategorized? ids]}]
  (if uncategorized?
    "$uncat"
    (str "$filter.category.length === " (count ids)
         (apply str (map #(str " && $filter.category.includes('" % "')") ids)))))

(defn- rollup-click-expr [{:keys [uncategorized? ids] :as row}]
  ;; Toggle this row's filter. The ternary branches are single expressions, so the assignments
  ;; inside them are joined with the comma operator (a `;` there is a JS syntax error); the
  ;; trailing page-reset + @get are `;`-separated statements at the top level.
  (let [active   (rollup-active-expr row)
        set-it   (if uncategorized?
                   "$uncat = true, $filter.category = []"
                   (str "$filter.category = [" (str/join ", " (map #(str "'" % "'") ids)) "], $uncat = false"))
        clear-it (if uncategorized? "$uncat = false" "$filter.category = []")]
    (str active " ? (" clear-it ") : (" set-it "); $page = 0; @get('/transactions/rows')")))

(defn- rollup-row [{:keys [name depth group?] :as row}]
  [:li {:class (str/trim (str "rollup-row rollup-row--depth" depth (when group? " rollup-row--group")))
        "data-class" (str "{'is-active': " (rollup-active-expr row) "}")}
   [:button.rollup-row-button
    {:type "button" "data-on:click" (rollup-click-expr row)
     "data-attr" (str "{'aria-pressed': " (rollup-active-expr row) "}")}
    [:span.rollup-row-name
     (when (= 1 depth) [:span.rollup-branch {:aria-hidden "true"} "└"])
     name]
    [:span.rollup-amount (fmt/amount (:amount row))]]])

(def ^:private rollup-section-labels {:income "Income" :expense "Expenses" :transfer "Transfers"})

(defn- rollup-section [{:keys [type rows total]}]
  [:section.rollup-section
   [:h3.rollup-section-title (rollup-section-labels type)]
   (into [:ul.rollup-rows] (map rollup-row rows))
   [:div.rollup-subtotal
    [:span.rollup-row-name (str (rollup-section-labels type) " total")]
    [:span.rollup-amount (fmt/amount total)]]])

(defn- rollup-pane [{:keys [income expenses transfers grand-total]}]
  (let [sections (filter #(seq (:rows %)) [income expenses transfers])]
    [:aside.rollup-pane {:id "category-rollup" :aria-label "Category summary"}
     [:div.rollup-scroll
      (if (empty? sections)
        [:p.rollup-empty "No activity to summarize."]
        (map rollup-section sections))
      [:div.rollup-net
       [:span.rollup-net-label "Net"]
       [:span {:class (str "rollup-amount " (if (neg? grand-total) "negative" "positive"))}
        (fmt/amount grand-total)]]]]))

(defn- rollup-fragment
  "The #category-rollup pane for `txs` (whole month) + `categories` — rendered on load and
   re-patched after edits."
  [txs categories]
  (rollup-pane (view/category-rollup txs categories)))

(defn page
  "GET / — full page. Seeds the view-state from the URL; a fresh load clears lingering."
  [{:keys [db-conn]}]
  (fn [req]
    (commands/clear-linger! auth/user-id)
    (let [m (month/parse (get-in req [:query-params "month"]))
          month-str (month/serialize m)
          txs (db-transactions/list-for-month db-conn month-str)
          stats (db-stats/entity-counts db-conn)
          categories (db-categories/list-all db-conn)
          view-st (vs/query->view-state (:query-params req))
          result (view/view txs view-st)
          counts (view/facet-counts txs view-st)
          acct (view/account-options txs view-st)
          inst (view/institution-options txs view-st)
          cat  (view/category-funnel-options txs view-st)]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body
       (layout/document
        {:title "Finance Aggregator"
         :islands ["combobox" "url" "grid-nav" "resize" "split-editor" "modal"]
         :signals (vs/client-signals view-st month-str result (:query-params req))}
        [:div.container.container--workspace {"data-on:keydown__window" undo-key-js}
         (shell/masthead {:active :transactions :stats stats})
         (error-banner)
         (sr-status)
         [:div.transactions-layout
          [:div.card
           (toolbar m counts)
           (active-filters acct inst cat view-st)
           (if (empty? txs)
             (empty-state)
             (list (table (:rows result)) (pagination-bar result)))]
          (rollup-fragment txs categories)]
         (when (seq txs) (list (funnel-popovers acct inst cat) (row-actions-menu)))
         (category-options categories)
         (url-sync)
         ;; Patched by GET /transactions/:id/split-editor; emptied again on close/save.
         [:div {:id "modal-root"}]])})))

(defn rows
  "GET /transactions/rows — a pure view change: clear lingering, re-run the view, morph the tbody +
   pagination bar, patch $page back to the clamped value."
  [{:keys [db-conn]}]
  (fn [req]
    (commands/clear-linger! auth/user-id)
    (let [signals (r/read-signals req)
          month-str (month/serialize (month/parse (:month signals)))
          txs (db-transactions/list-for-month db-conn month-str)
          view-st (vs/signals->view-state signals)
          result (view/view txs view-st)]
      (hk/->sse-response
       req
       {hk/on-open
        (fn [sse]
          ;; A view change (filter/sort/paginate) dismisses any error a prior action left up.
          (d*/patch-elements! sse (r/render (error-banner)))
          ;; Announce the filtered result size to screen readers.
          (d*/patch-elements! sse (r/render (sr-status (str (:total result)
                                                            (if (= 1 (:total result)) " transaction" " transactions")))))
          (d*/patch-elements! sse (r/render (tbody (:rows result))))
          (d*/patch-elements! sse (r/render (pagination-bar result)))
          (patch-filter-feedback! sse txs view-st)
          (d*/patch-signals! sse (r/signals {:page (:page result)}))
          (d*/close-sse! sse))}))))

(defn- edit-response
  "Shared SSE response after any edit/undo/redo: re-render the tbody (lingering the touched
   rows), the pagination bar, the server-authoritative counts, and the undo/redo controls.
   `:close-modal?` also re-patches #modal-root empty (a split save closes its modal);
   `:after-patch` (fn of sse) patches an extra fragment (the review modal refreshes its list
   in place instead of closing)."
  [db-conn req signals & {:keys [close-modal? after-patch]}]
  (let [user auth/user-id
        month-str (month/serialize (month/parse (:month signals)))
        txs (db-transactions/list-for-month db-conn month-str)
        view-st (vs/signals->view-state signals)
        {:keys [stale-ids] :as result} (view/view-with-linger txs view-st (commands/linger user))
        counts (view/facet-counts txs view-st)]
    (hk/->sse-response
     req
     {hk/on-open
      (fn [sse]
        ;; A successful edit clears any error banner a prior failed action left up.
        (d*/patch-elements! sse (r/render (error-banner)))
        ;; Announce the new counts to screen readers (the morphed badges are silent to them).
        (d*/patch-elements! sse (r/render (sr-status (review-status-message counts))))
        (d*/patch-elements! sse (r/render (tbody (:rows result) stale-ids)))
        (d*/patch-elements! sse (r/render (pagination-bar result)))
        (patch-filter-feedback! sse txs view-st)
        (d*/patch-elements! sse (r/render (undo-redo-controls)))
        ;; A recategorize/split moves money between rollup rows, so re-patch the whole-month pane.
        (d*/patch-elements! sse (r/render (rollup-fragment txs (db-categories/list-all db-conn))))
        (when close-modal? (d*/patch-elements! sse (r/render [:div {:id "modal-root"}])))
        (when after-patch (after-patch sse))
        (d*/patch-signals! sse (r/signals {:page (:page result)}))
        (d*/close-sse! sse))})))

(defn- error-response
  "SSE response for a failed mutation: surface the message in the dismissable error bar and
   close any open modal (so the bar isn't hidden behind a backdrop). The mutation threw
   before its command was recorded and before any datom was written (every db mutation
   validates up front), so the table already reflects the true state — nothing else to
   re-render."
  [req msg]
  (hk/->sse-response
   req
   {hk/on-open
    (fn [sse]
      (d*/patch-elements! sse (r/render (error-banner msg)))
      (d*/patch-elements! sse (r/render [:div {:id "modal-root"}]))
      (d*/close-sse! sse))}))

(defn- handle-edit
  "Run an edit handler body (a thunk returning its SSE response); if a mutation throws an
   ex-info (validation / :conflict / :not-found), surface its message in the error bar
   instead of letting it escape to the JSON exception middleware — which a Datastar SSE
   client receives as a non-event-stream response, so no fragment patches and the user
   sees nothing change. Unexpected (non-ex-info) errors keep the default 500 + logging."
  [req thunk]
  (try (thunk)
       (catch clojure.lang.ExceptionInfo e
         (error-response req (ex-message e)))))

(defn toggle-reviewed
  "PUT /transactions/:id/reviewed/:v — record + apply a reviewed command, then re-render."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [tx-id (-> req :path-params :id parse-long)
             after (= "true" (-> req :path-params :v))]
         (commands/apply! db-conn auth/user-id
                          {:type :set-reviewed :tx-id tx-id :before (not after) :after after
                           :label (if after "Marked reviewed" "Marked unreviewed")})
         (edit-response db-conn req (r/read-signals req)))))))

(defn set-description
  "PUT /transactions/:id/description — record + apply an inline-description-edit command (the new
   value rides in the $editValue courier signal), then re-render."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [tx-id (-> req :path-params :id parse-long)
             signals (r/read-signals req)
             before (db-transactions/user-description db-conn tx-id)
             after (str/trim (or (:editValue signals) ""))]
         (commands/apply! db-conn auth/user-id
                          {:type :set-description :tx-id tx-id :before before :after after
                           :label "Edited description"})
         (edit-response db-conn req signals))))))

(defn set-category
  "PUT /transactions/:id/category — record + apply an :update-category command (the chosen id rides
   in the $catValue courier; empty = clear), then re-render (counts move)."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [tx-id (-> req :path-params :id parse-long)
             signals (r/read-signals req)
             before (db-transactions/category-id db-conn tx-id)
             after (vs/parse-category-value (:catValue signals))]
         (commands/apply! db-conn auth/user-id
                          {:type :set-category :tx-id tx-id :before before :after after
                           :label "Recategorized"})
         (edit-response db-conn req signals))))))

(defn split-editor
  "GET /transactions/:id/split-editor — render the split-editor modal for one transaction into
   #modal-root. A pure read (no command, no lingering change)."
  [{:keys [db-conn]}]
  (fn [req]
    (let [tx (db-transactions/by-id db-conn (-> req :path-params :id parse-long))]
      (hk/->sse-response
       req
       {hk/on-open
        (fn [sse]
          (d*/patch-elements! sse (r/render (split-editor-modal tx)))
          (d*/close-sse! sse))}))))

(defn set-splits
  "PUT /transactions/:id/splits — record + apply a :set-splits command (the new parts ride in
   the $splitValue courier as JSON; [] un-splits), then re-render and close the modal. Captures
   the prior parts as :before so undo restores them."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [tx-id (-> req :path-params :id parse-long)
             signals (r/read-signals req)
             before (db-transactions/current-splits db-conn tx-id)
             after (vs/parse-splits-value (:splitValue signals))]
         (commands/apply! db-conn auth/user-id
                          {:type :set-splits :tx-id tx-id :before before :after after
                           :label (cond (empty? after)  "Un-split"
                                        (seq before)     "Edited split"
                                        :else            "Split transaction")})
         (edit-response db-conn req signals :close-modal? true))))))

(defn match-editor
  "GET /transactions/:id/match — render the transfer match/unmatch modal into #modal-root.
   A pure read (matched → partner + Unmatch; unmatched → match candidates)."
  [{:keys [db-conn]}]
  (fn [req]
    (let [tx-id (-> req :path-params :id parse-long)
          tx (db-transactions/by-id db-conn tx-id)
          candidates (when-not (:transaction/transfer-pair tx)
                       (db-transfers/match-candidates db-conn tx-id))]
      (hk/->sse-response
       req
       {hk/on-open
        (fn [sse]
          (d*/patch-elements! sse (r/render (match-modal tx candidates)))
          (d*/close-sse! sse))}))))

(defn confirm-match
  "PUT /transactions/:id/match/:partner — link the two legs as a transfer (a :set-match command,
   so undo unlinks), then re-render and close the modal."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [tx-id (-> req :path-params :id parse-long)
             partner (-> req :path-params :partner parse-long)]
         (commands/apply! db-conn auth/user-id
                          {:type :set-match :tx-id tx-id :before nil :after partner :label "Matched transfer"})
         (edit-response db-conn req (r/read-signals req) :close-modal? true))))))

(defn unmatch
  "PUT /transactions/:id/unmatch — remove the transfer link (a :set-match command capturing the
   prior partner as :before, so undo relinks), then re-render and close the modal."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [tx-id (-> req :path-params :id parse-long)
             before (get-in (db-transactions/by-id db-conn tx-id) [:transaction/transfer-pair :db/id])]
         (commands/apply! db-conn auth/user-id
                          {:type :set-match :tx-id tx-id :before before :after nil :label "Unmatched transfer"})
         (edit-response db-conn req (r/read-signals req) :close-modal? true))))))

(defn review-transfers
  "GET /transactions/review-transfers — render the bulk transfer-review modal (auto-suggested
   pairs) into #modal-root."
  [{:keys [db-conn]}]
  (fn [req]
    (let [suggestions (db-transfers/suggest-matches db-conn)]
      (hk/->sse-response
       req
       {hk/on-open
        (fn [sse]
          (d*/patch-elements! sse (r/render (review-modal suggestions)))
          (d*/close-sse! sse))}))))

(defn- refresh-review-list!
  "Re-patch #review-list with the recomputed suggestions (after a confirm/reject, the acted-on
   pair drops out and the modal stays open)."
  [db-conn sse]
  (d*/patch-elements! sse (r/render (review-list (db-transfers/suggest-matches db-conn)))))

(defn review-confirm
  "PUT /transactions/review/:out/confirm/:in — confirm a suggested pair (:set-match command),
   then re-render the table + refresh the suggestion list in place."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [out (-> req :path-params :out parse-long)
             in (-> req :path-params :in parse-long)]
         (commands/apply! db-conn auth/user-id
                          {:type :set-match :tx-id out :before nil :after in :label "Matched transfer"})
         (edit-response db-conn req (r/read-signals req)
                        :after-patch (fn [sse] (refresh-review-list! db-conn sse))))))))

(defn review-reject
  "PUT /transactions/review/:a/reject/:b — reject a suggested pair (:reject-match command, so
   undo un-rejects), then re-render the table + refresh the suggestion list in place."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [a (-> req :path-params :a parse-long)
             b (-> req :path-params :b parse-long)]
         (commands/apply! db-conn auth/user-id
                          {:type :reject-match :tx-id a :partner b :before false :after true :label "Rejected transfer"})
         (edit-response db-conn req (r/read-signals req)
                        :after-patch (fn [sse] (refresh-review-list! db-conn sse))))))))

(defn undo
  "POST /transactions/undo — reverse the last edit (keeping the row lingering), then re-render."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (commands/undo! db-conn auth/user-id)
       (edit-response db-conn req (r/read-signals req))))))

(defn redo
  "POST /transactions/redo — re-apply the last undone edit, then re-render."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (commands/redo! db-conn auth/user-id)
       (edit-response db-conn req (r/read-signals req))))))

(ns finance-aggregator.web.pages.transactions-view
  "Pure hiccup rendering for the transactions workspace — every fragment the route
   handlers (finance-aggregator.web.pages.transactions) render on load and SSE-patch.
   No I/O: data (txs / view-state / counts / funnel options / rollup) in, hiccup out.
   The data logic lives in the pure web.view + web.view-state; the HTTP/SSE
   orchestration lives in the handler namespace, which :refers the fragments here."
  (:require
   [clojure.string :as str]
   [finance-aggregator.web.format :as fmt]
   [finance-aggregator.web.month :as month]
   [finance-aggregator.web.render :as r]
   [finance-aggregator.web.shell :as shell]
   [finance-aggregator.web.view-state :as vs]))

;; ---------------------------------------------------------------------------
;; Columns
;; ---------------------------------------------------------------------------
;; The column config + view-state codec live in web.view-state (pure, tested). This page
;; consumes `vs/columns` (render order/widths/headers) and `vs/hideable-columns` (the picker).

(defn table-hide-class []
  ;; Static Datastar value for the table's `data-class`: flips the per-column `hide-<id>`
  ;; classes off the `cols.<id>` signals, plus `hide-posted` off `$showPosted` (the inline
  ;; posted-date hint — a display option living in the same View menu). No data manipulation;
  ;; the ids/signals are render-time literals.
  (str "{"
       (str/join ", " (conj (mapv (fn [[id _]] (str "'hide-" id "': !$cols." id)) vs/hideable-columns)
                            "'hide-posted': !$showPosted"))
       "}"))

(declare undo-redo-controls column-picker) ; defined later, used by the toolbar/table

;; --- Toolbar / row icons (inline SVG, sized by CSS) ------------------------
;; Stroke icons centred by the button's flex box — no font-glyph baseline to fight, so the
;; toolbar carets/arrows and the row caret sit dead-centre and read as one controlled set.

(defn icon [& body]
  (into [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"
               :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden "true"}]
        body))

(defn chevron-left   [] (icon [:polyline {:points "15 18 9 12 15 6"}]))
(defn chevron-right  [] (icon [:polyline {:points "9 18 15 12 9 6"}]))
(defn chevron-down   [] (icon [:polyline {:points "6 9 12 15 18 9"}]))
(defn chevrons-left  [] (icon [:polyline {:points "11 17 6 12 11 7"}] [:polyline {:points "18 17 13 12 18 7"}]))
(defn chevrons-right [] (icon [:polyline {:points "13 17 18 12 13 7"}] [:polyline {:points "6 17 11 12 6 7"}]))
(defn undo-icon     [] (icon [:path {:d "M9 14 4 9l5-5"}] [:path {:d "M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5 5.5 5.5 0 0 1-5.5 5.5H11"}]))
(defn redo-icon     [] (icon [:path {:d "m15 14 5-5-5-5"}] [:path {:d "M20 9H9.5A5.5 5.5 0 0 0 4 14.5 5.5 5.5 0 0 0 9.5 20H13"}]))
(defn split-icon    [] (icon [:polyline {:points "5 4 5 9 12 14 12 20"}] [:polyline {:points "19 4 19 9 12 14"}]))

;; ---------------------------------------------------------------------------
;; Rows (every transaction — a split part included — is one editable row)
;; ---------------------------------------------------------------------------

(defn account-name
  "A transaction's account display name (its external name), or \"—\". Works on any pulled
   tx/leg carrying :transaction/account (a row, a transfer partner, a suggestion leg)."
  [tx]
  (or (get-in tx [:transaction/account :account/external-name]) "—"))

(defn institution-name
  "A transaction's institution display name, or \"—\"."
  [tx]
  (or (get-in tx [:transaction/account :account/institution :institution/name]) "—"))

(defn amount-span
  "Signed amount with sign class — one rule for every row: 0 reads negative."
  [amt]
  [:span {:class (str "numeric " (if (pos? amt) "positive" "negative"))} (fmt/amount amt)])

(defn reconciled-checkbox
  "A server-confirmed toggle (`change` @put's the new state in the path — `el.checked`,
   no per-row signal). Every row's checkbox is live: a split part reconciles itself like
   any transaction."
  [tx-id reconciled?]
  [:input {:type "checkbox" :class "reconciled-checkbox" :checked (boolean reconciled?)
           "data-on:change" (str "@put('/transactions/" tx-id "/reconciled/' + el.checked)")}])

(defn row-class [base stale?]
  (str/trim (str base (when stale? " is-stale"))))

;; --- Inline description edit (server-confirmed) ----------------------------
;; Click the button → class-swap to an input (reusing the carried-over
;; .description-cell.editing CSS). Enter/blur optimistically set the button text, copy the
;; input value into the single $editValue courier signal, and @put it; the server applies a
;; command and morphs the row back (reconciling blank → imported description). Escape reverts
;; the input to its server value. No per-row signals — the input holds its own text.

(def desc-open-js
  (str "el.closest('.description-cell').classList.add('editing');"
       " el.nextElementSibling.focus(); el.nextElementSibling.select()"))

(defn desc-commit-js [tx-id]
  (str "el.previousElementSibling.textContent = el.value || '—',"
       " $editValue = el.value, @put('/transactions/" tx-id "/description'),"
       " el.closest('.description-cell').classList.remove('editing')"))

(defn desc-keydown-js [tx-id]
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

(defn desc-blur-js [tx-id]
  ;; A genuine click-away commits; Enter/Escape already removed `editing`, so their trailing
  ;; blur is a no-op (guards the double-commit).
  (str "el.closest('.description-cell').classList.contains('editing') && (" (desc-commit-js tx-id) ")"))

(defn editable-description [tx-id text]
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

(defn editable-category [tx-id category]
  (list
   [:button.category-button.combo-cell
    {:type "button" :tabindex "-1" :id (str "cat-view-tx" tx-id) :aria-haspopup "listbox"}
    (or (:category/name category) "Uncategorized")]
   [:input {:type "hidden" :id (str "cat-courier-tx" tx-id)
            "data-on:change" (str "$catValue = el.value; @put('/transactions/" tx-id "/category')")}]))

(defn transfer-status-marker
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

(defn category-cell-inner
  "The category cell's content: the editable combobox button + its hidden courier, with the
   transfer marker tucked in beside them (wrapped in .category-cell-row) for transfer rows.
   The hidden input stays a sibling of the combo button either way, so the combobox island's
   `cell.parentElement.querySelector` still finds it."
  [tx]
  (let [[btn hidden] (editable-category (:db/id tx) (:transaction/category tx))]
    (if-let [marker (transfer-status-marker tx)]
      [:div.category-cell-row btn marker hidden]
      (list btn hidden))))

(defn category-options
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

(defn grid-cell
  "Attrs marking a <td> as a keyboard-navigable grid cell (txId:tx:col, consumed by the
   gridNavigation reducer). Every row is a plain navigable row — a split part included."
  [tx-id col]
  {:data-cell (str tx-id ":tx:" col) :role "gridcell" :tabindex "-1"})

;; --- Row-actions menu ------------------------------------------------------
;; A trailing always-on chrome column (NOT a hideable data column, so it stays out of the
;; cols/URL/picker machinery). A quiet caret opens one shared floating menu (row-actions-menu,
;; rendered outside the table so it escapes overflow), carrying the row's id + split state +
;; position into the ephemeral _rowMenu signals.

(defn row-actions-cell
  "The caret cell. $_rowMenuSplit = \"this row is a split part\" (the menu item reads
   'Edit split'); $_rowMenuSplitTarget = the id the split editor must open on — the
   PARENT for a part (depth is 1), the row itself otherwise. `split-parent-id` is the
   pulled parent's id, nil on a non-part row."
  [tx-id split-parent-id matched? manual?]
  [:td.actions-cell
   [:div.row-actions
    [:button.row-actions-trigger
     {:type "button" :aria-haspopup "menu" :aria-label "Row actions"
      "data-attr" (str "{'aria-expanded': $_rowMenu === " tx-id " ? 'true' : 'false'}")
      "data-on:click__stop"
      (str "$_rowMenu = ($_rowMenu === " tx-id " ? 0 : " tx-id ");"
           " $_rowMenuSplit = " (some? split-parent-id) ";"
           " $_rowMenuSplitTarget = " (or split-parent-id tx-id) ";"
           " $_rowMenuMatched = " (boolean matched?) ";"
           " $_rowMenuManual = " (boolean manual?) ";"
           " $_rowMenuX = Math.max(8, window.innerWidth - el.getBoundingClientRect().right);"
           " $_rowMenuY = el.getBoundingClientRect().bottom + 4")}
     (chevron-right)]]])

(defn date-cell
  "The date column: the transaction date (when the purchase happened), and — only when the
   EFFECTIVE posted date differs from it — a muted inline '· posted <date>' on the same line,
   mirroring how a bank/card statement shows both a transaction and a post date.
   :transaction/effective-posted-date (data.ledger/effective-posted-date) is the manual
   override when the user has set one, else the provider's posted-date guess, else the plain
   transaction date; reconciliation buckets by it, so the hint always shows what actually
   drives that, not just the raw imported guess. When a manual override is set
   (:transaction/user-posted-date present) the hint carries `posted-hint--manual` + a title, so
   a user-set date reads visibly different from the provider's own. Both dates are short (no
   year — the period header carries it); a row with no distinct effective date has
   date == effective, so no hint shows."
  [{:transaction/keys [date posted-date effective-posted-date user-posted-date]}]
  (let [shown (or date posted-date)]
    [:td.date-cell
     [:span.numeric (fmt/date-short shown)]
     (when (and effective-posted-date shown (not= shown effective-posted-date))
       [:span (cond-> {:class "posted-hint"}
                user-posted-date (assoc :class "posted-hint posted-hint--manual"
                                        :title "Posted date set manually"))
        [:span.posted-sep "·"] "posted " (fmt/date-short effective-posted-date)])]))

(defn split-marker
  "Payee-cell marker on a split PART row: a quiet icon button linking the part back to
   its family — it opens the split editor on the PARENT (the only place amounts change).
   `parent` is the pulled :transaction/split-parent map. `__stop` so the click doesn't
   also reach the cell/grid-nav handlers; tabindex -1 keeps it out of the tab order
   (the row-actions menu is the keyboard path)."
  [parent]
  [:button.split-marker
   {:type "button" :tabindex "-1"
    :aria-label "Part of a split — view or edit"
    :title (str "Part of a split of " (fmt/amount (:transaction/amount parent)))
    "data-on:click__stop" (str "@get('/transactions/" (:db/id parent) "/split-editor')")}
   (split-icon)])

(defn split-drift-badge
  "Amount-cell warning on a part whose family no longer sums to the imported total
   (:transaction/split-drift — a re-sync changed the parent's amount). Surfaced, never
   silently corrected; the editor is where the user re-balances."
  []
  [:span.split-drift-badge
   {:role "img"
    :aria-label "Split no longer adds up — the imported total changed"
    :title "Split no longer adds up — the imported total changed"}
   "⚠"])

(defn normal-row
  "One transaction as one row. A split part renders as a normal row plus a payee-cell
   marker back to its family (and, on drift, an amount-cell warning) — every column
   stays live exactly like any other row's."
  [stale? {:transaction/keys [payee effective-description amount reconciled
                              split-parent split-drift] :as tx}]
  [:tr {:role "row" :class (row-class (if split-parent "is-split-part" "") stale?)}
   (date-cell tx)
   [:td (account-name tx)]
   [:td (institution-name tx)]
   [:td.payee-cell payee (when split-parent (split-marker split-parent))]
   [:td.description-cell (grid-cell (:db/id tx) "description") (editable-description (:db/id tx) effective-description)]
   [:td.amount-cell (amount-span amount) (when split-drift (split-drift-badge))]
   [:td.category-cell (grid-cell (:db/id tx) "category") (category-cell-inner tx)]
   [:td.reconciled-cell (grid-cell (:db/id tx) "reconciled") (reconciled-checkbox (:db/id tx) reconciled)]
   (row-actions-cell (:db/id tx) (:db/id split-parent) (some? (:transaction/transfer-pair tx))
                     (= :manual (:transaction/provider tx)))])

(defn tbody
  ([rows] (tbody rows #{}))
  ([rows stale-ids]
   (into [:tbody {:id "tx-tbody"}]
         (map #(normal-row (contains? stale-ids (:db/id %)) %) rows))))

;; ---------------------------------------------------------------------------
;; Header (sortable columns; cycle asc → desc → cleared, server-side)
;; ---------------------------------------------------------------------------

(defn sort-click-js [col]
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

(def funnel-cols #{"account" "institution" "category"})

(defn funnel-icon []
  [:svg.th-filter-icon {:width "12" :height "12" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
                        :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden "true"}
   [:polygon {:points "22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"}]])

(defn funnel-open-js [col]
  (str "$_openFunnel = ($_openFunnel === '" col "' ? '' : '" col "'); $_funnelQuery = ''; "
       "$_funnelX = Math.max(8, Math.min(el.getBoundingClientRect().left, window.innerWidth - 300)); "
       "$_funnelY = el.getBoundingClientRect().bottom + 4"))

(defn funnel-button [col label]
  (let [active (str "$filter." col ".filter(x=>x).length")]
    [:button.th-filter-btn
     {:type "button" :aria-haspopup "dialog" :aria-label (str "Filter " label) :aria-expanded "false"
      "data-on:click__stop" (funnel-open-js col)
      "data-attr" (str "{'aria-expanded': $_openFunnel === '" col "' ? 'true' : 'false'}")
      "data-class" (str "{'is-active': " active " > 0}")}
     (funnel-icon)
     [:span.th-filter-count {"data-show" (str active " > 0") "data-text" active}]]))

(defn resize-handle [] [:div.col-resize-handle {:aria-hidden "true"}])

(defn th [{:keys [id label sortable min protected]}]
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

(defn table [rows]
  ;; .table-resizable = fixed layout; the <colgroup> widths are authoritative and the resize
  ;; island refines them (auto-fit on load + drag handles). Density/sticky come with the class.
  [:div.transactions-table-scroll {:tabindex "0"}
   [:table.table.table-resizable {:role "grid" "data-class" (table-hide-class)}
    [:colgroup
     (concat (for [{:keys [w]} vs/columns] [:col {:style (str "width:" w "px")}])
             [[:col.actions-col {:aria-hidden "true"}]])]
    [:thead [:tr (concat (map th vs/columns) [[:th.actions-th {:aria-hidden "true"}]])]]
    (tbody rows)]])

;; ---------------------------------------------------------------------------
;; Toolbar
;; ---------------------------------------------------------------------------

(defn period-label
  "The dateline label: the whole month (\"January 2025\") normally, or — when the table is
   lensed to a statement period — the actual narrowed span (`from`/`to` Dates, which may cross
   a calendar-month boundary, e.g. \"Dec 28 – Jan 27, 2025\")."
  ([m] (month/display m))
  ([m from to] (if (and from to) (fmt/date-span from to) (month/display m))))

(defn period-display
  "The dateline element, id'd so the rows handler can re-patch it when narrowing changes."
  [label]
  [:span#period-navigator-display.month-navigator-display label])

(defn month-navigator
  ;; Full navigation (anchor). URL-state write-back (preserving filters across month
  ;; change) isn't wired yet, so a month change resets the view (and clears any narrowing).
  ([m] (month-navigator m nil nil))
  ([m from to]
   [:div.month-navigator
    [:div.month-navigator-controls
     [:a.button.button-secondary.month-nav-button
      {:href (str "/?month=" (month/serialize (month/prev-month m))) :title "Previous month"
       :aria-label "Previous month"} (chevron-left)]
     (period-display (period-label m from to))
     [:a.button.button-secondary.month-nav-button
      {:href (str "/?month=" (month/serialize (month/next-month m))) :title "Next month"
       :aria-label "Next month"} (chevron-right)]]]))

(defn search-box []
  [:div.table-search
   [:svg.table-search-icon {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"
                            :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden "true"}
    [:circle {:cx "11" :cy "11" :r "8"}] [:line {:x1 "21" :y1 "21" :x2 "16.65" :y2 "16.65"}]]
   [:input.table-search-input
    {:type "search" :placeholder "Search payee, description…" :aria-label "Search transactions"
     "data-bind" "search"
     "data-on:input__debounce.300ms" "$page = 0; @get('/transactions/rows')"}]])

(defn scope-toggle [{:keys [unreconciled total]}]
  [:div.scope-toggle {:role "group" :aria-label "Reconcile scope"}
   [:button.scope-toggle-btn
    {"type" "button" "data-on:click" "$scope = 'to-reconcile'; $page = 0; @get('/transactions/rows')"
     "data-class" "{'is-active': $scope === 'to-reconcile'}"}
    "To reconcile" [:span#count-unreconciled.filter-count unreconciled]]
   [:button.scope-toggle-btn
    {"type" "button" "data-on:click" "$scope = 'all'; $page = 0; @get('/transactions/rows')"
     "data-class" "{'is-active': $scope === 'all'}"}
    "All" [:span#count-total.filter-count total]]])

(defn count-chip [label signal span-id count]
  [:button.count-chip
   {"type" "button"
    "data-on:click" (str "$" signal " = !$" signal "; $page = 0; @get('/transactions/rows')")
    "data-class" (str "{'is-active': $" signal "}")}
   label [:span.filter-count {:id span-id} count]])

(defn toolbar [m counts undo]
  [:div.toolbar
   [:div.toolbar-controls
    (month-navigator m)
    [:span.toolbar-divider {:aria-hidden "true"}]
    (search-box)
    (scope-toggle counts)
    (count-chip "Uncategorized" "uncat" "count-uncategorized" (:uncategorized counts))
    (count-chip "Hide transfers" "hideTransfers" "count-transfers" (:transfers-hidden counts))]
   [:div.toolbar-actions
    [:button.button.button-secondary.add-transaction-button
     {:type "button" :aria-haspopup "dialog" "data-on:click" "@get('/transactions/manual/new')"}
     "Add transaction"]
    [:button.button.button-secondary.filter-button
     {:type "button" :aria-haspopup "dialog" "data-on:click" "@get('/transactions/review-transfers')"}
     "Review transfers"]
    (undo-redo-controls undo) (column-picker)]])

;; ---------------------------------------------------------------------------
;; Pagination (fully server-rendered each response → disabled states stay correct)
;; ---------------------------------------------------------------------------

(defn nav-btn [{:keys [title js disabled?]} glyph]
  [:button (cond-> {:class "button button-secondary pagination-nav-button"
                    :type "button" :title title :aria-label title
                    "data-on:click" js}
             disabled? (assoc :disabled true))
   glyph])

(defn pagination-bar [{:keys [page page-count page-size]}]
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
      (nav-btn {:title "First page"    :disabled? first? :js "$page = 0; @get('/transactions/rows')"} (chevrons-left))
      (nav-btn {:title "Previous page" :disabled? first? :js "$page = Math.max(0, $page - 1); @get('/transactions/rows')"} (chevron-left))
      [:span.pagination-status (str "Page " (inc page) " of " page-count)]
      (nav-btn {:title "Next page" :disabled? last? :js "$page = $page + 1; @get('/transactions/rows')"} (chevron-right))
      (nav-btn {:title "Last page" :disabled? last? :js (str "$page = " (dec page-count) "; @get('/transactions/rows')")} (chevrons-right))]]))

;; ---------------------------------------------------------------------------
;; Lingering + edit fragments
;; ---------------------------------------------------------------------------

(defn counts-fragment
  "The toolbar count badges as a hiccup fragment (each span morphed by id) — re-patched after
   an edit, since reconciling/categorizing a row moves these server-authoritative counts."
  [{:keys [unreconciled total uncategorized transfers-hidden]}]
  (list [:span#count-unreconciled.filter-count unreconciled]
        [:span#count-total.filter-count total]
        [:span#count-uncategorized.filter-count uncategorized]
        [:span#count-transfers.filter-count transfers-hidden]))

(defn undo-redo-controls
  "Undo/redo buttons for the toolbar (stable #undo-redo morph target, re-rendered after every
   edit so the enabled state + tooltip track the command log). The keyboard shortcuts
   (Cmd/Ctrl+Z / +Shift) do the same thing. Dumb: the handler supplies the current
   `{:undo-label :redo-label}` (nil label = nothing to undo/redo)."
  [{:keys [undo-label redo-label]}]
  [:div.undo-redo {:id "undo-redo" :role "group" :aria-label "Undo and redo"}
   [:button (cond-> {:class "button button-secondary undo-redo-btn" :type "button"
                     :aria-label "Undo" :title (if undo-label (str "Undo: " undo-label) "Nothing to undo")
                     "data-on:click" "@post('/transactions/undo')"}
              (not undo-label) (assoc :disabled true))
    (undo-icon)]
   [:button (cond-> {:class "button button-secondary undo-redo-btn" :type "button"
                     :aria-label "Redo" :title (if redo-label (str "Redo: " redo-label) "Nothing to redo")
                     "data-on:click" "@post('/transactions/redo')"}
              (not redo-label) (assoc :disabled true))
    (redo-icon)]])

(defn- view-menu-checkbox
  "One display/column toggle row: a checkbox bound to `signal` (checked = shown), labelled
   `label`. Reuses the filter-dropdown item styling so Display and Columns read as one list."
  [signal label]
  [:li.filter-dropdown-item
   [:label.filter-dropdown-checkbox-label
    [:input.filter-dropdown-checkbox {:type "checkbox" "data-bind" signal}]
    [:span.filter-dropdown-label-text label]]])

(defn column-picker
  "Toolbar \"View\" dropdown: table display options. A Display group — the inline posted-date
   hint (`$showPosted` flips the table's `hide-posted` class) — sits above the column-visibility
   list (each `cols.<id>` checkbox flips a `hide-<id>` class). Both are pure CSS (no round-trip)
   and the URL reflector persists them. The footer's \"Reset widths\" hands every column back to
   auto-fit via the resize island's window hook. `__stop` so the open-click isn't also seen as a
   click-outside. (Kept the `column-picker` fn/class name — the resize + e2e hooks key off it.)"
  []
  [:div.filter-button-container.column-picker
   [:button.button.button-secondary.filter-button
    {:type "button" :aria-haspopup "true"
     "data-on:click__stop" "$_colsOpen = !$_colsOpen"
     ;; String 'true'/'false' (not a bare boolean): Datastar drops a false-valued attr,
     ;; which would strip aria-expanded and the [aria-expanded="true"] caret flip.
     "data-attr" "{'aria-expanded': $_colsOpen ? 'true' : 'false'}"}
    "View"
    [:span.filter-button-arrow (chevron-down)]]
   [:div.filter-dropdown.view-menu
    {"data-show" "$_colsOpen" "data-on:click__outside" "$_colsOpen = false"}
    [:div.view-menu-section
     [:p.view-menu-group-label "Display"]
     [:ul.filter-dropdown-list
      (view-menu-checkbox "showPosted" "Posted dates")]]
    [:div.view-menu-section
     [:p.view-menu-group-label "Columns"]
     [:ul.filter-dropdown-list
      (for [[id label] vs/hideable-columns]
        (view-menu-checkbox (str "cols." id) label))]]
    [:div.filter-dropdown-footer
     [:button.button.button-secondary.filter-dropdown-clear
      {:type "button" "data-on:click" "window.__resetWidths && window.__resetWidths()"}
      "Reset widths"]]]])
(defn empty-state []
  [:div.empty-state
   [:div.empty-state-title "No transactions this month"]
   [:p "Use the month controls to browse another period, or import from Setup."]])

(defn error-banner
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

(defn sr-status
  "Visually-hidden polite live region (#sr-status). After an edit or view change it's
   re-patched with a short spoken summary, so the SSE-morphed counts — which only sighted
   users can watch tick — are announced to screen-reader users too."
  ([] (sr-status nil))
  ([msg] [:div {:id "sr-status" :class "sr-only" :role "status" :aria-live "polite"} msg]))

(defn review-status-message
  "The spoken summary after an edit: the faceted to-reconcile count, plus the uncategorized
   count when any remain. Gives a screen-reader user confirmation an action landed and where
   the counts now stand."
  [{:keys [unreconciled uncategorized]}]
  (str unreconciled (if (= 1 unreconciled) " transaction" " transactions") " to reconcile"
       (when (pos? uncategorized) (str ", " uncategorized " uncategorized"))))

(defn url-sync
  "Reflect the persistent view-state into the URL on change (the url island owns the
   serialization). Scoped by the signal-patch filter to the persistent signals so it ignores
   edit + ephemeral-UI signals. The READ side is server-side (query->view-state on load)."
  []
  [:div {:hidden true
         "data-on-signal-patch-filter"
         "{include: /^(search|scope|hideTransfers|uncat|showPosted|sortCol|sortDir|page|pageSize)$|^(cols|filter)\\./}"
         "data-on-signal-patch"
         (str "window.__syncUrl && window.__syncUrl({q: $search, scope: $scope,"
              " ht: $hideTransfers, uncat: $uncat, sortCol: $sortCol, sortDir: $sortDir,"
              " page: $page, pageSize: $pageSize, cols: $cols, showPosted: $showPosted,"
              " fa: $filter.account, fi: $filter.institution, fc: $filter.category})")}])

(defn funnel-list
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

(defn funnel-popover
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

(defn active-filter-chip [col field label remove-id]
  [:span.active-chip
   [:span.active-chip-field field]
   [:span.active-chip-value (or label "—")]
   [:button.active-chip-remove
    {:type "button" :aria-label (str "Remove " label " filter")
     "data-on:click" (str "$filter." col " = $filter." col ".filter(x => x !== '" remove-id "');"
                          " $page = 0; @get('/transactions/rows')")}
    "×"]])

(defn active-filters
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

(defn funnel-popovers [account-opts institution-opts category-opts]
  (list (funnel-popover "account" account-opts)
        (funnel-popover "institution" institution-opts)
        (funnel-popover "category" category-opts)))

(def undo-key-js
  ;; Cmd/Ctrl+Z = undo, +Shift = redo. Static literal (no server data).
  (str "(evt.metaKey || evt.ctrlKey) && (evt.key === 'z' || evt.key === 'Z')"
       " && (evt.preventDefault(), evt.shiftKey ? @post('/transactions/redo') : @post('/transactions/undo'))"))
;; --- Row-actions menu + split-editor modal ---------------------------------

(def close-modal-js
  ;; Pure-client modal close: empty #modal-root. Used by modals without an island (the match +
  ;; review modals); the split modal's island wipes it the same way.
  "document.getElementById('modal-root').replaceChildren()")

(defn backdrop-attrs
  "Backdrop close behaviour for an island-less modal: a click on the backdrop itself
   (not a bubbled click from inside the dialog — `evt.target === el`) closes, as does Escape."
  []
  {:role "presentation"
   "data-on:click" (str "evt.target === el && (" close-modal-js ")")
   "data-on:keydown__window" (str "evt.key === 'Escape' && (" close-modal-js ")")})

(defn row-actions-menu
  "The single floating menu shared by every row's caret (rendered outside the table so it
   escapes overflow). $_rowMenu holds the open row's id (0 = closed); $_rowMenuSplit (\"this
   row is a split part\") and $_rowMenuMatched flip the item labels — \"Edit split\" vs
   \"Split transaction\" and \"Matched transfer\" vs \"Match transfer\"; $_rowMenuSplitTarget
   is the id the split editor opens on (a part's PARENT, else the row itself); $_rowMenuManual
   gates a \"Delete transaction\" item shown only for manual rows. \"Set posted date…\" @get's
   plain $_rowMenu — the server resolves a split part to its family root itself
   (split-editor-root), so no dedicated target signal is needed there. Each item @get's the
   relevant modal (the url is built before the signals are cleared)."
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
      "data-on:click" "@get('/transactions/' + $_rowMenuSplitTarget + '/split-editor'); $_rowMenu = 0"}
     "Split transaction"]]
   [:li {:role "none"}
    [:button.row-actions-item
     {:type "button" :role "menuitem"
      "data-text" "$_rowMenuMatched ? 'Matched transfer' : 'Match transfer'"
      "data-on:click" "@get('/transactions/' + $_rowMenu + '/match'); $_rowMenu = 0"}
     "Match transfer"]]
   [:li {:role "none"}
    [:button.row-actions-item
     {:type "button" :role "menuitem"
      "data-on:click" "@get('/transactions/' + $_rowMenu + '/posted-date-editor'); $_rowMenu = 0"}
     "Set posted date…"]]
   ;; Manual transactions only (the user's own entries) can be deleted — the menu item
   ;; is hidden for imported rows via $_rowMenuManual.
   [:li {:role "none" "data-show" "$_rowMenuManual"}
    [:button.row-actions-item.is-danger
     {:type "button" :role "menuitem"
      "data-on:click" "@get('/transactions/' + $_rowMenu + '/manual/delete'); $_rowMenu = 0"}
     "Delete transaction"]]])

(defn split-editor-modal
  "The split-editor modal, patched into #modal-root by GET /transactions/:id/split-editor.
   The dialog carries the editor's data for the island (tx id, parent amount, JSON seed rows,
   already-split?); the island fills .split-rows-container, runs the live balance math
   (islands/lib/splitMath), and on save writes the JSON payload into #split-courier (whose
   change @put's). Cancel/Esc/backdrop close client-side (the island wipes #modal-root); save
   closes server-side (the PUT response re-patches #modal-root empty)."
  [tx seed]
  (let [tx-id (:db/id tx)
        amount (:transaction/amount tx)
        split? (boolean (seq (:transaction/_split-parent tx)))]
    [:div {:id "modal-root"}
     [:div.modal-backdrop.split-modal-backdrop {:role "presentation"}
      [:div.modal-content.split-modal-content
       {:data-split-editor "" :data-tx-id (str tx-id) :data-amount (str amount)
        :data-seed (r/signals seed) :data-split (str split?)
        :role "dialog" :aria-modal "true" :aria-labelledby "split-modal-title"}
       [:h2#split-modal-title (if split? "Edit split" "Split transaction")]
       [:div.split-modal-sub
        [:span.split-modal-payee (:transaction/payee tx)]
        [:span.numeric (fmt/amount amount)]]
       [:p.split-modal-hint
        "Divide this transaction into parts that add up to the total. Parts can be categorized now or later."]
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

(defn match-modal
  "The transfer match/unmatch modal, patched into #modal-root by GET /transactions/:id/match.
   No island needed: a matched row shows its partner + an Unmatch button; an unmatched row
   lists candidate counterparts (each a button that @put's the confirm). Both @put's apply a
   :set-match command (so undo/redo works) and close the modal (re-patch #modal-root empty).
   Cancel/Esc/backdrop close client-side."
  [tx candidates]
  (let [tx-id (:db/id tx)
        partner (:transaction/transfer-pair tx)
        leg (fn [t static?]
              (let [body [:span.transfer-suggestion-body
                          [:span.transfer-suggestion-route (account-name t)]
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

(defn suggestion-row
  "One suggested pair: Confirm links it; \"Not a transfer\" rejects it. Both @put a review
   action that refreshes #review-list in place (the acted-on pair drops out)."
  [{:keys [outflow inflow amount day-diff]}]
  (let [out-id (:db/id outflow)
        in-id (:db/id inflow)]
    [:div.transfer-suggestion
     [:button.button.button-primary.transfer-confirm-button
      {:type "button" "data-on:click" (str "@put('/transactions/review/" out-id "/confirm/" in-id "')")}
      "Confirm"]
     [:div.transfer-suggestion-body
      [:div.transfer-suggestion-route
       [:span (account-name outflow)] [:span.transfer-arrow "→"] [:span (account-name inflow)]]
      [:div.transfer-suggestion-meta
       (str (fmt/date (:transaction/posted-date outflow)) " · "
            day-diff (if (= 1 day-diff) " day apart" " days apart"))]]
     [:span.transfer-suggestion-amount.numeric (fmt/amount amount)]
     [:button.transfer-reject-button
      {:type "button" "data-on:click" (str "@put('/transactions/review/" out-id "/reject/" in-id "')")}
      "Not a transfer"]]))

(defn review-list
  "The suggestion list (its own #review-list id is the morph target so a confirm/reject can
   re-patch the now-smaller list in place without closing the modal)."
  [suggestions]
  [:div {:id "review-list"}
   (if (empty? suggestions)
     [:div.transfer-empty "No suggested transfers — you're all caught up."]
     (into [:div.transfer-suggestion-list {:role "list"}] (map suggestion-row suggestions)))])

(defn review-modal
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

(defn rollup-active-expr [{:keys [uncategorized? ids]}]
  (if uncategorized?
    "$uncat"
    (str "$filter.category.length === " (count ids)
         (apply str (map #(str " && $filter.category.includes('" % "')") ids)))))

(defn rollup-click-expr [{:keys [uncategorized? ids] :as row}]
  ;; Toggle this row's filter. The ternary branches are single expressions, so the assignments
  ;; inside them are joined with the comma operator (a `;` there is a JS syntax error); the
  ;; trailing page-reset + @get are `;`-separated statements at the top level.
  (let [active   (rollup-active-expr row)
        set-it   (if uncategorized?
                   "$uncat = true, $filter.category = []"
                   (str "$filter.category = [" (str/join ", " (map #(str "'" % "'") ids)) "], $uncat = false"))
        clear-it (if uncategorized? "$uncat = false" "$filter.category = []")]
    (str active " ? (" clear-it ") : (" set-it "); $page = 0; @get('/transactions/rows')")))

(defn rollup-row [{:keys [name depth group?] :as row}]
  [:li {:class (str/trim (str "rollup-row rollup-row--depth" depth (when group? " rollup-row--group")))
        "data-class" (str "{'is-active': " (rollup-active-expr row) "}")}
   [:button.rollup-row-button
    {:type "button" "data-on:click" (rollup-click-expr row)
     "data-attr" (str "{'aria-pressed': " (rollup-active-expr row) "}")}
    [:span.rollup-row-name
     (when (= 1 depth) [:span.rollup-branch {:aria-hidden "true"} "└"])
     name]
    [:span.rollup-amount (fmt/amount (:amount row))]]])

(def rollup-section-labels {:income "Income" :expense "Expenses" :transfer "Transfers"})

(defn rollup-section [{:keys [type rows total]}]
  [:section.rollup-section
   [:h3.rollup-section-title (rollup-section-labels type)]
   (into [:ul.rollup-rows] (map rollup-row rows))
   [:div.rollup-subtotal
    [:span.rollup-row-name (str (rollup-section-labels type) " total")]
    [:span.rollup-amount (fmt/amount total)]]])

(defn- summary-toggle
  "The collapsible header for a summary-column card: an <h2> section heading whose
   button flips `open-signal` (a Datastar ephemeral signal like \"$_rollupOpen\"). The
   chevron is the app's shared stroke icon — it points down when open and rotates to the
   row-caret's right when collapsed. `controls-id` names the body it shows/hides."
  [title open-signal controls-id]
  [:h2.summary-card-heading
   [:button.summary-toggle
    ;; Emit the string 'true'/'false' (not a bare boolean): Datastar's data-attr drops an
    ;; attribute whose value is boolean false, which would strip aria-expanded entirely and
    ;; break both the a11y state and the [aria-expanded="false"] chevron rotation.
    {:type "button" :aria-controls controls-id
     "data-on:click" (str open-signal " = !" open-signal)
     "data-attr" (str "{'aria-expanded': " open-signal " ? 'true' : 'false'}")}
    title
    (chevron-down)]])

(defn- reconcile-status-span
  "The status chip shared by an overview account row and a statement row. `status` is
   :reconciled / :drift / :partial / :no-snapshot: :drift is a statement's own period verdict
   (reconcile-statement-period); :partial is the coverage-strict account verdict
   (data.ledger/month-coverage) — when it carries a `difference` (the single-number case: a
   month-boundary balance entered with no statements at all) it reads exactly like :drift
   ('off by $X'), since that IS the number to blame; without one (a gap in coverage, or
   multiple periods with no single figure) it reads as a plain 'partly covered' note instead."
  [status difference]
  (cond
    (= :reconciled status)
    [:span.reconcile-status.reconcile-status--ok
     {:title "Tracked activity matches the bank's balance change"}
     [:span.reconcile-tick {:aria-hidden "true"} "✓"] "matches"]
    (= :drift status)
    [:span.reconcile-status.reconcile-status--drift
     {:title "Computed change differs from the bank's reported change"}
     "off by " (fmt/amount difference)]
    (= :partial status)
    (if (some? difference)
      [:span.reconcile-status.reconcile-status--drift
       {:title "Computed change differs from the bank's reported change"}
       "off by " (fmt/amount difference)]
      [:span.reconcile-status.reconcile-status--muted
       [:span {:aria-hidden "true"} "○ "] "partly covered"])
    :else
    [:span.reconcile-status.reconcile-status--muted "needs balances"]))

(defn- reconcile-row
  "One overview account line: its name + reconcile status, the whole row a button that
   drills into the focused reconcile view — which also filters the table to that account
   (both flow through /transactions/rows, keeping the panel and table in sync)."
  [{:keys [account-id status difference] acct-name :name}]
  [:li {:class (str "reconcile-row reconcile-row--" (name status))}
   [:button.reconcile-drill
    {:type "button" :aria-label (str "Reconcile " acct-name)
     "data-on:click" (str "$filter.account = ['" account-id "']; $page = 0; @get('/transactions/rows')")}
    [:span.reconcile-account {:title acct-name} acct-name]
    (reconcile-status-span status difference)]])

(defn- reconcile-readout-line [label value]
  [:div.reconcile-readout-line
   [:span.reconcile-readout-label label]
   [:span.reconcile-readout-amt.numeric (if (some? value) (fmt/amount value) "—")]])

(defn- focus-coverage-headline
  "The focused card's top-of-card headline: the ACCOUNT-LEVEL coverage-strict verdict
   (data.ledger/month-coverage via web.view/focus-close's :coverage) — whether EVERY
   transaction this month is inside a reconciled period (month-boundary and/or statement),
   not just the single month-boundary period's own verdict (that lives further down, in
   focus-month-section). This is the headline because it's the actual close-gate question for
   this account; the month-end balances are just one of the ways to answer it."
  [{:keys [status uncovered first-uncovered]}]
  (cond
    (= :reconciled status)
    [:div.reconcile-coverage.reconcile-coverage--ok
     [:p.reconcile-coverage-line [:span.reconcile-tick {:aria-hidden "true"} "✓ "] "Reconciled"]
     [:p.reconcile-coverage-note "Every transaction this month is inside a matching period."]]
    (= :partial status)
    [:div.reconcile-coverage.reconcile-coverage--partial
     [:p.reconcile-coverage-line
      [:span.gate-mark {:aria-hidden "true"} "○ "]
      (str uncovered " transaction" (when (not= 1 uncovered) "s") " not yet covered")]
     [:p.reconcile-coverage-note
      (str "Add a period that covers "
           (if first-uncovered (str "activity from " (fmt/date first-uncovered)) "the remaining activity")
           ".")]]
    :else
    [:div.reconcile-coverage.reconcile-coverage--muted
     [:p.reconcile-coverage-line "○ Not checked yet"]
     [:p.reconcile-coverage-note "Enter month-end balances or add a statement to check this account."]]))

(defn- balance-field
  "A labelled balance input for the focused card. The note spells out the app-owned
   end-of-day boundary date, so the user only ever types a figure."
  [id signal label boundary-date value]
  [:div.reconcile-field
   [:label.reconcile-field-label {:for id}
    label [:span.reconcile-field-note (str "end of " (fmt/date boundary-date))]]
   [:input.form-input
    {:id id :type "number" :step "0.01" :inputmode "decimal" :placeholder "0.00"
     :value (if (some? value) (str value) "") "data-bind" signal}]])

(defn- statement-toggle-js
  "Toggle the table's narrowing to this statement's span: click to narrow to [from, to]
   (which may cross the month boundary), click the selected one again to return to the
   whole month. `from`/`to` are yyyy-MM-dd."
  [from to]
  (str "($reconFrom === '" from "' && $reconTo === '" to "')"
       " ? ($reconFrom = '', $reconTo = '')"
       " : ($reconFrom = '" from "', $reconTo = '" to "');"
       " $page = 0; @get('/transactions/rows')"))

(defn- statement-row
  "One statement in the focused card's list: its span, the two statement balances, and its
   reconcile verdict — a button that narrows the table to the statement's date range (a second
   click un-narrows). A trailing Edit opens the modal. Highlights when it's the active span."
  [{:keys [id start-date end-date start-iso end-iso start-balance end-balance status difference]}]
  [:li {:class (str "reconcile-statement reconcile-statement--" (name status))
        "data-class" (str "{'is-selected': $reconFrom === '" start-iso "' && $reconTo === '" end-iso "'}")}
   [:button.reconcile-statement-span
    {:type "button" :aria-label (str "Show transactions for " (fmt/date start-date) " to " (fmt/date end-date))
     "data-on:click" (statement-toggle-js start-iso end-iso)}
    [:span.reconcile-statement-dates (fmt/date start-date) " → " (fmt/date end-date)]
    (reconcile-status-span status difference)
    [:span.reconcile-statement-bals.numeric (fmt/amount start-balance) " → " (fmt/amount end-balance)]]
   [:button.reconcile-statement-edit
    {:type "button" :aria-haspopup "dialog"
     :aria-label (str "Edit statement " (fmt/date start-date) " to " (fmt/date end-date))
     "data-on:click" (str "@get('/transactions/statement-modal?id=" id "')")}
    "Edit"]])

(defn- focus-statements-section
  "The account's statements overlapping the month (each a reconcilable arbitrary-span period)
   + the '+ Add statement' action — for accounts (credit cards) whose balance can't be read on
   a chosen day, so reconciliation runs between the statement's own dates instead."
  [statements]
  [:div.reconcile-statements
   [:div.reconcile-statements-head
    [:span.reconcile-subhead "Statements"]
    [:button.reconcile-add-statement
     {:type "button" :aria-haspopup "dialog"
      "data-on:click" "@get('/transactions/statement-modal')"}
     "+ Add statement"]]
   (if (seq statements)
     (into [:ul.reconcile-statement-list] (map statement-row statements))
     [:p.reconcile-statements-empty
      "None yet — add one to reconcile an arbitrary date range (e.g. a credit-card statement)."])])

(defn- month-period-verdict
  "The month-end section's own per-period verdict — just this one boundary span, not the
   account-level coverage headline (focus-coverage-headline, further up the card). nil for
   :no-snapshot (nothing to say yet; the fields above are the prompt)."
  [status difference]
  (cond
    (= :reconciled status)
    [:p.reconcile-month-verdict.reconcile-status--ok "✓ This period matches"]
    (= :drift status)
    [:p.reconcile-month-verdict.reconcile-status--drift (str "Off by " (fmt/amount difference) " for this period")]
    :else nil))

(defn- focus-month-section
  "The collapsible 'Month-end balances' disclosure: opening/closing entry (app-owned end-of-day
   dates), Save, and the expected-vs-tracked readout ending in this period's own verdict. Optional
   by design — a credit card reconciled entirely by statements never needs it — so it's collapsible
   and its default open/closed state is decided server-side (recon-signals: collapsed only when
   the account already reconciles by statements alone), driven by the ephemeral $_reconMonthOpen
   signal (mirrors the summary-card collapse pattern, scoped to this one sub-section)."
  [opening closing opening-date closing-date expected tracked boundary-status boundary-difference]
  [:div.reconcile-month {"data-class" "{'is-collapsed': !$_reconMonthOpen}"}
   [:button.reconcile-month-toggle
    {:type "button" "data-on:click" "$_reconMonthOpen = !$_reconMonthOpen"
     "data-attr" "{'aria-expanded': $_reconMonthOpen ? 'true' : 'false'}"}
    [:span.reconcile-subhead "Month-end balances"]
    [:span.reconcile-month-opt "optional"]
    (chevron-down)]
   [:div.reconcile-month-body
    [:div.reconcile-month-fields
     (balance-field "recon-open"  "reconOpen"  "Opening" opening-date opening)
     (balance-field "recon-close" "reconClose" "Closing" closing-date closing)]
    [:div.form-actions
     [:button.button.button-primary
      {:type "button"
       "data-attr" "{disabled: $reconOpen === '' && $reconClose === ''}"
       "data-on:click" "@post('/transactions/reconcile')"}
      "Save balances"]]
    [:div.reconcile-month-readout
     (reconcile-readout-line "Expected change" expected)
     (reconcile-readout-line "Tracked activity" tracked)
     (month-period-verdict boundary-status boundary-difference)]]])

(defn- focus-card
  "The focused single-account reconcile view: a Back action, the COVERAGE HEADLINE (the
   account-level coverage-strict verdict — every txn this month covered by SOME reconciled
   period), the STATEMENTS list (arbitrary-span periods), then the collapsible MONTH-END
   BALANCES section (the single month-boundary period: opening/closing entry, Save, its own
   expected-vs-tracked verdict). The headline answers the actual close-gate question up front;
   the two period-entry sections below are simply the two ways to answer it — a credit card
   reconciled purely by statements can leave the month-end section collapsed."
  [{:keys [opening closing opening-date closing-date expected tracked
           boundary-status boundary-difference coverage statements]
    acct-name :name}]
  [:div.reconcile-focus
   [:div.reconcile-focus-head
    [:button.reconcile-back
     {:type "button" :aria-label "Back to all accounts"
      "data-on:click" "$filter.account = []; $reconFrom = ''; $reconTo = ''; $page = 0; @get('/transactions/rows')"}
     "← Back"]
    [:span.reconcile-focus-title {:title acct-name} acct-name]]
   (focus-coverage-headline coverage)
   (focus-statements-section statements)
   (focus-month-section opening closing opening-date closing-date expected tracked
                        boundary-status boundary-difference)])

(defn- account-select
  "A .form-select over all accounts, bound to `signal`, preselecting `selected` eid.
   Shared by the statement-balance and add-transaction modals."
  [id signal accounts selected]
  (into [:select.form-select {:id id "data-bind" signal}]
        (for [{:keys [eid name]} accounts]
          [:option (cond-> {:value (str eid)} (= eid selected) (assoc :selected true)) name])))

(defn- form-modal
  "The shared island-less form-modal frame → #modal-root: a backdrop (Esc + backdrop
   close) wrapping a .form-modal-content dialog labelled by `labelledby-id`. `body` is
   the dialog's contents; role/aria-modal let the modal-focus island trap focus. Shared
   by the statement-balance, add-transaction, and delete-confirm modals."
  [labelledby-id & body]
  [:div {:id "modal-root"}
   [:div.modal-backdrop (backdrop-attrs)
    (into [:div.modal-content.form-modal-content
           {:role "dialog" :aria-modal "true" :aria-labelledby labelledby-id}]
          body)]])

(defn- direction-btn
  "One segment of the money-out / money-in toggle, bound to $txDir."
  [dir label]
  [:button.txn-dir-btn
   {:type "button" :role "radio"
    "data-attr"    (str "{'aria-checked': $txDir === '" dir "'}")
    "data-class"   (str "{'is-active': $txDir === '" dir "'}")
    "data-on:click" (str "$txDir = '" dir "'")}
   label])

(defn- category-select
  "Optional category picker for the add-transaction modal, grouped by type, bound to
   $txCategory (\"\" = uncategorized). A plain select keeps the modal island-free; the
   table's richer combobox is a separate surface."
  [categories]
  (let [by-type (group-by :category/type categories)]
    (into [:select.form-select {:id "tx-category" "data-bind" "txCategory"}
           [:option {:value ""} "Uncategorized"]]
          (for [[type label] [[:income "Income"] [:expense "Expenses"] [:transfer "Transfers"]]
                :let [cats (sort-by :category/name (by-type type))]
                :when (seq cats)]
            (into [:optgroup {:label label}]
                  (for [c cats] [:option {:value (str (:db/id c))} (:category/name c)]))))))

(defn add-transaction-modal
  "GET /transactions/manual/new → patched into #modal-root: record a transaction the
   bank feed didn't import. `accounts` is [{:eid :name}] (shown prominently, so it's
   always clear which account the entry lands on), `categories` the optional picker
   options, `default-date` a yyyy-MM-dd seed. Amount is entered as a positive magnitude
   with a money-out/-in toggle; the handler derives the canonical sign. No island —
   plain data-bind fields the handler seeds via patch-signals; Save is disabled until
   account + amount + date are set. Cancel/Esc/backdrop close client-side; a successful
   save re-renders the table and closes the modal."
  [accounts categories default-date selected]
  (form-modal "add-tx-title"
   [:h2#add-tx-title "Add transaction"]
   [:p.form-modal-hint
    "Record a transaction the bank feed didn't import — cash, a missed charge, anything you need in the ledger."]
   [:div.form-group
    [:label.form-label {:for "tx-account"} "Account"]
    (account-select "tx-account" "txAccount" accounts selected)]
   [:div.form-modal-row
    [:div.form-group
     [:span.form-label "Direction"]
     [:div.txn-direction {:role "radiogroup" :aria-label "Direction"}
      (direction-btn "out" "Money out")
      (direction-btn "in" "Money in")]]
    [:div.form-group
     [:label.form-label {:for "tx-amount"} "Amount"]
     [:input.form-input {:id "tx-amount" :type "number" :step "0.01" :min "0" :inputmode "decimal"
                         :placeholder "0.00" "data-bind" "txAmount"}]]]
   [:div.form-modal-row
    [:div.form-group
     [:label.form-label {:for "tx-date"} "Date"]
     [:input.form-input {:id "tx-date" :type "date" :value default-date "data-bind" "txDate"}]]
    [:div.form-group
     [:label.form-label {:for "tx-category"} "Category"]
     (category-select categories)]]
   [:div.form-group
    [:label.form-label {:for "tx-payee"} "Payee"]
    [:input.form-input {:id "tx-payee" :type "text" :placeholder "e.g. Corner Store" "data-bind" "txPayee"}]]
   [:div.form-group
    [:label.form-label {:for "tx-desc"} "Description"]
    [:input.form-input {:id "tx-desc" :type "text" :placeholder "Optional" "data-bind" "txDesc"}]]
   [:div.form-actions
    [:button.button.button-secondary {:type "button" "data-on:click" close-modal-js} "Cancel"]
    [:button.button.button-primary
     {:type "button"
      "data-attr" "{disabled: !($txAccount && $txAmount && $txDate)}"
      "data-on:click" "@post('/transactions/manual')"}
     "Add transaction"]]))

(defn delete-transaction-modal
  "GET /transactions/:id/manual/delete → a small confirm dialog into #modal-root.
   Deleting a manual transaction is permanent (there's no undo), so confirm first,
   echoing the payee/amount/date so the user knows exactly what they're removing."
  [tx]
  (form-modal "del-tx-title"
   [:h2#del-tx-title "Delete transaction?"]
   [:p.form-modal-hint
    "This permanently removes the manual transaction "
    [:strong (or (not-empty (:transaction/payee tx)) "(no payee)")]
    " for " (fmt/amount (:transaction/amount tx))
    " on " (fmt/date (:transaction/posted-date tx))
    ". This can't be undone."]
   [:div.form-actions
    [:button.button.button-secondary {:type "button" "data-on:click" close-modal-js} "Cancel"]
    [:button.button.button-danger
     {:type "button" "data-on:click" (str "@post('/transactions/" (:db/id tx) "/manual/delete')")}
     "Delete"]]))

(defn statement-modal
  "GET /transactions/statement-modal → #modal-root: add or edit a statement (an arbitrary-span
   reconciliation) for the focused account. Fields: start/end date + start/end balance — no
   account picker (the statement lands on whichever account you drilled into). `editing?` flips
   the title/verb and shows Delete; the handler seeds the signals (blank to add, the statement's
   values to edit) and $stId carries the edit target (blank = create). No island — plain
   data-bind fields; Save is disabled until all four are set."
  [editing?]
  (form-modal "statement-modal-title"
   [:h2#statement-modal-title (if editing? "Edit statement" "Add statement")]
   [:p.form-modal-hint
    "A statement runs between two dates you read off it. The close checks your tracked activity in that span against the balance change."]
   [:div.form-modal-row
    [:div.form-group
     [:label.form-label {:for "st-start"} "From"]
     [:input.form-input {:id "st-start" :type "date" "data-bind" "stStart"}]]
    [:div.form-group
     [:label.form-label {:for "st-start-bal"} "Start balance"]
     [:input.form-input {:id "st-start-bal" :type "number" :step "0.01" :inputmode "decimal"
                         :placeholder "0.00" "data-bind" "stStartBal"}]]]
   [:div.form-modal-row
    [:div.form-group
     [:label.form-label {:for "st-end"} "To"]
     [:input.form-input {:id "st-end" :type "date" "data-bind" "stEnd"}]]
    [:div.form-group
     [:label.form-label {:for "st-end-bal"} "End balance"]
     [:input.form-input {:id "st-end-bal" :type "number" :step "0.01" :inputmode "decimal"
                         :placeholder "0.00" "data-bind" "stEndBal"}]]]
   [:div.form-actions
    (when editing?
      [:button.button.button-danger {:type "button"
                                     "data-on:click" "@post('/transactions/statement/delete')"} "Delete"])
    [:button.button.button-secondary {:type "button" "data-on:click" close-modal-js} "Cancel"]
    [:button.button.button-primary
     {:type "button"
      "data-attr" "{disabled: !($stStart && $stStartBal && $stEnd && $stEndBal)}"
      "data-on:click" "@post('/transactions/statement')"}
     (if editing? "Save" "Add statement")]]))

(defn posted-date-modal
  "GET /transactions/:id/posted-date-editor → #modal-root: manually override a transaction's
   posted date — for providers (Lunchflow, CSV/manual) that never supply a genuine one
   independent of the transaction date, so a statement crossing a boundary can still be
   reconciled. `tx` is the family ROOT (a split part's editor opens on its parent — same
   defensive resolve as the split/match editors), pulled with the derived fields. The
   `type=date` input is bound to the $postedDateValue courier, which the handler seeds
   server-side (patch-signals, not shown here) with the row's current EFFECTIVE date; a muted
   line always shows the provider's own imported :transaction/posted-date ('—' when the
   provider never supplied one) so the user can see exactly what they're overriding. Save
   @put's the courier as-is; Clear (shown only when an override is already set) blanks it
   first — the handler parses a blank value as \"clear the override\", falling back through
   the effective chain. Cancel/Esc/backdrop close client-side."
  [tx]
  (let [tx-id (:db/id tx)
        override? (some? (:transaction/user-posted-date tx))
        imported (:transaction/posted-date tx)]
    (form-modal "posted-date-modal-title"
     [:h2#posted-date-modal-title "Posted date — " (or (not-empty (:transaction/payee tx)) "(no payee)")]
     [:div.posted-date-modal-sub
      [:span.numeric (fmt/amount (:transaction/amount tx))]]
     [:div.form-group
      [:label.form-label {:for "posted-date-input"} "Posted date"]
      [:input.form-input {:id "posted-date-input" :type "date" "data-bind" "postedDateValue"}]]
     [:p.form-modal-hint "Imported: " (if imported (fmt/date-short imported) "—")]
     [:div.form-actions
      (if override?
        [:button.button.button-secondary
         {:type "button"
          "data-on:click" (str "$postedDateValue = ''; @put('/transactions/" tx-id "/posted-date')")}
         "Clear override"]
        [:span])
      [:button.button.button-secondary {:type "button" "data-on:click" close-modal-js} "Cancel"]
      [:button.button.button-primary
       {:type "button" "data-on:click" (str "@put('/transactions/" tx-id "/posted-date')")}
       "Save"]])))

(defn- gate-line [ok? label]
  [:li {:class (str "gate-line " (if ok? "gate-line--ok" "gate-line--todo"))}
   [:span.gate-mark {:aria-hidden "true"} (if ok? "✓" "○")]
   [:span label]])

(defn- close-controls
  "Below the per-account rows: the completeness gate + Close button (open month), or
   the closed banner + drift note + Reopen (closed month)."
  [{:keys [gate closed? closed-at drift]}]
  (if closed?
    [:div.reconcile-close
     [:p.reconcile-closed [:span.reconcile-tick {:aria-hidden "true"} "✓ "] "Closed " (fmt/date closed-at)]
     (when drift
       [:p.reconcile-drift
        "Changed since close — net was " (fmt/amount (:frozen drift))
        ", now " (fmt/amount (:now drift)) "."])
     [:button.button.button-secondary.button-small
      {"data-on:click" "@post('/transactions/reopen')"} "Reopen"]]
    [:div.reconcile-close
     [:ul.reconcile-gate
      (gate-line (:all-reconciled? gate)
                 (if (:all-reconciled? gate) "All reconciled" (str (:unreconciled gate) " to reconcile")))
      (gate-line (:all-categorized? gate)
                 (if (:all-categorized? gate) "All categorized" (str (:uncategorized gate) " uncategorized")))
      (gate-line (:balanced? gate)
                 (if (:balanced? gate) "Balances match" "Balances unreconciled"))]
     [:button.button.reconcile-close-btn
      (cond-> {"data-on:click" "@post('/transactions/close')"}
        (not (:ready? gate)) (assoc :disabled true))
      "Close month"]]))

(defn close-panel
  "The monthly-close panel (#reconciliation). Two modes, driven by whether the table is
   filtered to a single account (model carries :focus): the OVERVIEW of every active
   account's reconcile status — each row a button that drills in — plus the completeness
   gate + Close / Reopen; or the FOCUSED reconcile card for one account (opening/closing
   entry + verdict + Back), where the real cleanup happens. Its own #reconciliation
   element, kept OUTSIDE #category-rollup so the rollup's edit re-patches never clobber it;
   re-patched by its own reconcile/close/reopen actions, by manual create/delete, and by
   any filter change (drill/back go through /transactions/rows). The wrapper is ALWAYS
   rendered (a stable SSE morph target); in overview with no activity it's `:hidden` — but
   a focused card is never empty.

   A collapsible summary card: the header toggles $_reconcileOpen (an ephemeral body signal
   that survives these morphs)."
  [{:keys [rows focus] :as model}]
  (let [empty? (and (nil? focus) (not (seq rows)))]
    [:section.reconcile-panel.summary-card
     (cond-> {:id "reconciliation" :aria-label "Monthly close"
              "data-class" "{'summary-collapsed': !$_reconcileOpen}"}
       empty? (assoc :hidden true))
     (when-not empty?
       (if focus
         (list
          (summary-toggle "Reconciliation" "$_reconcileOpen" "reconcile-body")
          [:div.reconcile-body.summary-card-body {:id "reconcile-body"}
           (focus-card focus)])
         (list
          (summary-toggle "Reconciliation" "$_reconcileOpen" "reconcile-body")
          [:div.reconcile-body.summary-card-body {:id "reconcile-body"}
           (into [:ul.reconcile-rows] (map reconcile-row rows))]
          (close-controls model))))]))

(defn rollup-pane [{:keys [income expenses transfers grand-total]}]
  (let [sections (filter #(seq (:rows %)) [income expenses transfers])]
    [:aside.rollup-pane.summary-card {:id "category-rollup" :aria-label "Category summary"
                                      "data-class" "{'summary-collapsed': !$_rollupOpen}"}
     (summary-toggle "Summary" "$_rollupOpen" "rollup-scroll")
     [:div.rollup-scroll.summary-card-body {:id "rollup-scroll"}
      (if (empty? sections)
        [:p.rollup-empty "No activity to summarize."]
        (map rollup-section sections))
      [:div.rollup-net
       [:span.rollup-net-label "Net"]
       [:span {:class (str "rollup-amount " (if (neg? grand-total) "negative" "positive"))}
        (fmt/amount grand-total)]]]]))

;; ---------------------------------------------------------------------------
;; Page body (the full workspace inside layout/document)
;; ---------------------------------------------------------------------------

(defn page-body
  "The full transactions workspace body (everything inside layout/document). Dumb: the handler
   supplies the `month`, masthead `stats`, `categories` (for the combobox model), `view-st`
   (funnel selections), the presented `model`, the `undo` labels, and whether the month is
   `empty?` of transactions."
  [{:keys [month stats categories view-st model undo empty?]}]
  ;; `cat-opts` is the model's category *funnel* option list — kept distinct from the
  ;; `category-options` view fn (the hidden combobox source list) it would otherwise shadow.
  (let [{:keys [result counts account-options institution-options rollup]
         close-model :close cat-opts :category-options} model]
    [:div.container.container--workspace {"data-on:keydown__window" undo-key-js}
     (shell/masthead {:active :transactions :stats stats})
     (error-banner)
     (sr-status)
     [:div.transactions-layout
      [:div.card
       (toolbar month counts undo)
       (active-filters account-options institution-options cat-opts view-st)
       (if empty?
         (empty-state)
         (list (table (:rows result)) (pagination-bar result)))]
      ;; The summary column: the reconciliation readout stacked above the category
      ;; rollup. Kept as siblings (not nested in #category-rollup) so edit re-patches
      ;; of the rollup leave the reconciliation panel intact.
      [:div.rollup-column
       (close-panel close-model)
       (rollup-pane rollup)]]
     (when-not empty?
       (list (funnel-popovers account-options institution-options cat-opts)
             (row-actions-menu)))
     (category-options categories)
     (url-sync)
     ;; Patched by GET /transactions/:id/split-editor; emptied again on close/save.
     [:div {:id "modal-root"}]]))

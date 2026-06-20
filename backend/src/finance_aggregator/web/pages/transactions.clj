(ns finance-aggregator.web.pages.transactions
  "Server-rendered transactions workspace (/). Phase 3a is the read-only
   foundation: the full 9-column table (normal + split rows, signed amounts,
   sticky header) for a month, plus month navigation. The interaction layers
   (reviewed toggle, search/filters, inline edit, combobox, grid-nav, sorting,
   pagination, splits, transfers, rollup) are added in later phases."
  (:require
   [clojure.string :as str]
   [finance-aggregator.db.categories :as db-categories]
   [finance-aggregator.db.stats :as db-stats]
   [finance-aggregator.db.transactions :as db-transactions]
   [finance-aggregator.web.format :as fmt]
   [finance-aggregator.web.hiccup :as h]
   [finance-aggregator.web.layout :as layout]
   [finance-aggregator.web.month :as month]
   [finance-aggregator.web.shell :as shell]))

;; ---------------------------------------------------------------------------
;; Columns
;; ---------------------------------------------------------------------------

(def ^:private columns
  [{:id "date"        :label "Date"}
   {:id "account"     :label "Account"}
   {:id "institution" :label "Institution"}
   {:id "payee"       :label "Payee"}
   {:id "description" :label "Description"}
   {:id "amount"      :label "Amount"}
   {:id "category"    :label "Category"}
   {:id "reviewed"    :label "Reviewed"}
   {:id "actions"     :label ""}])

(defn- cell-class [id]
  (case id
    "amount"      "amount-cell"
    "description" "description-cell"
    "category"    "category-cell"
    "reviewed"    "reviewed-cell"
    "actions"     "actions-cell"
    nil))

;; --- Column width estimate -------------------------------------------------
;; `.table td` sets `max-width: 0`, so columns collapse + ellipsis-truncate unless
;; an explicit width is set. React sets per-column `<col style="width">` from its
;; client-side auto-sizing; we render a content-fit estimate server-side (matching
;; that approach) so the table reads correctly before/without JS. The resize/
;; auto-fit island refines these client-side in Phase 3d.

(defn- col-text
  "The display string for column `id` of a (top-level) transaction — used both to
   size columns and (implicitly) mirrors what the cell renders."
  [id {:transaction/keys [posted-date account payee effective-description amount category]}]
  (case id
    "date"        (fmt/date posted-date)
    "account"     (or (:account/external-name account) "—")
    "institution" (or (get-in account [:account/institution :institution/name]) "—")
    "payee"       (or payee "")
    "description" (if (str/blank? effective-description) "—" (str effective-description))
    "amount"      (fmt/amount amount)
    "category"    (or (:category/name category) "Uncategorized")
    ("reviewed" "actions") ""))

(def ^:private col-size
  ;; [min-px max-px px-per-char] — numeric (mono) columns are wider per char.
  {"date"        [120 180 9.5]
   "account"     [88 190 7.8]
   "institution" [96 200 7.8]
   "payee"       [110 280 7.8]
   "description" [110 300 7.8]
   "amount"      [92 150 8.6]
   "category"    [180 180 7.8]
   "reviewed"    [86 86 7.8]
   "actions"     [44 44 7.8]})

(defn- col-width [id label txs]
  (let [[lo hi cpx] (col-size id)
        longest (apply max (count label) (map #(count (col-text id %)) txs))]
    (long (min hi (max lo (+ 30 (* longest cpx)))))))

;; ---------------------------------------------------------------------------
;; Cells
;; ---------------------------------------------------------------------------

(defn- amount-span
  "Signed amount with the positive/negative class. `neg-at-zero?` matches the React
   split rule (0 reads positive) vs the normal-row rule (0 reads negative)."
  [amt neg-at-zero?]
  (let [negative? (if neg-at-zero? (neg? amt) (not (pos? amt)))]
    [:span {:class (str "numeric " (if negative? "negative" "positive"))} (fmt/amount amt)]))

(defn- description-button [text]
  [:button.description-button
   {:type "button" :tabindex "-1" :aria-label (when (str/blank? text) "Add description")}
   (if (str/blank? text) "—" text)])

;; --- Inline description edit (Datastar class-swap) -------------------------
;; A normal row's description cell renders both a view button and a bound input;
;; an `editing` class on the cell swaps which is shown (CSS), so opening the editor
;; never shifts the row. Both the button (data-text) and the input (data-bind) read
;; the same desc.tx<id> signal, so the optimistic value stands instantly. $descOrig
;; snapshots the pre-edit value so Escape can revert. Enter/blur persist via @put;
;; the server reconciles the signal to the authoritative effective description (a
;; cleared override falls back to the imported text). Split memos edit in 3e.

(defn- desc-open-js
  "Button click: snapshot the current value, open the editor, focus + select input."
  [tx-id]
  (str "$descOrig = $desc.tx" tx-id ", "
       "el.closest('.description-cell').classList.add('editing'), "
       "el.nextElementSibling.focus(), el.nextElementSibling.select()"))

(defn- grid-edit-js
  "Fire a `gridedit` DOM event the grid-nav island listens for (bubbling up to the
   table). `advance` moves down + re-opens the editor (the Enter-walks-the-column
   flow); `cancel` returns focus to the cell."
  [action]
  (str "el.dispatchEvent(new CustomEvent('gridedit', {detail: {action: '" action "'}, bubbles: true}))"))

(defn- desc-keydown-js
  "Inline description editor keys, integrated with the grid-nav island. Enter persists
   then fires gridedit:advance; Escape reverts then fires gridedit:cancel. Both remove
   `editing` first so the trailing blur (focus leaving the input) is a no-op."
  [tx-id]
  ;; stopPropagation so the grid-nav island's container keydown doesn't re-handle the
  ;; key after the editor closes + refocuses the cell (Escape would otherwise clear the
  ;; active cell). Tab is NOT handled here, so it still bubbles to grid-nav (commit+move).
  (let [put   (str "@put('/transactions/" tx-id "/description')")
        close "el.closest('.description-cell').classList.remove('editing')"]
    (str "evt.key === 'Enter' && (evt.stopPropagation(), " put ", " close ", " (grid-edit-js "advance") "); "
         "evt.key === 'Escape' && (evt.stopPropagation(), $desc.tx" tx-id " = $descOrig, " close ", " (grid-edit-js "cancel") ")")))

(defn- desc-blur-js
  "A genuine click-away commits. Enter/Escape already cleared `editing`, so their
   trailing blur is a no-op — this guards the double-commit React latched against."
  [tx-id]
  (str "el.closest('.description-cell').classList.contains('editing') && "
       "(@put('/transactions/" tx-id "/description'), "
       "el.closest('.description-cell').classList.remove('editing'))"))

(defn- editable-description
  "View button + bound input for a normal row's editable description."
  [tx-id text]
  (list
   [:button.description-button
    (h/a {"type" "button" "tabindex" "-1"
          "data-text" (str "$desc.tx" tx-id " ? $desc.tx" tx-id " : '—'")
          "data-on:click" (desc-open-js tx-id)
          "aria-label" (when (str/blank? text) "Add description")})
    (if (str/blank? text) "—" text)]
   [:input.description-input
    (h/a {"type" "text" "data-bind" (str "desc.tx" tx-id)
          "aria-label" "Edit description"
          "data-on:keydown" (desc-keydown-js tx-id)
          "data-on:blur" (desc-blur-js tx-id)})]))

;; --- Category combobox (Zag island + Datastar persistence) -----------------
;; A normal row's category cell keeps the .category-button as the view (the island
;; opens the combobox on click) plus a hidden input bound to cat.tx<id>: the island
;; writes the chosen id there and dispatches change, which @put-persists the change.
;; The server runs update-category! and patches the toolbar counts (a category change
;; moves the uncategorized count). Split-row categories open the split modal in 3e.

(defn- editable-category [tx-id category]
  (list
   [:button.category-button.combo-cell
    (h/a {"type" "button" "tabindex" "-1"
          "id" (str "cat-view-tx" tx-id)
          "aria-haspopup" "listbox"})
    (or (:category/name category) "Uncategorized")]
   [:input
    (h/a {"type" "hidden" "data-bind" (str "cat.tx" tx-id)
          "data-on:change" (str "@put('/transactions/" tx-id "/category')")})]))

(defn- category-options
  "Hidden source-of-truth list the combobox island reconstructs its Category[] from
   (id/parent/sort-order as data-attrs) — Replicant escapes JSON in a <script>, so the
   model is carried in the DOM instead (migration gotcha §2)."
  [categories]
  [:ul#category-options {:hidden true :aria-hidden "true"}
   (for [c categories]
     [:li (cond-> {:data-id (:db/id c)}
            (:category/type c)                   (assoc :data-type (name (:category/type c)))
            (get-in c [:category/parent :db/id]) (assoc :data-parent (get-in c [:category/parent :db/id]))
            (:category/sort-order c)             (assoc :data-sort (:category/sort-order c)))
      (:category/name c)])])

(defn- transfer-status
  "The inline ⇄ marker beside a category: a quiet matched glyph, or an ochre 'Match'
   to-do for a transfer-typed row with no counterpart. (Inert in Phase 3a; the
   transfer modal is wired in Phase 3e.)"
  [{:transaction/keys [transfer-pair category]}]
  (cond
    transfer-pair
    (let [partner (get-in transfer-pair [:transaction/account :account/external-name] "another account")]
      [:button.transfer-status.transfer-status-matched
       {:type "button"
        :title (str "Matched transfer with " partner
                    " (" (fmt/amount (:transaction/amount transfer-pair)) ")")}
       [:span.transfer-status-glyph {:aria-hidden "true"} "⇄"]])

    (= :transfer (:category/type category))
    [:button.transfer-status.transfer-status-unmatched
     {:type "button" :title "Transfer with no matched counterpart — click to match"}
     [:span.transfer-status-glyph {:aria-hidden "true"} "⇄"] "Match"]))

(defn- reviewed-checkbox
  "Optimistic reviewed checkbox bound to a Datastar signal: the click flips the
   signal instantly (no round-trip), and a per-checkbox debounced write-behind
   persists via @put. The server never echoes the checkbox back (the optimistic
   state stands); it only persists. `signal` is e.g. \"reviewed.tx12\"."
  [signal]
  [:input.reviewed-checkbox
   (h/a {"type" "checkbox"
         "data-bind" signal
         "data-on:change__debounce.700ms" "@put('/transactions/reviewed')"})])

(defn- split-icon [drift?]
  [:svg {:class (str "split-icon" (when drift? " split-icon-drift"))
         :width "14" :height "14" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden "true"}
   [:title (if drift? "Split parts no longer add up to the amount" "Part of a split")]
   [:polyline {:points "15 10 20 15 15 20"}]
   [:path {:d "M4 4v7a4 4 0 0 0 4 4h12"}]])

;; ---------------------------------------------------------------------------
;; Client-side filtering (Datastar data-show)
;; ---------------------------------------------------------------------------
;; Each row carries a data-show expression over the toolbar signals — search,
;; review scope, hide-transfers, uncategorized — so filtering is instant with no
;; round-trip. Per-row constants (search haystack, transfer-hidden, uncategorized,
;; the reviewed expression) are baked in server-side; the signals are the only
;; reactive inputs. A reviewed row stays put (no re-render) until the next full
;; page render, which is the desired "linger".

(defn- search-haystack
  "Lowercased text a search matches against (payee, effective description, category,
   and each split part's memo + category) — mirrors React searchTransactions."
  [tx]
  (str/lower-case
   (str/join " "
             (remove str/blank?
                     (concat [(:transaction/payee tx)
                              (:transaction/effective-description tx)
                              (get-in tx [:transaction/category :category/name])]
                             (mapcat (fn [p] [(:split/memo p)
                                              (get-in p [:split/category :category/name])])
                                     (:transaction/splits tx)))))))

(defn- reviewed-expr
  "JS expression that is true when the row counts as reviewed: the tx signal for a
   normal row, or every part's signal AND-ed for a split."
  [tx]
  (if-let [parts (seq (:transaction/splits tx))]
    (str "(" (str/join " && " (map #(str "$reviewed.sp" (:db/id %)) parts)) ")")
    (str "$reviewed.tx" (:db/id tx))))

(defn- row-show-attrs
  "data-show attribute map combining the active toolbar filters for a transaction's
   row(s). Clauses that can't ever hide this row (it isn't a hidden transfer / it is
   uncategorized) are omitted so the expression stays minimal."
  [tx]
  (h/a {"data-show"
        (str "($search === '' || " (h/js-str (search-haystack tx)) ".includes($search.toLowerCase()))"
             " && ($scope === 'all' || !(" (reviewed-expr tx) "))"
             (when (:transaction/transfer-hidden tx) " && !$hideTransfers")
             (when-not (db-transactions/needs-category? tx) " && !$uncat"))}))

;; ---------------------------------------------------------------------------
;; Rows
;; ---------------------------------------------------------------------------

(defn- grid-cell
  "Attributes marking a <td> as a keyboard-navigable grid cell: its stable data-cell
   key (txId:splitId|tx:col, matching gridNavigation/cellKey), the gridcell role, and
   the roving tabindex (-1 until the grid-nav island makes it the active cell). Merge
   into the cell's own attrs. (Split rows aren't navigable until split editing — 3e.)"
  [tx-id split-id col]
  {:data-cell (str tx-id ":" (or split-id "tx") ":" col)
   :role "gridcell"
   :tabindex "-1"})

(defn- normal-row [show {:transaction/keys [posted-date account payee effective-description
                                            amount category] :as tx}]
  [:tr (merge show {:role "row"})
   [:td [:span.numeric (fmt/date posted-date)]]
   [:td (or (:account/external-name account) "—")]
   [:td (or (get-in account [:account/institution :institution/name]) "—")]
   [:td payee]
   [:td.description-cell.editable (grid-cell (:db/id tx) nil "description")
    (editable-description (:db/id tx) effective-description)]
   [:td.amount-cell (amount-span amount false)]
   [:td.category-cell (grid-cell (:db/id tx) nil "category")
    [:div.category-cell-row
     (editable-category (:db/id tx) category)
     (transfer-status tx)]]
   [:td.reviewed-cell (grid-cell (:db/id tx) nil "reviewed")
    (reviewed-checkbox (str "reviewed.tx" (:db/id tx)))]
   [:td.actions-cell]
   [:td.table-spacer-cell {:aria-hidden "true"}]])

(defn- split-parent-row [show {:transaction/keys [posted-date account payee effective-description]}]
  [:tr (merge show {:role "row" :class "is-split-parent"})
   [:td [:span.numeric (fmt/date posted-date)]]
   [:td (or (:account/external-name account) "—")]
   [:td (or (get-in account [:account/institution :institution/name]) "—")]
   [:td payee]
   [:td.description-cell (description-button effective-description)]
   [:td.amount-cell]
   [:td.category-cell]
   [:td.reviewed-cell]
   [:td.actions-cell]
   [:td.table-spacer-cell {:aria-hidden "true"}]])

(defn- split-child-row [show drift? last? {:split/keys [amount memo category] :as part}]
  [:tr (merge show {:role "row" :class (str "split-child-row" (when last? " is-last"))})
   [:td] [:td] [:td] [:td]
   [:td.description-cell
    [:span.split-description (split-icon drift?) (description-button memo)]]
   [:td.amount-cell (amount-span amount true)]
   [:td.category-cell
    [:div.category-cell-content
     [:button.category-button {:type "button" :tabindex "-1" :title "Edit split"}
      (or (:category/name category) "Uncategorized")]]]
   [:td.reviewed-cell (reviewed-checkbox (str "reviewed.sp" (:db/id part)))]
   [:td]
   [:td.table-spacer-cell {:aria-hidden "true"}]])

(defn- tx-rows
  "Expand a transaction into its table row(s): one normal row, or a split parent +
   one row per ordered part. All rows of a split share the same data-show so they
   filter together."
  [tx]
  (let [show (row-show-attrs tx)]
    (if-let [parts (seq (:transaction/splits tx))]
      (let [sorted (sort-by :split/order parts)
            drift? (false? (:transaction/splits-balanced tx))
            last-idx (dec (count sorted))]
        (into [(split-parent-row show tx)]
              (map-indexed (fn [i p] (split-child-row show drift? (= i last-idx) p)) sorted)))
      [(normal-row show tx)])))

;; ---------------------------------------------------------------------------
;; Table + chrome
;; ---------------------------------------------------------------------------

(defn- th-class [id]
  (->> [(cell-class id) "th-static"] (remove nil?) (str/join " ")))

;; --- Column visibility -----------------------------------------------------
;; The read-only columns are hideable via the toolbar picker: a `cols.<id>` signal per
;; column, a `hide-<id>` class toggled on the table by data-class, and a CSS rule that
;; collapses that column by position. Only read-only columns are hideable, so this never
;; touches the grid-nav island (which navigates description/category/reviewed).

(def ^:private hideable-columns
  [["date" "Date"] ["account" "Account"] ["institution" "Institution"]
   ["payee" "Payee"] ["amount" "Amount"]])

(defn- cols-hide-class []
  (str "{" (str/join ", " (map (fn [[id _]] (str "'hide-" id "': !$cols." id)) hideable-columns)) "}"))

(defn- transactions-table [txs]
  ;; Plain .table (table-layout:auto, content-fit) for now; Phase 3d switches to
  ;; .table-resizable (fixed layout) once the resize/auto-size island sets <col> widths.
  [:div.transactions-table-scroll {:tabindex "0"}
   [:table.table (h/a {"role" "grid" "data-class" (cols-hide-class)})
    [:colgroup
     (concat
      (for [{:keys [id label]} columns]
        [:col {:style (str "width:" (col-width id label txs) "px")}])
      [[:col.table-spacer-col]])]
    [:thead
     [:tr
      (for [{:keys [id label]} columns]
        [:th {:class (th-class id)} label])
      [:th.table-spacer-cell {:aria-hidden "true"}]]]
    [:tbody (mapcat tx-rows txs)]]])

(defn- month-navigator [m]
  [:div.month-navigator
   [:div.month-navigator-controls
    [:a.button.button-secondary.month-nav-button
     {:href (str "/?month=" (month/serialize (month/prev-month m))) :title "Previous month"} "‹"]
    [:span.month-navigator-display (month/display m)]
    [:a.button.button-secondary.month-nav-button
     {:href (str "/?month=" (month/serialize (month/next-month m))) :title "Next month"} "›"]]])

(defn- search-box []
  [:div.table-search
   [:svg.table-search-icon {:width "14" :height "14" :viewBox "0 0 24 24" :fill "none"
                            :stroke "currentColor" :stroke-width "2" :stroke-linecap "round"
                            :stroke-linejoin "round" :aria-hidden "true"}
    [:circle {:cx "11" :cy "11" :r "8"}]
    [:line {:x1 "21" :y1 "21" :x2 "16.65" :y2 "16.65"}]]
   [:input.table-search-input
    (h/a {"type" "search" "placeholder" "Search payee, description…"
          "data-bind" "search" "aria-label" "Search transactions"})]])

(defn- scope-toggle
  "Segmented Needs-review / All switch. Sets the $scope signal (data-show keys off
   it); the counts are server-rendered and patched after the reviewed write-behind."
  [{:keys [unreviewed total]}]
  [:div.scope-toggle {:role "group" :aria-label "Review scope"}
   [:button.scope-toggle-btn
    (h/a {"type" "button" "data-on:click" "$scope = 'needs-review'"
          "data-class" "{'is-active': $scope === 'needs-review'}"})
    "Needs review" [:span#count-unreviewed.filter-count unreviewed]]
   [:button.scope-toggle-btn
    (h/a {"type" "button" "data-on:click" "$scope = 'all'"
          "data-class" "{'is-active': $scope === 'all'}"})
    "All" [:span#count-total.filter-count total]]])

(defn- count-chip [label signal span-id count]
  [:button.count-chip
   (h/a {"type" "button" "data-on:click" (str "$" signal " = !$" signal)
         "data-class" (str "{'is-active': $" signal "}")})
   label [:span.filter-count {:id span-id} count]])

(defn counts-fragment
  "The four toolbar count badges as an HTML string, for the SSE patch after a
   write-behind (each morphed by id)."
  [counts]
  (str/join
   (map h/render
        [[:span#count-unreviewed.filter-count (:unreviewed counts)]
         [:span#count-total.filter-count (:total counts)]
         [:span#count-uncategorized.filter-count (:uncategorized counts)]
         [:span#count-transfers.filter-count (:transfers-hidden counts)]])))

(defn- column-picker
  "Toolbar dropdown toggling which read-only columns are shown — checkboxes bound to the
   `cols.<id>` signals (reusing the carried-over filter-dropdown styling). `__stop` on the
   toggle so its open-click isn't also seen as a click outside the menu (Datastar trap)."
  []
  [:div.filter-button-container.column-picker
   [:button.button.button-secondary.filter-button
    (h/a {"type" "button" "aria-haspopup" "true"
          "data-on:click__stop" "$colsOpen = !$colsOpen"
          "data-attr" "{'aria-expanded': $colsOpen}"})
    "Columns"
    [:span.filter-button-arrow (h/a {"data-text" "$colsOpen ? '▲' : '▼'"}) "▼"]]
   [:div.filter-dropdown
    (h/a {"data-show" "$colsOpen" "data-on:click__outside" "$colsOpen = false"})
    [:ul.filter-dropdown-list
     (for [[id label] hideable-columns]
       [:li.filter-dropdown-item
        [:label.filter-dropdown-checkbox-label
         [:input.filter-dropdown-checkbox (h/a {"type" "checkbox" "data-bind" (str "cols." id)})]
         [:span.filter-dropdown-label-text label]]])]]])

(defn- toolbar [m counts]
  [:div.toolbar
   [:div.toolbar-controls
    (month-navigator m)
    [:span.toolbar-divider {:aria-hidden "true"}]
    (search-box)
    (scope-toggle counts)
    (count-chip "Uncategorized" "uncat" "count-uncategorized" (:uncategorized counts))
    (count-chip "Hide transfers" "hideTransfers" "count-transfers" (:transfers-hidden counts))]
   [:div.toolbar-actions (column-picker)]])

(defn- empty-state []
  [:div.empty-state
   [:div.empty-state-title "No transactions this month"]
   [:p "Use the month controls to browse another period, or import transactions "
    "from the Setup page."]])

(defn- reviewed-signals
  "Initial `reviewed` signal map for the month's rows: {tx<id> bool} for normal
   transactions, {sp<id> bool} per split part (split parents have no checkbox)."
  [txs]
  (into {}
        (mapcat (fn [tx]
                  (if-let [parts (seq (:transaction/splits tx))]
                    (map (fn [p] [(keyword (str "sp" (:db/id p)))
                                  (boolean (:split/reviewed p))])
                         parts)
                    [[(keyword (str "tx" (:db/id tx)))
                      (boolean (:transaction/reviewed tx))]]))
                txs)))

(defn- unsplit [txs] (remove #(seq (:transaction/splits %)) txs))

(defn- unsplit-signal-map
  "Build a {tx<id> (value-fn tx)} signal map over the month's normal (unsplit) rows —
   the shape the per-row desc/cat signal namespaces share. (reviewed differs: it keys
   split parts individually, so it builds its own map.)"
  [txs value-fn]
  (into {} (for [tx (unsplit txs)]
             [(keyword (str "tx" (:db/id tx))) (value-fn tx)])))

(defn- description-signals
  "Initial `desc` signal map {tx<id> effective-description} — what the inline
   description editor binds to. Split memos edit in 3e."
  [txs]
  (unsplit-signal-map txs #(or (not-empty (:transaction/effective-description %)) "")))

(defn- category-signals
  "Initial `cat` signal map {tx<id> category-id-or-\"\"} — the hidden bound input the
   combobox island writes to persist a category change. Ids are stringified so Datastar
   types the signal as a string: a numeric seed would make `data-bind` coerce a cleared
   value to 0 (truthy server-side) instead of \"\"."
  [txs]
  (unsplit-signal-map txs #(if-let [cid (get-in % [:transaction/category :db/id])] (str cid) "")))

;; ---------------------------------------------------------------------------
;; Page
;; ---------------------------------------------------------------------------

(defn page
  "Factory: GET / handler. Renders the month's transactions table."
  [{:keys [db-conn]}]
  (fn [req]
    (let [m (month/parse (get-in req [:query-params "month"]))
          month-str (month/serialize m)
          txs (db-transactions/list-for-month db-conn month-str)
          counts (db-transactions/month-counts txs)
          stats (db-stats/entity-counts db-conn)
          categories (db-categories/list-all db-conn)]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body
       (layout/base-page
        {:title "Finance Aggregator"
         :islands ["combobox" "grid-nav"]
         :signals {:reviewed (reviewed-signals txs)
                   :desc (description-signals txs)
                   :descOrig ""
                   :cat (category-signals txs)
                   :cols (into {} (map (fn [[id _]] [(keyword id) true]) hideable-columns))
                   :colsOpen false
                   :month month-str
                   :search ""
                   :scope "all"
                   :hideTransfers false
                   :uncat false}}
        [:div.container.container--workspace
         (shell/masthead {:active :transactions :stats stats})
         [:div.transactions-layout
          [:div.card
           (toolbar m counts)
           (if (empty? txs)
             (empty-state)
             (transactions-table txs))]]
         (category-options categories)])})))

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
          ;; Pin the row when categorizing it under an active category filter (uncat chip
          ;; or funnel) so it lingers in place; then persist. Cleared on the next filter change.
          "data-on:change" (str "($uncat || $filter.category.filter(x=>x).length > 0)"
                                 " && ($linger.tx" tx-id " = true); "
                                 "@put('/transactions/" tx-id "/category')")})]))

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
  [signal tx-id]
  [:input.reviewed-checkbox
   (h/a {"type" "checkbox"
         "data-bind" signal
         ;; Pin the row when reviewing it in the needs-review queue so it lingers (stale)
         ;; in place rather than vanishing mid-task; the next filter change clears all pins.
         "data-on:click" (str "$scope === 'needs-review' && ($linger.tx" tx-id " = true)")
         "data-on:change__debounce.700ms" "@put('/transactions/reviewed')"})])

(defn- split-icon [drift?]
  [:svg {:class (str "split-icon" (when drift? " split-icon-drift"))
         :width "14" :height "14" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden "true"}
   [:title (if drift? "Split parts no longer add up to the amount" "Part of a split")]
   [:polyline {:points "15 10 20 15 15 20"}]
   [:path {:d "M4 4v7a4 4 0 0 0 4 4h12"}]])

;; ---------------------------------------------------------------------------
;; Header-filter funnels — option lists
;; ---------------------------------------------------------------------------
;; The Account / Institution / Category headers carry a multi-select filter funnel.
;; Its option list (value + label + a count of matching rows this month) is computed
;; server-side from the month's transactions — static per load, like React's
;; extractFilterOptions/buildCategoryOptions. The category list is split-aware (a tx
;; contributes each distinct category its parts touch) and omits the uncategorized
;; tokens, which the toolbar chip owns.

(defn- tx-account-id [tx] (get-in tx [:transaction/account :db/id]))
(defn- tx-institution-id [tx] (get-in tx [:transaction/account :account/institution :db/id]))

(defn- tx-category-ids
  "Distinct real category ids a transaction touches (split-aware): each split part's
   category, or the unsplit tx's category. Categoryless parts contribute nothing — the
   Uncategorized chip owns those."
  [tx]
  (vec (distinct
        (if-let [parts (seq (:transaction/splits tx))]
          (keep #(get-in % [:split/category :db/id]) parts)
          (when-let [id (get-in tx [:transaction/category :db/id])] [id])))))

(defn- count-options
  "Sorted [{:id :label :count}] from txs, counting transactions per id via id-fn/label-fn."
  [txs id-fn label-fn]
  (->> txs
       (keep (fn [tx] (when-let [id (id-fn tx)] [id (label-fn tx)])))
       (reduce (fn [m [id label]]
                 (-> m (assoc-in [id :label] label) (update-in [id :count] (fnil inc 0))))
               {})
       (map (fn [[id {:keys [label count]}]] {:id id :label label :count count}))
       (sort-by :label)))

(defn- account-options [txs]
  (count-options txs tx-account-id
                 #(or (get-in % [:transaction/account :account/external-name]) "Unknown")))

(defn- institution-options [txs]
  (count-options txs tx-institution-id
                 #(or (get-in % [:transaction/account :account/institution :institution/name]) "Unknown")))

(defn- category-funnel-options
  "Funnel options for the Category column: real categories present this month with the
   number of transactions touching each (split-aware). Uncategorized is excluded (the
   toolbar chip owns it, matching React)."
  [txs]
  (let [name-of (fn [tx]
                  (concat
                   (when-let [c (:transaction/category tx)]
                     (when (:db/id c) [[(:db/id c) (:category/name c)]]))
                   (mapcat (fn [p] (when-let [c (:split/category p)]
                                     (when (:db/id c) [[(:db/id c) (:category/name c)]])))
                           (:transaction/splits tx))))
        names (into {} (mapcat name-of txs))
        counts (frequencies (mapcat tx-category-ids txs))]
    (->> counts
         (map (fn [[id n]] {:id id :label (get names id "Unknown") :count n}))
         (sort-by :label))))

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

(defn- column-filter-clause
  "Per-row clause for a checkbox-array funnel (account/institution): show when nothing is
   selected, or this row's value is among the selection. Datastar seeds an empty string
   per unchecked box, so `.filter(x=>x)` drops the empties before testing emptiness
   (migration §6)."
  [signal value]
  (when value
    (str " && ($" signal ".filter(x=>x).length === 0 || $" signal ".includes('" value "'))")))

(def ^:private funnel-active-expr "$filter.category.filter(x=>x).length > 0")

(defn- category-show-clause
  "Category-funnel ∪ Uncategorized-chip clause, split-aware. Composes the multi-select
   category funnel with the Uncategorized chip as a union (matching React's single
   category-filter array): a row shows when neither is active, OR it touches a selected
   category, OR (it is uncategorized AND the chip is on). Normal rows read the LIVE
   category signal ($cat.tx<id>) so categorizing immediately re-filters (and lingering
   keeps the row in view) — split parts aren't editable yet, so they use baked tokens."
  [tx]
  (if (seq (:transaction/splits tx))
    (let [tokens  (tx-category-ids tx)
          literal (str "[" (str/join "," (map #(str "'" % "'") tokens)) "]")]
      (str " && ((!(" funnel-active-expr ") && !$uncat)"
           " || (" funnel-active-expr " && " literal ".some(t => $filter.category.includes(t)))"
           (when (db-transactions/needs-category? tx) " || $uncat")
           ")"))
    (let [cat (str "$cat.tx" (:db/id tx))]
      (str " && ((!(" funnel-active-expr ") && !$uncat)"
           " || (" funnel-active-expr " && " cat " !== '' && $filter.category.includes(" cat "))"
           " || ($uncat && " cat " === ''))"))))

(defn- match-expr
  "JS expression true when a transaction's row matches every active toolbar filter +
   header funnel. The lingering layer ORs $linger on top of this (see row-attrs)."
  [tx]
  (str "($search === '' || " (h/js-str (search-haystack tx)) ".includes($search.toLowerCase()))"
       " && ($scope === 'all' || !(" (reviewed-expr tx) "))"
       (when (:transaction/transfer-hidden tx) " && !$hideTransfers")
       (column-filter-clause "filter.account" (tx-account-id tx))
       (column-filter-clause "filter.institution" (tx-institution-id tx))
       (category-show-clause tx)))

(defn- linger-key
  "The $linger signal pinning a transaction in view after an edit moves it out of an
   active filter (split parts share their parent transaction's pin)."
  [tx]
  (str "$linger.tx" (:db/id tx)))

(defn- row-attrs
  "data-show + is-stale for a transaction's row(s). A row shows if it matches the live
   filters OR it's pinned ($linger) — an edit (review / categorize) pins the row under an
   active edit-prone filter so it lingers in place instead of vanishing, until the next
   filter change clears all pins. It reads as `is-stale` (de-emphasized) while pinned but
   no longer matching."
  [tx]
  (let [m (match-expr tx) l (linger-key tx)]
    (h/a {"data-show" (str "(" m ") || " l)
          "data-class" (str "{'is-stale': " l " && !(" m ")}")})))

(defn- category-moved-mark
  "The '→' breadcrumb on a stale row's category cell: shown when the row is pinned, no
   longer matches, AND a category filter is active (it was categorized out of view). The
   category cell already shows the new category, so the arrow reads as 'moved to …'."
  [tx]
  (let [m (match-expr tx) l (linger-key tx)]
    [:span.category-moved-mark
     (h/a {"data-show" (str l " && !(" m ") && ($uncat || " funnel-active-expr ")")
           "aria-hidden" "true"})
     "→ "]))

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

(defn- sort-keys
  "Numeric sort keys baked on a transaction's leader <tr> for the client sort island
   (date as epoch ms, amount as a number). The string columns (account/institution/
   payee/category) are read from the rendered cell text instead — so arbitrary text
   can't break an attribute (Replicant doesn't escape quotes in attribute values)."
  [{:transaction/keys [posted-date amount]}]
  {:data-sort-date (str (if posted-date (.getTime ^java.util.Date posted-date) 0))
   :data-sort-amount (str (or amount 0))})

(defn- normal-row [attrs {:transaction/keys [posted-date account payee effective-description
                                             amount category] :as tx}]
  [:tr (merge attrs {:role "row"} (sort-keys tx))
   [:td [:span.numeric (fmt/date posted-date)]]
   [:td (or (:account/external-name account) "—")]
   [:td (or (get-in account [:account/institution :institution/name]) "—")]
   [:td payee]
   [:td.description-cell.editable (grid-cell (:db/id tx) nil "description")
    (editable-description (:db/id tx) effective-description)]
   [:td.amount-cell (amount-span amount false)]
   [:td.category-cell (grid-cell (:db/id tx) nil "category")
    [:div.category-cell-row
     (category-moved-mark tx)
     (editable-category (:db/id tx) category)
     (transfer-status tx)]]
   [:td.reviewed-cell (grid-cell (:db/id tx) nil "reviewed")
    (reviewed-checkbox (str "reviewed.tx" (:db/id tx)) (:db/id tx))]
   [:td.actions-cell]
   [:td.table-spacer-cell {:aria-hidden "true"}]])

(defn- split-parent-row [attrs {:transaction/keys [posted-date account payee effective-description] :as tx}]
  [:tr (merge attrs {:role "row" :class "is-split-parent"} (sort-keys tx))
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

(defn- split-child-row [attrs parent-tx-id drift? last? {:split/keys [amount memo category] :as part}]
  [:tr (merge attrs {:role "row" :class (str "split-child-row" (when last? " is-last"))})
   [:td] [:td] [:td] [:td]
   [:td.description-cell
    [:span.split-description (split-icon drift?) (description-button memo)]]
   [:td.amount-cell (amount-span amount true)]
   [:td.category-cell
    [:div.category-cell-content
     [:button.category-button {:type "button" :tabindex "-1" :title "Edit split"}
      (or (:category/name category) "Uncategorized")]]]
   [:td.reviewed-cell (reviewed-checkbox (str "reviewed.sp" (:db/id part)) parent-tx-id)]
   [:td]
   [:td.table-spacer-cell {:aria-hidden "true"}]])

(defn- tx-rows
  "Expand a transaction into its table row(s): one normal row, or a split parent +
   one row per ordered part. All rows of a split share the same data-show + linger pin so
   they filter and linger together."
  [tx]
  (let [attrs (row-attrs tx)]
    (if-let [parts (seq (:transaction/splits tx))]
      (let [sorted (sort-by :split/order parts)
            drift? (false? (:transaction/splits-balanced tx))
            last-idx (dec (count sorted))]
        (into [(split-parent-row attrs tx)]
              (map-indexed (fn [i p] (split-child-row attrs (:db/id tx) drift? (= i last-idx) p)) sorted)))
      [(normal-row attrs tx)])))

;; ---------------------------------------------------------------------------
;; Table + chrome
;; ---------------------------------------------------------------------------

(defn- th-class
  ([id] (th-class id "th-static"))
  ([id modifier] (->> [(cell-class id) modifier] (remove nil?) (str/join " "))))

(def ^:private sortable-cols
  "Columns the client sort island sorts by (clicking the header). Reviewed/actions aren't."
  #{"date" "account" "institution" "payee" "amount" "category"})

(def ^:private resizable-cols
  "Columns the resize island gives a drag handle. Reviewed/actions are fixed-width."
  #{"date" "account" "institution" "payee" "description" "amount" "category"})

(defn- resize-handle []
  [:div.col-resize-handle {:aria-hidden "true"}])

;; --- Header-filter funnels (UI) --------------------------------------------
;; Each funnel column's header carries a small funnel button that opens a floating
;; popover (rendered separately, outside the scroll container — see funnel-popovers).
;; The button is `position:static` inside the sortable th; `__stop` keeps its click from
;; also triggering the column sort. It sets $openFunnel + the popover's rect coords. The
;; selection signal ($filter.<col>) is a checkbox-array, so its active count drops the
;; empty placeholders Datastar seeds per unchecked box.

(def ^:private funnel-cols #{"account" "institution" "category"})

(defn- funnel-icon []
  [:svg.th-filter-icon {:width "12" :height "12" :viewBox "0 0 24 24" :fill "none"
                        :stroke "currentColor" :stroke-width "2" :stroke-linecap "round"
                        :stroke-linejoin "round" :aria-hidden "true"}
   [:polygon {:points "22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"}]])

(defn- funnel-open-js
  "Toggle this funnel open (closing any other) and anchor the popover under the button.
   Coords are viewport-relative for the position:fixed popover; left is clamped so a
   right-edge column's menu stays on screen."
  [col]
  (str "$openFunnel = ($openFunnel === '" col "' ? '' : '" col "'); $funnelQuery = ''; "
       "$funnelX = Math.max(8, Math.min(el.getBoundingClientRect().left, window.innerWidth - 300)); "
       "$funnelY = el.getBoundingClientRect().bottom + 4"))

(defn- funnel-button [col label]
  (let [active (str "$filter." col ".filter(x=>x).length")]
    [:button.th-filter-btn
     (h/a {"type" "button" "aria-haspopup" "dialog" "aria-label" (str "Filter " label)
           "aria-expanded" "false"
           "data-on:click__stop" (funnel-open-js col)
           ;; Resolve to the literal "true"/"false" string (not a bare boolean, which
           ;; Datastar renders as aria-expanded="" — invalid for assistive tech).
           "data-attr" (str "{'aria-expanded': $openFunnel === '" col "' ? 'true' : 'false'}")
           "data-class" (str "{'is-active': " active " > 0}")})
     (funnel-icon)
     [:span.th-filter-count
      (h/a {"data-show" (str active " > 0") "data-text" active})]]))

(defn- th-cell [{:keys [id label]}]
  ;; The visible header parts live in a flex .th-content row so the label truncates and the
  ;; sort indicator + filter funnel stay pinned + clickable when a column shrinks to its
  ;; minimum (fixed layout). The resize handle is an absolute sibling, outside that row.
  (if (sortable-cols id)
    [:th (h/a {"class" (th-class id "th-sortable")
               "data-col-id" id "data-sort-col" id "aria-sort" "none"})
     [:span.th-content
      [:span.th-label label]
      [:span.th-sort-indicator {:aria-hidden "true"}]
      (when (funnel-cols id) (funnel-button id label))]
     (when (resizable-cols id) (resize-handle))]
    [:th (h/a {"class" (th-class id) "data-col-id" id})
     [:span.th-content [:span.th-label label]]
     (when (resizable-cols id) (resize-handle))]))

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
  ;; .table-resizable = table-layout:fixed, so the <colgroup> widths are authoritative.
  ;; The server renders a content-fit estimate per <col> (below) for the pre-JS / no-JS
  ;; view; the col-resize island refines them on load and on each container/visibility
  ;; change, and the drag handles let the user override per column.
  [:div.transactions-table-scroll {:tabindex "0"}
   [:table.table.table-resizable (h/a {"role" "grid" "data-class" (cols-hide-class)})
    [:colgroup
     (concat
      (for [{:keys [id label]} columns]
        [:col {:style (str "width:" (col-width id label txs) "px")}])
      [[:col.table-spacer-col]])]
    [:thead
     [:tr
      (for [c columns] (th-cell c))
      [:th.table-spacer-cell {:aria-hidden "true"}]]]
    [:tbody (mapcat tx-rows txs)]]])

(defn- month-nav-link
  "A prev/next month anchor. The href is the plain `/?month=` (no-JS fallback); with JS, the
   click instead swaps only the `month` param on the current URL, so the active view-state
   (which the url-state island keeps in the query string) carries across the month change."
  [target label title]
  (let [m-str (month/serialize target)]
    [:a.button.button-secondary.month-nav-button
     (h/a {"href" (str "/?month=" m-str) "title" title
           "data-on:click" (str "evt.preventDefault(); const u = new URL(location.href); "
                                 "u.searchParams.set('month', '" m-str "'); u.searchParams.delete('page'); "
                                 "location.assign(u.pathname + '?' + u.searchParams.toString())")})
     label]))

(defn- month-navigator [m]
  [:div.month-navigator
   [:div.month-navigator-controls
    (month-nav-link (month/prev-month m) "‹" "Previous month")
    [:span.month-navigator-display (month/display m)]
    (month-nav-link (month/next-month m) "›" "Next month")]])

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

(defn- funnel-popover
  "One header-filter popover (floating, position:fixed via .header-filter-popover so it
   escapes the table's scroll overflow — same approach as the 3c combobox). Rendered
   outside the table so clicks inside don't bubble to the sort handler on <thead>. Shown
   for its column via $openFunnel; closes on an outside click, but only when it's the open
   one (so clicking inside another funnel doesn't close it). The in-menu search filters the
   options client-side; each checkbox binds the $filter.<col> array; Clear empties it."
  [col options]
  [:div.header-filter-popover
   (h/a {"data-show" (str "$openFunnel === '" col "'")
         "data-style" "{left: $funnelX + 'px', top: $funnelY + 'px'}"
         "data-on:click__outside" (str "$openFunnel === '" col "' && ($openFunnel = '')")})
   [:div.filter-dropdown.filter-dropdown--bare
    [:div.filter-dropdown-header
     [:input.filter-dropdown-search
      (h/a {"type" "search" "placeholder" "Search…" "data-bind" "funnelQuery"
            "aria-label" "Filter options"})]]
    [:ul.filter-dropdown-list
     (if (empty? options)
       [:li.filter-dropdown-item.empty "No values"]
       (for [{:keys [id label count]} options]
         [:li.filter-dropdown-item
          (h/a {"data-show" (str "$funnelQuery === '' || "
                                 (h/js-str (str/lower-case label)) ".includes($funnelQuery.toLowerCase())")})
          [:label.filter-dropdown-checkbox-label
           [:input.filter-dropdown-checkbox
            (h/a {"type" "checkbox" "value" (str id) "data-bind" (str "filter." col)})]
           [:span.filter-dropdown-label-text label]
           [:span.filter-dropdown-count count]]]))]
    [:div.filter-dropdown-footer
     [:button.button.button-secondary.filter-dropdown-clear
      (h/a {"type" "button" "data-on:click" (str "$filter." col " = []")}) "Clear"]]]])

(defn- funnel-popovers [account-opts institution-opts category-opts]
  (list
   (funnel-popover "account" account-opts)
   (funnel-popover "institution" institution-opts)
   (funnel-popover "category" category-opts)))

(defn- linger-reset
  "On any filter change (search / scope / chips / funnels), clears the lingering-row pins
   AND jumps back to the first page (matching React's setPage(0) on filter changes). The
   signal-patch filter scopes it to the filter signals so an edit (which patches
   reviewed / cat / linger) never triggers it — only a genuine filter change does. Pins are
   reset to the all-false map (not {}) so each $linger.tx<id> keeps existing — a removed key
   would lose its reactive binding for the next edit (Datastar tracks signals that exist
   when an expression first runs)."
  [empty-literal]
  [:div.linger-reset
   (h/a {"hidden" "true"
         "data-on-signal-patch-filter" "{include: /^(search|scope|hideTransfers|uncat|filter\\.)/}"
         "data-on-signal-patch" (str "$linger = " empty-literal "; $page = 0")})])

(defn- url-sync
  "Write side of URL view-state: reflect the persisted filter/visibility signals into the
   query string on change (the url-state island's window.__syncUrl owns the serialization;
   sort.ts owns the `sort` param). Scoped by the signal-patch filter to the persisted
   signals so it ignores edit + ephemeral-UI signals."
  []
  [:div.url-sync
   (h/a {"hidden" "true"
         "data-on-signal-patch-filter" "{include: /^(search|scope|hideTransfers|uncat|page|pageSize)$|^(filter|cols)\\./}"
         "data-on-signal-patch"
         (str "window.__syncUrl && window.__syncUrl({month: $month, q: $search, scope: $scope,"
              " ht: $hideTransfers, uncat: $uncat, fa: $filter.account, fi: $filter.institution,"
              " fc: $filter.category, cols: $cols, page: $page, pageSize: $pageSize})")})])

;; ---------------------------------------------------------------------------
;; Pagination
;; ---------------------------------------------------------------------------
;; Client-side, downstream of filtering (filter → paginate, matching React): the pagination
;; island slices the filter-visible row-groups to the current page via a `.page-hidden`
;; class. These controls only drive $page / $pageSize; the island computes $pageCount and
;; clamps an out-of-range $page (paginate-clamp event). $page is 0-indexed in the signal,
;; 1-indexed in the URL.

(def ^:private page-size-options [25 50 100 250])

(defn- pagination-bar []
  [:div.pagination
   [:div.pagination-size-controls
    (for [n page-size-options]
      [:button.button.pagination-size-button
       (h/a {"type" "button"
             "data-on:click" (str "$pageSize = " n "; $page = 0")
             "data-class" (str "{'button-primary': $pageSize === " n ", 'button-secondary': $pageSize !== " n "}")})
       (str n)])]
   [:div.pagination-navigation
    [:button.button.button-secondary.pagination-nav-button
     (h/a {"type" "button" "title" "First page" "aria-label" "First page"
           "data-on:click" "$page = 0" "data-attr" "{disabled: $page === 0}"}) "«"]
    [:button.button.button-secondary.pagination-nav-button
     (h/a {"type" "button" "title" "Previous page" "aria-label" "Previous page"
           "data-on:click" "$page = Math.max(0, $page - 1)" "data-attr" "{disabled: $page === 0}"}) "‹"]
    [:span.pagination-status (h/a {"data-text" "'Page ' + ($page + 1) + ' of ' + $pageCount"}) "Page 1 of 1"]
    [:button.button.button-secondary.pagination-nav-button
     (h/a {"type" "button" "title" "Next page" "aria-label" "Next page"
           "data-on:click" "$page = Math.min($pageCount - 1, $page + 1)"
           "data-attr" "{disabled: $page >= $pageCount - 1}"}) "›"]
    [:button.button.button-secondary.pagination-nav-button
     (h/a {"type" "button" "title" "Last page" "aria-label" "Last page"
           "data-on:click" "$page = $pageCount - 1" "data-attr" "{disabled: $page >= $pageCount - 1}"}) "»"]]])

(defn- pagination-bridges
  "Hidden elements bridging the pagination island and Datastar: one recomputes the page
   slice when a paginating signal changes (scoped so it ignores pageCount + edit signals);
   the other receives the island's clamped page + computed count as numbers."
  []
  (list
   [:div.pagination-sync
    (h/a {"hidden" "true"
          "data-on-signal-patch-filter" "{include: /^(page|pageSize|search|scope|hideTransfers|uncat)$|^(filter|linger)\\./}"
          "data-on-signal-patch" "window.__paginate && window.__paginate($page, $pageSize)"})]
   [:div.pagination-clamp
    (h/a {"hidden" "true"
          "data-on:paginate-clamp__window" "$page = evt.detail.page; $pageCount = evt.detail.count"})]))

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

(defn- linger-signals
  "Initial `linger` signal map {tx<id> false} over every transaction. Pre-declaring each
   key (rather than starting from {}) is what makes the per-row is-stale / data-show
   expressions reactive to a later pin: Datastar only tracks signals that exist when an
   expression first runs, so a key created on the fly wouldn't notify the row. (Reused as
   the reset value via h/signals — see linger-reset.)"
  [txs]
  (into {} (for [tx txs] [(keyword (str "tx" (:db/id tx))) false])))

;; ---------------------------------------------------------------------------
;; URL view-state (read side)
;; ---------------------------------------------------------------------------
;; The query string carries the view (search / scope / chips / funnels / column
;; visibility) so a reload or shared link restores it ([[feedback_url_view_state]]).
;; This parses those params into the initial signal values; the url-state + sort islands
;; write them back as the user interacts. Sort is applied client-side by sort.ts.

(defn- csv-param [qp k]
  (let [v (get qp k)]
    (if (str/blank? v) [] (str/split v #","))))

(defn- parse-view-state [qp]
  (let [hidden  (set (csv-param qp "hidecols"))
        page-raw (some-> (get qp "page") parse-long)
        ps-raw   (some-> (get qp "pageSize") parse-long)]
    {:search        (or (get qp "q") "")
     :scope         (if (= "needs-review" (get qp "scope")) "needs-review" "all")
     :hideTransfers (= "1" (get qp "ht"))
     :uncat         (= "1" (get qp "uncat"))
     :filter        {:account     (csv-param qp "fa")
                     :institution (csv-param qp "fi")
                     :category    (csv-param qp "fc")}
     :cols          (into {} (map (fn [[id _]] [(keyword id) (not (contains? hidden id))])
                                   hideable-columns))
     ;; URL page is 1-indexed; the signal is 0-indexed. pageSize accepts any positive int
     ;; (the buttons offer 25/50/100/250, but an arbitrary URL value still slices fine).
     :page          (if (and page-raw (> page-raw 1)) (dec page-raw) 0)
     :pageSize      (if (and ps-raw (pos? ps-raw)) ps-raw 25)}))

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
          categories (db-categories/list-all db-conn)
          account-opts (account-options txs)
          institution-opts (institution-options txs)
          category-opts (category-funnel-options txs)
          vs (parse-view-state (:query-params req))
          ;; Seed pagination so the controls are correct on load without waiting for the
          ;; island (no-filter load = all txs visible). The island recomputes the count for
          ;; a filtered view on first interaction. Counts whole transactions (a split = 1).
          total-pages (max 1 (long (Math/ceil (/ (count txs) (double (:pageSize vs))))))
          page-clamped (min (:page vs) (dec total-pages))]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body
       (layout/base-page
        {:title "Finance Aggregator"
         :islands ["combobox" "grid-nav" "sort" "col-resize" "url-state" "pagination"]
         :signals {:reviewed (reviewed-signals txs)
                   :desc (description-signals txs)
                   :descOrig ""
                   :cat (category-signals txs)
                   :cols (:cols vs)
                   :colsOpen false
                   :filter (:filter vs)
                   :openFunnel ""
                   :funnelX 0
                   :funnelY 0
                   :funnelQuery ""
                   :linger (linger-signals txs)
                   :month month-str
                   :search (:search vs)
                   :scope (:scope vs)
                   :hideTransfers (:hideTransfers vs)
                   :uncat (:uncat vs)
                   :page page-clamped
                   :pageSize (:pageSize vs)
                   :pageCount total-pages}}
        [:div.container.container--workspace
         (shell/masthead {:active :transactions :stats stats})
         [:div.transactions-layout
          [:div.card
           (toolbar m counts)
           (if (empty? txs)
             (empty-state)
             (list (transactions-table txs) (pagination-bar)))]]
         (when (seq txs)
           (list
            (funnel-popovers account-opts institution-opts category-opts)
            (linger-reset (h/signals (linger-signals txs)))
            (url-sync)
            (pagination-bridges)))
         (category-options categories)])})))

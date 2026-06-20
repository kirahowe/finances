(ns finance-aggregator.web.pages.transactions
  "Server-rendered transactions workspace (/). Phase 3a is the read-only
   foundation: the full 9-column table (normal + split rows, signed amounts,
   sticky header) for a month, plus month navigation. The interaction layers
   (reviewed toggle, search/filters, inline edit, combobox, grid-nav, sorting,
   pagination, splits, transfers, rollup) are added in later phases."
  (:require
   [clojure.string :as str]
   [finance-aggregator.db.stats :as db-stats]
   [finance-aggregator.db.transactions :as db-transactions]
   [finance-aggregator.web.format :as fmt]
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

(defn- reviewed-checkbox [reviewed?]
  ;; Display-only in Phase 3a; the optimistic Datastar toggle is wired in 3b.
  [:input.reviewed-checkbox (cond-> {:type "checkbox"} reviewed? (assoc :checked true))])

(defn- split-icon [drift?]
  [:svg {:class (str "split-icon" (when drift? " split-icon-drift"))
         :width "14" :height "14" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden "true"}
   [:title (if drift? "Split parts no longer add up to the amount" "Part of a split")]
   [:polyline {:points "15 10 20 15 15 20"}]
   [:path {:d "M4 4v7a4 4 0 0 0 4 4h12"}]])

;; ---------------------------------------------------------------------------
;; Rows
;; ---------------------------------------------------------------------------

(defn- normal-row [{:transaction/keys [posted-date account payee effective-description
                                       amount category reviewed] :as tx}]
  [:tr
   [:td [:span.numeric (fmt/date posted-date)]]
   [:td (or (:account/external-name account) "—")]
   [:td (or (get-in account [:account/institution :institution/name]) "—")]
   [:td payee]
   [:td.description-cell (description-button effective-description)]
   [:td.amount-cell (amount-span amount false)]
   [:td.category-cell
    [:div.category-cell-row
     [:button.category-button {:type "button" :tabindex "-1"}
      (or (:category/name category) "Uncategorized")]
     (transfer-status tx)]]
   [:td.reviewed-cell (reviewed-checkbox reviewed)]
   [:td.actions-cell]
   [:td.table-spacer-cell {:aria-hidden "true"}]])

(defn- split-parent-row [{:transaction/keys [posted-date account payee effective-description]}]
  [:tr.is-split-parent
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

(defn- split-child-row [drift? last? {:split/keys [amount memo category reviewed]}]
  [:tr {:class (str "split-child-row" (when last? " is-last"))}
   [:td] [:td] [:td] [:td]
   [:td.description-cell
    [:span.split-description (split-icon drift?) (description-button memo)]]
   [:td.amount-cell (amount-span amount true)]
   [:td.category-cell
    [:div.category-cell-content
     [:button.category-button {:type "button" :tabindex "-1" :title "Edit split"}
      (or (:category/name category) "Uncategorized")]]]
   [:td.reviewed-cell (reviewed-checkbox reviewed)]
   [:td]
   [:td.table-spacer-cell {:aria-hidden "true"}]])

(defn- tx-rows
  "Expand a transaction into its table row(s): one normal row, or a split parent +
   one row per ordered part."
  [tx]
  (if-let [parts (seq (:transaction/splits tx))]
    (let [sorted (sort-by :split/order parts)
          drift? (false? (:transaction/splits-balanced tx))
          last-idx (dec (count sorted))]
      (into [(split-parent-row tx)]
            (map-indexed (fn [i p] (split-child-row drift? (= i last-idx) p)) sorted)))
    [(normal-row tx)]))

;; ---------------------------------------------------------------------------
;; Table + chrome
;; ---------------------------------------------------------------------------

(defn- th-class [id]
  (->> [(cell-class id) "th-static"] (remove nil?) (str/join " ")))

(defn- transactions-table [txs]
  ;; Plain .table (table-layout:auto, content-fit) for now; Phase 3d switches to
  ;; .table-resizable (fixed layout) once the resize/auto-size island sets <col> widths.
  [:div.transactions-table-scroll {:tabindex "0"}
   [:table.table {:role "grid"}
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

(defn- toolbar [m]
  [:div.toolbar
   [:div.toolbar-controls (month-navigator m)]])

(defn- empty-state []
  [:div.empty-state
   [:div.empty-state-title "No transactions this month"]
   [:p "Use the month controls to browse another period, or import transactions "
    "from the Setup page."]])

;; ---------------------------------------------------------------------------
;; Page
;; ---------------------------------------------------------------------------

(defn page
  "Factory: GET / handler. Renders the month's transactions table."
  [{:keys [db-conn]}]
  (fn [req]
    (let [m (month/parse (get-in req [:query-params "month"]))
          txs (db-transactions/list-for-month db-conn (month/serialize m))
          stats (db-stats/entity-counts db-conn)]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body
       (layout/base-page
        {:title "Finance Aggregator"}
        [:div.container.container--workspace
         (shell/masthead {:active :transactions :stats stats})
         [:div.transactions-layout
          [:div.card
           (toolbar m)
           (if (empty? txs)
             (empty-state)
             (transactions-table txs))]]])})))

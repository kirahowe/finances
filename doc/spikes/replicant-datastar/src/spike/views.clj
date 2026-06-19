(ns spike.views
  "Replicant hiccup views for the spike, rendered to HTML strings on the JVM.

  One server-rendered transactions table wired with Datastar attributes. Probes
  the interactivity the React app currently owns via TanStack Table / downshift:

    Round 1 (proven):
      1. Optimistic reviewed-toggle + debounced write-behind + server counts.
      2. Inline payee editing (click-to-edit, Enter commits optimistically).
      3. Client-side search filter (zero round-trips).
      4. Spreadsheet keyboard nav — JS island porting gridNavigation.ts.

    Round 2 (the TanStack/downshift features):
      5. Category combobox — type-ahead + keyboard nav, the downshift replacement
         (combobox.js island), persisted via the JSON API (not Datastar) to show
         the JSON API coexists.
      6. Column show/hide — Datastar signals + CSS, pure client.
      7. Header filter (by account) — Datastar array signal + data-show, pure client.
      8. Column resize / auto-fit drag — table-tools.js island (pointer events)."
  (:require [clojure.string :as str]
            [replicant.string :as rs]
            [spike.data :as data]))

;; ---------------------------------------------------------------------------
;; Datastar attribute helpers
;; ---------------------------------------------------------------------------
;; Datastar's v1 syntax uses colons in attribute names (data-on:click,
;; data-bind:foo, data-class:active). Clojure keyword literals can't carry those
;; cleanly, so we build attribute maps from string keys and coerce to keywords —
;; replicant.string renders an attribute keyword by its (name), colon preserved.

(defn- a [m]
  (reduce-kv (fn [acc k v] (assoc acc (if (string? k) (keyword k) k) v)) {} m))

;; replicant.string does NOT escape double quotes inside attribute values, so any
;; Datastar expression embedded in an attribute must avoid them. We quote strings
;; for JS with single quotes and escape any single quote/backslash in the data.
(defn- js-str [s]
  (str "'" (-> (str s) (str/replace "\\" "\\\\") (str/replace "'" "\\'")) "'"))

(defn cents->str [c]
  (let [neg? (neg? c)
        c (Math/abs (long c))]
    (str (when neg? "-") "$" (quot c 100) "." (format "%02d" (rem c 100)))))

(defn- row-key [tx-id split-id] (str tx-id ":" (or split-id "tx")))

;; ---------------------------------------------------------------------------
;; Columns
;; ---------------------------------------------------------------------------

(def columns
  [{:id "date" :label "Date"}
   {:id "description" :label "Description"}
   {:id "account" :label "Account" :filter true}
   {:id "category" :label "Category"}
   {:id "amount" :label "Amount" :amount true}
   {:id "reviewed" :label "✓"}])

;; ---------------------------------------------------------------------------
;; Cells
;; ---------------------------------------------------------------------------

(defn- payee-cell
  "Inline-editable description cell. Span + input swap on an `editing` class the
  grid island toggles; Enter persists via @put, Escape reverts — both in Datastar."
  [tx-id payee]
  (let [sig  (str "payee.tx" tx-id)
        ckey (row-key tx-id nil)]
    [:td.cell.editable.col-description {:data-cell (str ckey ":description") :tabindex "-1"}
     [:span.cell-view payee]
     [:input.cell-input
      (a {"value" payee
          "data-bind" sig
          "data-on:keydown"
          (str "evt.key==='Enter' && (el.previousElementSibling.textContent = el.value,"
               " @put('/tx/" tx-id "/payee'),"
               " el.closest('td').classList.remove('editing'), el.closest('td').focus());"
               "evt.key==='Escape' && (el.value = el.previousElementSibling.textContent,"
               " el.closest('td').classList.remove('editing'), el.closest('td').focus())")})]]))

(defn- category-cell
  "Category cell. `combo?` marks it for the combobox island (normal rows); a split
  child keeps a plain cell (in the app its category opens the split modal)."
  [ckey category combo?]
  [:td.cell.editable.col-category
   (cond-> {:data-cell (str ckey ":category") :tabindex "-1"}
     combo? (assoc :class "combo-cell"))
   [:span.cell-view (or category [:em.muted "—"])]])

(defn- reviewed-cell [rowkey sig]
  [:td.cell.editable.reviewed-cell.col-reviewed {:data-cell (str rowkey ":reviewed") :tabindex "-1"}
   [:button.check
    (a {"data-on:click" (str "$" sig " = !$" sig)
        "data-class"    (str "{checked: $" sig "}")
        "data-text"     (str "$" sig " ? '✓' : ''")
        "aria-label"    "reviewed"})]])

(defn- row-show
  "Combine the client-side search filter and the per-account header filter into one
  data-show expression. Empty search / no account selected = match all.
  NOTE: Datastar's checkbox-array binding seeds the signal with one empty string
  per unchecked box (e.g. ['','','']), so we count non-empty selections, not length."
  [haystack acct-id]
  (a {"data-show"
      (str "($search === '' || " (js-str haystack) ".includes($search.toLowerCase()))"
           " && ($acct.filter(x => x).length === 0 || $acct.includes(" (js-str acct-id) "))")}))

(defn- normal-row [{:keys [id date payee account category cents reviewed]}]
  (let [haystack (.toLowerCase (str payee " " category " " (data/account-name account)))
        ckey (row-key id nil)]
    [:tr.txrow (row-show haystack account)
     [:td.cell.ro.col-date date]
     (payee-cell id payee)
     [:td.cell.ro.col-account (data/account-name account)]
     (category-cell ckey category true)
     [:td.cell.ro.amount.col-amount {:class (when (neg? cents) "neg")} (cents->str cents)]
     (reviewed-cell ckey (str "reviewed.tx" id))]))

(defn- split-rows [{:keys [id date payee account cents splits]}]
  (let [haystack (.toLowerCase (str payee " split"))]
    (into
     [[:tr.txrow.split-parent (row-show haystack account)
       [:td.cell.ro.col-date date]
       (payee-cell id payee)
       [:td.cell.ro.col-account (data/account-name account)]
       [:td.cell.ro.col-category [:em.muted "split"]]
       [:td.cell.ro.amount.col-amount {:class (when (neg? cents) "neg")} (cents->str cents)]
       [:td.cell.ro.col-reviewed]]]
     (for [s splits
           :let [ckey (row-key id (:id s))]]
       [:tr.txrow.split-child (row-show haystack account)
        [:td.cell.ro.col-date]
        [:td.cell.ro.col-description [:span.tree "└"] (:memo s)]
        [:td.cell.ro.col-account]
        (category-cell ckey (:category s) false)
        [:td.cell.ro.amount.col-amount {:class (when (neg? (:cents s)) "neg")} (cents->str (:cents s))]
        (reviewed-cell ckey (str "reviewed.sp" (:id s)))]))))

;; ---------------------------------------------------------------------------
;; Toolbar controls (column picker) + header filter
;; ---------------------------------------------------------------------------

(defn- column-picker []
  [:div.dropdown
   ;; __stop so this open-click doesn't also register as a click *outside* the
   ;; menu (which would immediately close it) — a Datastar dropdown gotcha.
   [:button.btn (a {"data-on:click__stop" "$colsOpen = !$colsOpen"}) "Columns ▾"]
   [:div.menu (a {"data-show" "$colsOpen" "data-on:click__outside" "$colsOpen = false"})
    (for [{:keys [id label]} columns]
      [:label.menu-item
       [:input (a {"type" "checkbox" "data-bind" (str "cols." id)})]
       " " label])]])

(defn- account-filter []
  [:div.dropdown
   [:button.funnel (a {"data-on:click__stop" "$acctOpen = !$acctOpen"
                       "data-class" "{active: $acct.filter(x => x).length > 0}"}) "⏷"]
   [:div.menu (a {"data-show" "$acctOpen" "data-on:click__outside" "$acctOpen = false"})
    (for [{:keys [id name]} data/accounts]
      [:label.menu-item
       [:input (a {"type" "checkbox" "value" id "data-bind" "acct"})]
       " " name])]])

;; ---------------------------------------------------------------------------
;; Page
;; ---------------------------------------------------------------------------

(defn- initial-signals [txs]
  (let [pairs (mapcat (fn [{:keys [id reviewed splits]}]
                        (cons (str "tx" id ":" (boolean reviewed))
                              (map (fn [s] (str "sp" (:id s) ":" (boolean (:reviewed s))))
                                   splits)))
                      txs)
        ;; every column visible initially
        cols (str/join ", " (map #(str (:id %) ": true") columns))]
    (str "{reviewed: {" (str/join ", " pairs) "},"
         " cols: {" cols "},"
         " search: '', acct: [], colsOpen: false, acctOpen: false}")))

(defn counts-chip []
  (let [{:keys [reviewed total remaining]} (data/review-counts)]
    [:div#review-counts.chip
     [:strong (str reviewed)] " reviewed · "
     [:strong (str remaining)] " to review · " total " total"]))

(defn- table-hide-class
  "data-class on the table that hides a column when its cols.<id> signal is off."
  []
  (str "{" (str/join ", " (map #(str "'hide-" (:id %) "': !$cols." (:id %)) columns)) "}"))

(defn page
  "opts :combo — :zag loads the Zag.js TS island, else the hand-rolled JS one."
  [{:keys [combo]}]
  (let [txs (data/all-transactions)]
    (str
     "<!DOCTYPE html>"
     (rs/render
      [:html {:lang "en"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:title "Replicant + Datastar spike"]
        [:link {:rel "stylesheet" :href "/public/spike.css"}]
        [:script {:type "module" :src "/public/datastar.js"}]]
       [:body (a {"data-signals" (initial-signals txs)})
        [:header.topbar
         [:div
          [:h1 "Ledger spike " [:span.tag "Replicant SSR + Datastar SSE"]]
          [:p.sub "Server-rendered transactions table. No React. "
           [:span.muted "(in-memory fake data)"]]]
         (counts-chip)]

        [:section.controls
         [:input.search (a {"type" "search" "placeholder" "Search payee / category…"
                            "data-bind" "search"})]
         (column-picker)
         [:span.hint "↑↓←→/Tab move · Enter edits desc / opens category combobox · "
          "Space toggles · drag header edge to resize"]]

        ;; Write-behind: when any reviewed signal changes, persist 700ms later.
        [:div (a {"data-on-signal-patch__debounce.700ms" "@put('/sync-reviewed')"
                  "style" "display:none"})]

        [:main.tablewrap
         [:table#grid (a {"tabindex" "0" "data-class" (table-hide-class)})
          [:colgroup
           (for [{:keys [id]} columns]
             [:col (a {"data-col" id :class (str "col-" id)})])]
          [:thead
           [:tr
            (for [{:keys [id label amount filter]} columns]
              [:th (a {:class (str "col-" id (when amount " amount"))})
               [:span.th-label label]
               (when filter (account-filter))
               [:span.resize-handle (a {"data-col" id})]])]]
          [:tbody
           (mapcat (fn [t] (if (seq (:splits t)) (split-rows t) [(normal-row t)])) txs)]]]

        ;; Hidden category list the combobox island reads (avoids embedding JSON,
        ;; which Replicant would escape inside a <script>).
        [:ul#cat-options {:hidden true}
         (for [c data/categories] [:li c])]

        [:script {:type "module" :src "/public/grid-nav.js"}]
        [:script {:type "module" :src (if (= combo :zag) "/public/combobox-zag.js" "/public/combobox.js")}]
        [:script {:type "module" :src "/public/table-tools.js"}]]]))))

(ns spike.views
  "Replicant hiccup views for the spike, rendered to HTML strings on the JVM.

  The page is one server-rendered transactions table wired up with Datastar
  attributes. It bundles four experiments, each probing a different rung of the
  interactivity ladder the React app currently owns:

    1. Optimistic reviewed-toggle + debounced write-behind + server-authoritative
       counts  (the project's 'optimistic projection' pattern).
    2. Inline payee editing (click-to-edit, Enter commits optimistically).
    3. Client-side search filter (zero server round-trips).
    4. Spreadsheet keyboard navigation — the crux — driven by a JS island that
       reuses a near-verbatim port of the app's pure gridNavigation reducer."
  (:require [clojure.string :as str]
            [replicant.string :as rs]
            [spike.data :as data]))

;; ---------------------------------------------------------------------------
;; Datastar attribute helper
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

;; ---------------------------------------------------------------------------
;; Grid model for the keyboard-nav island (mirrors buildGridModel in the app)
;; ---------------------------------------------------------------------------

(defn- row-key [tx-id split-id] (str tx-id ":" (or split-id "tx")))

;; ---------------------------------------------------------------------------
;; Cells
;; ---------------------------------------------------------------------------

(defn- payee-cell
  "Inline-editable description cell (Experiment 2). A static span + an input live
  in the same <td>; the JS island toggles an `editing` class (CSS swaps them) and
  focuses the input. The input two-way-binds the optimistic `payee` signal so the
  span updates live; Enter persists via @put and exits, Escape just exits — both
  driven by Datastar, manipulating focus/class in the expression."
  [tx-id payee]
  (let [sig  (str "payee.tx" tx-id)
        ckey (row-key tx-id nil)]
    [:td.cell.editable {:data-cell (str ckey ":description") :tabindex "-1"}
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

(defn- reviewed-cell
  "Optimistic reviewed toggle (Experiment 1). Click flips the signal instantly;
  a single debounced write-behind element (below) syncs all changes. The server
  never patches this checkbox back — no per-toggle revalidation."
  [rowkey sig]
  [:td.cell.editable.reviewed-cell {:data-cell (str rowkey ":reviewed") :tabindex "-1"}
   [:button.check
    (a {"data-on:click" (str "$" sig " = !$" sig)
        "data-class"    (str "{checked: $" sig "}")
        "data-text"     (str "$" sig " ? '✓' : ''")
        "aria-label"    "reviewed"})]])

(defn- row-show [haystack]
  (a {"data-show" (str "$search === '' || " (js-str haystack) ".includes($search.toLowerCase())")}))

(defn- normal-row [{:keys [id date payee account category cents reviewed]}]
  (let [haystack (.toLowerCase (str payee " " category " " (data/account-name account)))
        ckey (row-key id nil)]
    [:tr.txrow (row-show haystack)
     [:td.cell.ro date]
     (payee-cell id payee)
     [:td.cell.ro (data/account-name account)]
     [:td.cell.editable {:data-cell (str ckey ":category") :tabindex "-1"}
      (or category [:em.muted "—"])]
     [:td.cell.ro.amount {:class (when (neg? cents) "neg")} (cents->str cents)]
     (reviewed-cell ckey (str "reviewed.tx" id))]))

(defn- split-rows [{:keys [id date payee account cents splits]}]
  (let [haystack (.toLowerCase (str payee " split"))]
    (into
     [[:tr.txrow.split-parent (row-show haystack)
       [:td.cell.ro date]
       (payee-cell id payee)
       [:td.cell.ro (data/account-name account)]
       [:td.cell.ro [:em.muted "split"]]
       [:td.cell.ro.amount {:class (when (neg? cents) "neg")} (cents->str cents)]
       [:td.cell.ro]]]
     (for [s splits
           :let [ckey (row-key id (:id s))]]
       [:tr.txrow.split-child (row-show haystack)
        [:td.cell.ro]
        [:td.cell.ro [:span.tree "└"] (:memo s)]
        [:td.cell.ro]
        [:td.cell.editable {:data-cell (str ckey ":category") :tabindex "-1"} (:category s)]
        [:td.cell.ro.amount {:class (when (neg? (:cents s)) "neg")} (cents->str (:cents s))]
        (reviewed-cell ckey (str "reviewed.sp" (:id s)))]))))

;; ---------------------------------------------------------------------------
;; Page
;; ---------------------------------------------------------------------------

(defn- initial-signals
  "Build Datastar's data-signals as a single-quoted JS object literal (the style
  the Datastar docs use, e.g. {user: {name: 'Alice'}}) rather than JSON — see
  js-str above for why double quotes can't appear in the attribute. Only booleans
  and an empty search string here, so no escaping needed for the values."
  [txs]
  (let [pairs (mapcat (fn [{:keys [id reviewed splits]}]
                        (cons (str "tx" id ":" (boolean reviewed))
                              (map (fn [s] (str "sp" (:id s) ":" (boolean (:reviewed s))))
                                   splits)))
                      txs)]
    (str "{reviewed: {" (str/join ", " pairs) "}, search: ''}")))

(defn counts-chip []
  (let [{:keys [reviewed total remaining]} (data/review-counts)]
    [:div#review-counts.chip
     [:strong (str reviewed)] " reviewed · "
     [:strong (str remaining)] " to review · " total " total"]))

(defn page []
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
          [:h1 "Ledger spike "
           [:span.tag "Replicant SSR + Datastar SSE"]]
          [:p.sub "Server-rendered transactions table. No React. "
           [:span.muted "(in-memory fake data)"]]]
         (counts-chip)]

        [:section.controls
         [:input.search (a {"type" "search" "placeholder" "Search payee / category…"
                            "data-bind" "search"})]
         [:span.hint "↑↓←→ / Tab to move · Enter edits description · Space toggles reviewed · type to edit"]]

        ;; Write-behind: when any reviewed signal changes, persist 700ms later.
        ;; Mirrors the app's debounced write-behind — one effect, not one per row.
        [:div (a {"data-on-signal-patch__debounce.700ms" "@put('/sync-reviewed')"
                  "style" "display:none"})]

        [:main.tablewrap
         [:table#grid {:tabindex "0"}
          [:thead
           [:tr
            [:th "Date"] [:th "Description"] [:th "Account"]
            [:th "Category"] [:th.amount "Amount"] [:th "✓"]]]
          [:tbody
           (mapcat (fn [t] (if (seq (:splits t)) (split-rows t) [(normal-row t)])) txs)]]]

        ;; The keyboard-nav island reconstructs its grid model from the DOM
        ;; ([data-cell] order) — no embedded JSON, which Replicant would escape.
        [:script {:type "module" :src "/public/grid-nav.js"}]]]))))

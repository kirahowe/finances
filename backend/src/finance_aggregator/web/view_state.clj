(ns finance-aggregator.web.view-state
  "Pure view-state codec for the server-authoritative transactions page.

   Three representations of the same persistent view state, and the pure maps between them:

     query-params (URL strings)  ─query->view-state─▶  view-state (web.view input map)
                                                              │
                                                     vs->signals / client-signals
                                                              ▼
     Datastar signals (client) ─signals->view-state─▶  initial signal map

   The view-state is exactly what `web.view/view` consumes; the signal map is the
   `data-signals` seed the page ships. Keeping all of this here (map→map, no hiccup, no db)
   means it is kaocha-testable in isolation — a real lingering bug once hid inside a private
   view fn and escaped the tests, so any data rearranging/parsing lives in tested pure fns.

   This namespace also owns the column configuration (`columns` / `hideable-columns` etc.),
   since the codec (`parse-cols`) and the page's rendering both need it — one home, no copy."
  (:require
   [charred.api :as json]
   [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Column configuration (shared by the codec here + the rendering in the page)
;; ---------------------------------------------------------------------------

(def columns
  "Ordered column metadata: render order, default width, sortability, min width, and whether
   the column is protected from hiding in the picker. Both the codec (column visibility) and
   the page (colgroup/headers/picker) read from this single source."
  [{:id "date"        :label "Date"        :w 120 :sortable true  :min 80  :protected true}
   {:id "account"     :label "Account"     :w 150 :sortable true  :min 90}
   {:id "institution" :label "Institution" :w 160 :sortable true  :min 90}
   {:id "payee"       :label "Payee"       :w 200 :sortable true  :min 100}
   {:id "description" :label "Description" :w 240 :sortable false :min 200}
   {:id "amount"      :label "Amount"      :w 120 :sortable true  :min 90  :protected true}
   {:id "category"    :label "Category"    :w 180 :sortable true  :min 200 :protected true}
   {:id "reviewed"    :label "Reviewed"    :w 96  :sortable false :min 80  :protected true}])

(def hideable-columns
  "[(id label)…] of the columns the column-picker can toggle, in render order. Column
   visibility is persistent-but-client-applied (a `cols.<id>` signal toggles a `hide-<id>`
   class); this drives both the visibility codec (`parse-cols`/URL) and the picker UI."
  (mapv (juxt :id :label) columns))

(def resizable-cols
  "Columns the resize island gives a drag handle (reviewed is fixed-width)."
  #{"date" "account" "institution" "payee" "description" "amount" "category"})

(def page-size-options [25 50 100 250])

;; ---------------------------------------------------------------------------
;; Primitive parsers
;; ---------------------------------------------------------------------------

(defn ->id-set
  "Coerce a seq of id strings (or numbers) to a set of longs, dropping blanks/non-numerics."
  [strs]
  (into #{} (keep #(some-> % str not-empty parse-long) strs)))

(defn csv-param
  "Split a comma-separated query param into a vector of non-empty tokens ([] when blank)."
  [qp k]
  (let [v (get qp k)] (if (str/blank? v) [] (str/split v #","))))

(defn parse-category-value
  "Coerce the `$catValue` courier signal (a number, an id string, \"\", or nil) to a long id
   or nil. \"\" / nil → nil (clear); \"7\" → 7; 7 → 7."
  [raw]
  (cond (number? raw) (long raw)
        (string? raw) (some-> raw not-empty parse-long)
        :else nil))

(defn parse-splits-value
  "Coerce the `$splitValue` courier (a JSON string of [{amount, categoryId, memo?}] the
   split-editor island serializes) into the db.transactions/set-splits! input shape
   [{:amount string :category-id long :memo string?}]. Blank / nil / \"[]\" → [] (un-split)."
  [raw]
  (if (str/blank? raw)
    []
    (->> (json/read-json raw :key-fn keyword)
         (mapv (fn [{:keys [amount categoryId memo]}]
                 (cond-> {:amount (str amount) :category-id (some-> categoryId long)}
                   (not (str/blank? memo)) (assoc :memo (str/trim memo))))))))

;; ---------------------------------------------------------------------------
;; View-state assembly (the web.view input map)
;; ---------------------------------------------------------------------------

(defn view-state
  "Build the web.view view-state from a generic accessor map (whose keys come from either the
   signals map or a query-param getter). Funnel selections are added by `with-funnels`;
   omitting them = no category/account/institution filter."
  [{:keys [search scope hide-transfers uncat sort-col sort-dir page page-size]}]
  {:search         (or search "")
   :scope          (if (= "needs-review" scope) :needs-review :all)
   :hide-transfers (boolean hide-transfers)
   :uncat          (boolean uncat)
   :sort           (when (not (str/blank? sort-col))
                     {:col (keyword sort-col) :dir (if (= "desc" sort-dir) :desc :asc)})
   :page           (or page 0)
   :page-size      (or page-size 25)})

(defn with-funnels
  "Add the header-funnel selections (account/institution/category id sets) to a view-state."
  [vs accounts institutions categories]
  (assoc vs
         :accounts (->id-set accounts)
         :institutions (->id-set institutions)
         :categories (->id-set categories)))

(defn signals->view-state
  "View-state from the live Datastar signals map (sent on every `@get`/`@put`)."
  [s]
  (with-funnels
    (view-state {:search (:search s) :scope (:scope s) :hide-transfers (:hideTransfers s)
                 :uncat (:uncat s) :sort-col (:sortCol s) :sort-dir (:sortDir s)
                 :page (:page s) :page-size (:pageSize s)})
    (get-in s [:filter :account]) (get-in s [:filter :institution]) (get-in s [:filter :category])))

(defn query->view-state
  "View-state seeded from the URL query params on a fresh page load."
  [qp]
  (with-funnels
    (view-state {:search (get qp "q") :scope (get qp "scope")
                 :hide-transfers (= "1" (get qp "ht")) :uncat (= "1" (get qp "uncat"))
                 :sort-col (get qp "sortCol") :sort-dir (get qp "sortDir")
                 :page (some-> (get qp "page") parse-long)
                 :page-size (some-> (get qp "pageSize") parse-long)})
    (csv-param qp "fa") (csv-param qp "fi") (csv-param qp "fc")))

;; ---------------------------------------------------------------------------
;; Signals (the client's initial data-signals seed)
;; ---------------------------------------------------------------------------

(defn parse-cols
  "The `cols.<id>` visibility signal map (true = visible) from the URL's `hidecols` csv."
  [qp]
  (let [hidden (set (csv-param qp "hidecols"))]
    (into {} (map (fn [[id _]] [(keyword id) (not (contains? hidden id))]) hideable-columns))))

(defn vs->signals
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
   :catValue      ""
   :splitValue    ""
   ;; Statement-balance modal (feature: dated manual balances) couriers.
   :stmtAccount   ""
   :stmtDate      ""
   :stmtBalance   ""
   :stmtDel       ""
   ;; Add-transaction modal (feature: manual transactions) couriers.
   :txAccount     ""
   :txDir         "out"
   :txAmount      ""
   :txDate        ""
   :txPayee       ""
   :txDesc        ""
   :txCategory    ""})

(defn client-signals
  "Full initial signal set: persistent view-state + column visibility + header-funnel
   selections (persistent) + ephemeral UI signals (underscore-prefixed → never sent)."
  [vs month-str result qp]
  (assoc (vs->signals vs month-str result)
         :cols (parse-cols qp)
         :filter {:account (csv-param qp "fa")
                  :institution (csv-param qp "fi")
                  :category (csv-param qp "fc")}
         :_colsOpen false
         ;; Summary-column panels default open; collapse state is ephemeral (survives
         ;; SSE morphs of #reconciliation/#category-rollup, resets on a full reload).
         :_reconcileOpen true
         :_rollupOpen true
         :_openFunnel ""
         :_funnelX 0
         :_funnelY 0
         :_funnelQuery ""
         ;; Row-actions menu (shared floating popover): which row's menu is open (0 = none),
         ;; whether that row is already split / already a matched transfer (drive the item
         ;; labels), and its position.
         :_rowMenu 0
         :_rowMenuSplit false
         :_rowMenuMatched false
         :_rowMenuManual false
         :_rowMenuX 0
         :_rowMenuY 0))

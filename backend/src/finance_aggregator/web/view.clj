(ns finance-aggregator.web.view
  "Pure transaction view-engine for the server-authoritative transactions page:
   filter → sort → paginate over a month's derived transactions, given the view-state
   the URL carries. This ports the rules that used to live in client `data-show`
   expressions and the sort/pagination islands into plain, kaocha-tested Clojure — the
   server now owns which rows render (see doc/plans/datastar-server-authoritative-rewrite.md).

   Input transactions already carry the derived fields (db.transactions/with-derived-fields):
   :transaction/reviewed (effective, split-rolled-up), :transaction/transfer-hidden,
   :transaction/effective-description, and nested :transaction/splits. Because a split's
   parts are nested in its transaction, sorting the transaction list keeps each split's
   rows together for free — no leader/group bookkeeping (unlike the client sort island).

   View-state shape (keyword map; the page parses it from query params):
     {:search \"text\"               ; case-insensitive substring over the haystack
      :scope :all | :needs-review
      :hide-transfers bool           ; hide matched-transfer rows
      :uncat bool                    ; Uncategorized chip
      :accounts #{id…}               ; header-funnel selections (db ids, longs)
      :institutions #{id…}
      :categories #{id…}             ; split-aware; unions with :uncat
      :sort {:col :date|:amount|:account|:institution|:payee|:category :dir :asc|:desc}
      :page 0-indexed
      :page-size pos-int}"
  (:require
   [clojure.string :as str]
   [finance-aggregator.db.transactions :as db-transactions]))

;; --- Filtering --------------------------------------------------------------

(defn- search-haystack
  "Lower-cased text a search matches against (payee, effective description, category,
   and each split part's memo + category) — mirrors the React searchTransactions rule."
  [tx]
  (->> (concat [(:transaction/payee tx)
                (:transaction/effective-description tx)
                (get-in tx [:transaction/category :category/name])]
               (mapcat (fn [p] [(:split/memo p)
                                (get-in p [:split/category :category/name])])
                       (:transaction/splits tx)))
       (remove str/blank?)
       (str/join " ")
       str/lower-case))

(defn- tx-category-ids
  "Distinct real category ids a transaction touches (split-aware): each split part's
   category, or the unsplit tx's category. Categoryless parts contribute nothing — the
   Uncategorized chip owns those."
  [tx]
  (set (if-let [parts (seq (:transaction/splits tx))]
         (keep #(get-in % [:split/category :db/id]) parts)
         (when-let [id (get-in tx [:transaction/category :db/id])] [id]))))

(defn- tx-account-id [tx] (get-in tx [:transaction/account :db/id]))
(defn- tx-institution-id [tx] (get-in tx [:transaction/account :account/institution :db/id]))

(defn- in-selection?
  "A funnel passes a row when nothing is selected, or the row's value is selected."
  [id selected]
  (or (empty? selected) (contains? selected id)))

(defn- matches-category?
  "Category funnel ∪ Uncategorized chip, split-aware (mirrors the old category-show-clause):
   passes when neither is active, OR the row touches a selected category, OR (the chip is on
   AND the row still needs a category)."
  [tx {:keys [categories uncat]}]
  (let [funnel? (seq categories)]
    (if (and (not funnel?) (not uncat))
      true
      (boolean (or (and funnel? (some categories (tx-category-ids tx)))
                   (and uncat (db-transactions/needs-category? tx)))))))

(defn- match?
  "True when a transaction passes every active filter — the server-side equivalent of the
   old per-row data-show expression."
  [tx {:keys [search scope hide-transfers accounts institutions] :as vs}]
  (and (or (str/blank? search)
           (str/includes? (search-haystack tx) (str/lower-case search)))
       ;; scope filters only when explicitly :needs-review (so a partial view-state from the
       ;; facet helpers, with scope absent, defaults to showing all)
       (or (not= scope :needs-review) (not (true? (:transaction/reviewed tx))))
       (not (and hide-transfers (:transaction/transfer-hidden tx)))
       (in-selection? (tx-account-id tx) accounts)
       (in-selection? (tx-institution-id tx) institutions)
       (matches-category? tx vs)))

(defn filter-txs [txs vs] (filter #(match? % vs) txs))

;; --- Sorting ----------------------------------------------------------------

(def ^:private sort-key-fns
  ;; Numeric columns sort by value; string columns by lower-cased text (matching the
  ;; client island's localeCompare-on-lowercased-cell-text).
  {:date        #(if-let [d (:transaction/posted-date %)] (.getTime ^java.util.Date d) 0)
   :amount      #(or (:transaction/amount %) 0)
   :account     #(str/lower-case (or (get-in % [:transaction/account :account/external-name]) ""))
   :institution #(str/lower-case (or (get-in % [:transaction/account :account/institution :institution/name]) ""))
   :payee       #(str/lower-case (or (:transaction/payee %) ""))
   :category    #(str/lower-case (or (get-in % [:transaction/category :category/name]) ""))})

(defn sort-txs
  "Stable sort by a sortable column; no/unknown column leaves the order untouched."
  [txs {:keys [col dir]}]
  (if-let [k (get sort-key-fns col)]
    (sort-by k (if (= dir :desc) #(compare %2 %1) compare) txs)
    txs))

;; --- Pagination -------------------------------------------------------------

(defn paginate
  "Slice the (already filtered+sorted) transactions to a page. Clamps an out-of-range page
   and returns the page metadata the toolbar renders. Counts whole transactions (a split = 1)."
  [txs page page-size]
  (let [total      (count txs)
        ps         (if (and page-size (pos? page-size)) page-size 25)
        page-count (max 1 (long (Math/ceil (/ total (double ps)))))
        clamped    (-> (or page 0) (max 0) (min (dec page-count)))]
    {:rows       (->> txs (drop (* clamped ps)) (take ps) vec)
     :total      total
     :page       clamped
     :page-count page-count
     :page-size  ps}))

;; --- Compose ----------------------------------------------------------------

(defn view
  "filter → sort → paginate. Returns {:rows :total :page :page-count :page-size}; :total is
   the filtered transaction count (drives the pagination status). The toolbar count chips
   are full-month and come from db.transactions/month-counts, not from here."
  [txs {:keys [sort page page-size] :as vs}]
  (-> txs
      (filter-txs vs)
      (sort-txs sort)
      (paginate page page-size)))

;; --- Lingering --------------------------------------------------------------

(defn view-with-linger
  "Like `view`, but keeps just-edited rows visible even after an edit drops them out of the
   active filter. A tx in `linger-set` that no longer matches is held in its **original
   position** (not appended at the end) and reported in `:stale-ids` so the page can render
   it de-emphasised (`is-stale`); the next pure view change clears the linger set.

   The order-preserving trick is to select rows by walking the source `txs` (filter-match OR
   lingered), so a lingered row stays where it naturally sits. `sort-txs` is a stable sort, so
   when a sort is active the lingered row sorts into its proper place too. Returns the same
   shape as `view` plus `:stale-ids` (a set of db ids)."
  [txs {:keys [sort page page-size] :as vs} linger-set]
  (let [linger-set  (or linger-set #{})
        matched-ids (set (map :db/id (filter-txs txs vs)))
        stale-ids   (into #{} (comp (map :db/id)
                                    (filter #(and (contains? linger-set %)
                                                  (not (contains? matched-ids %)))))
                          txs)
        visible     (filter #(or (contains? matched-ids (:db/id %))
                                 (contains? stale-ids (:db/id %)))
                            txs)]
    (-> (sort-txs visible sort)
        (paginate page page-size)
        (assoc :stale-ids stale-ids))))

;; --- Faceting: counts that compose ------------------------------------------
;; Faceted-search semantics: a control's count reflects toggling IT given the OTHER active
;; filters (its own facet neutralized), so the chips / scope / funnel option counts stay
;; consistent with the displayed rows and each count answers "what happens if I click this".
;; The total displayed-row count lives in the pagination footer, not here.

(defn- drop-facet
  "Neutralize one facet of a view-state (so a count can be computed over the OTHER filters)."
  [vs facet]
  (case facet
    :scope          (assoc vs :scope :all)
    ;; the Uncategorized chip + the category funnel are one OR-dimension — neutralize both
    :category-dim   (assoc vs :uncat false :categories #{})
    :hide-transfers (assoc vs :hide-transfers false)
    :accounts       (assoc vs :accounts #{})
    :institutions   (assoc vs :institutions #{})))

(defn facet-counts
  "The four toolbar counts, faceted (each computed with its own control neutralized):
   :total/:unreviewed reflect every filter except scope; :uncategorized reflects every filter
   except the category dimension; :transfers-hidden reflects every filter except Hide-transfers."
  [txs vs]
  (let [no-scope (filter-txs txs (drop-facet vs :scope))]
    {:total            (count no-scope)
     :unreviewed       (count (remove #(true? (:transaction/reviewed %)) no-scope))
     :uncategorized    (count (filter db-transactions/needs-category?
                                      (filter-txs txs (drop-facet vs :category-dim))))
     :transfers-hidden (count (filter :transaction/transfer-hidden
                                      (filter-txs txs (drop-facet vs :hide-transfers))))}))

;; --- Funnel options ---------------------------------------------------------
;; Each header funnel renders EVERY value present this month (so the full choice always shows),
;; with a FACETED count: how many rows matching the OTHER filters have that value. Pure
;; (txs + view-state → option maps), reusing the id-extracting fns above.

(defn- options-with-counts
  "Option list = every value in `all-txs` (label from there); count per option = how many
   `faceted-txs` have it (0 when none). Label-sorted seq of {:id :label :count}."
  [all-txs faceted-txs id-fn label-fn]
  (let [counts (frequencies (keep id-fn faceted-txs))
        labels (reduce (fn [m tx] (if-let [id (id-fn tx)] (assoc m id (label-fn tx)) m)) {} all-txs)]
    (->> labels
         (map (fn [[id label]] {:id id :label label :count (get counts id 0)}))
         (sort-by :label))))

(defn account-options [txs vs]
  (options-with-counts txs (filter-txs txs (drop-facet vs :accounts)) tx-account-id
                       #(or (get-in % [:transaction/account :account/external-name]) "Unknown")))

(defn institution-options [txs vs]
  (options-with-counts txs (filter-txs txs (drop-facet vs :institutions)) tx-institution-id
                       #(or (get-in % [:transaction/account :account/institution :institution/name]) "Unknown")))

(defn category-funnel-options
  "Real categories present this month (split-aware), each with a faceted touch-count. The
   Uncategorized chip owns categoryless rows, so it's excluded here."
  [txs vs]
  (let [counts (frequencies (mapcat tx-category-ids (filter-txs txs (drop-facet vs :category-dim))))
        labels (reduce (fn [m tx]
                         (reduce (fn [m c] (cond-> m (:db/id c) (assoc (:db/id c) (:category/name c))))
                                 m
                                 (cons (:transaction/category tx) (map :split/category (:transaction/splits tx)))))
                       {} txs)]
    (->> labels
         (map (fn [[id label]] {:id id :label label :count (get counts id 0)}))
         (sort-by :label))))

;; --- Split editor seed ------------------------------------------------------

(defn split-editor-seed
  "Seed rows for the split-editor island: each existing split part rendered as
   {:amount <magnitude, 2-dp string> :category-id <id|nil> :memo <string|nil>
    :seed-cents <signed integer cents>}, ordered by :split/order. [] when the transaction
   is unsplit (the island opens with blank rows). seed-cents carries the stored signed value
   so a mixed-sign part survives a round-trip until its magnitude is edited (matching the
   islands/lib/splitMath rowSignedCents contract)."
  [tx]
  (->> (:transaction/splits tx)
       (sort-by :split/order)
       (mapv (fn [{:split/keys [amount category memo]}]
               (let [scaled (.setScale (bigdec amount) 2 java.math.RoundingMode/HALF_UP)]
                 {:amount      (.toPlainString (.abs scaled))
                  :category-id (:db/id category)
                  :memo        memo
                  :seed-cents  (.longValueExact (.movePointRight scaled 2))})))))

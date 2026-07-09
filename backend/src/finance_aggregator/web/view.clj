(ns finance-aggregator.web.view
  "Pure transaction view-engine for the server-authoritative transactions page:
   filter → sort → paginate over a month's derived transactions, given the view-state
   the URL carries. The filter/sort/pagination rules are plain, kaocha-tested Clojure — the
   server owns which rows render (see doc/plans/datastar-server-authoritative-rewrite.md).

   Input transactions already carry the derived fields (db.transactions/with-derived-fields):
   :transaction/effective-description, :transaction/split-drift and
   :transaction/transfer-hidden. A split part is a first-class row here like any other
   transaction — it filters, sorts, counts and attributes independently (no split
   special-casing anywhere in this engine). Parts share their parent's posted-date (a
   copied field), so a date sort clusters a family naturally; no leader/group bookkeeping.

   View-state shape (keyword map; the page parses it from query params):
     {:search \"text\"               ; case-insensitive substring over the haystack
      :scope :all | :needs-review
      :hide-transfers bool           ; hide matched-transfer rows
      :uncat bool                    ; Uncategorized chip
      :accounts #{id…}               ; header-funnel selections (db ids, longs)
      :institutions #{id…}
      :categories #{id…}             ; unions with :uncat
      :sort {:col :date|:amount|:account|:institution|:payee|:category :dir :asc|:desc}
      :page 0-indexed
      :page-size pos-int}"
  (:require
   [clojure.string :as str]
   [finance-aggregator.data.ledger :as ledger]))

;; --- Filtering --------------------------------------------------------------

(defn- search-haystack
  "Lower-cased text a search matches against (payee, effective description, category
   name) — the case-insensitive substring search rule. A split part is a plain row here:
   its memo IS its effective description and it carries its own category."
  [tx]
  (->> [(:transaction/payee tx)
        (:transaction/effective-description tx)
        (get-in tx [:transaction/category :category/name])]
       (remove str/blank?)
       (str/join " ")
       str/lower-case))

(defn- tx-category-id [tx] (get-in tx [:transaction/category :db/id]))
(defn- tx-account-id [tx] (get-in tx [:transaction/account :db/id]))
(defn- tx-institution-id [tx] (get-in tx [:transaction/account :account/institution :db/id]))

(defn needs-category?
  "True when a transaction still needs a category (no :transaction/category ref). A split
   part is a plain row with its own category, so no split awareness is needed. Pure
   predicate over the canonical transaction shape (no I/O)."
  [tx]
  (nil? (tx-category-id tx)))

(defn- in-selection?
  "A funnel passes a row when nothing is selected, or the row's value is selected."
  [id selected]
  (or (empty? selected) (contains? selected id)))

(defn- matches-category?
  "Category funnel ∪ Uncategorized chip:
   passes when neither is active, OR the row's own category is selected, OR (the chip is
   on AND the row still needs a category)."
  [tx {:keys [categories uncat]}]
  (let [funnel? (seq categories)]
    (if (and (not funnel?) (not uncat))
      true
      (boolean (or (and funnel? (contains? categories (tx-category-id tx)))
                   (and uncat (needs-category? tx)))))))

(defn- match?
  "True when a transaction passes every active filter."
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
  ;; Numeric columns sort by value; string columns by lower-cased text
  ;; (localeCompare-on-lowercased-cell-text).
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
   are full-month and come from `facet-counts`, not from here."
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
     :uncategorized    (count (filter needs-category?
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
  "Real categories present this month, each with a faceted per-row touch-count (a part
   row counts like any row). The Uncategorized chip owns categoryless rows, so it's
   excluded here."
  [txs vs]
  (options-with-counts txs (filter-txs txs (drop-facet vs :category-dim)) tx-category-id
                       #(get-in % [:transaction/category :category/name])))

;; --- Split editor seed ------------------------------------------------------

(defn split-editor-seed
  "Seed rows for the split-editor island: each existing split part (a first-class
   :transaction/* row linked via :transaction/split-parent) rendered as
   {:id <part db/id> :amount <magnitude, 2-dp string> :category-id <id|nil>
    :memo <string|nil> :seed-cents <signed integer cents>}, ordered by
   :transaction/split-order. [] when the transaction is unsplit (the island opens
   with blank rows). seed-cents carries the stored signed value so a mixed-sign part
   survives a round-trip until its magnitude is edited (matching the
   islands/lib/splitMath rowSignedCents contract)."
  [tx]
  (->> (:transaction/_split-parent tx)
       (sort-by :transaction/split-order)
       (mapv (fn [{:transaction/keys [amount description] :as part}]
               (let [scaled (.setScale (bigdec amount) 2 java.math.RoundingMode/HALF_UP)]
                 {:id          (:db/id part)
                  :amount      (.toPlainString (.abs scaled))
                  :category-id (get-in part [:transaction/category :db/id])
                  :memo        description
                  :seed-cents  (.longValueExact (.movePointRight scaled 2))})))))

;; --- Category rollup --------------------------------------------------------
;; A per-category breakdown of a month for the summary pane.
;; Every row attributes its own amount to its own category (a split part is a plain row —
;; its family's parts land in their own rollup rows and sum to the excluded parent); a
;; missing/unknown category falls into an Uncategorized bucket split by sign. Single-level
;; parent hierarchy.

(defn- mag
  "Unsigned magnitude (works for bigdec, long, and 0)."
  [n]
  (if (neg? n) (- n) n))

(defn category-rollup
  "Build the category rollup for `txs` (a whole month) given `categories`. Returns
   {:income S :expenses S :transfers S :grand-total <signed>} where each S is
   {:type kw :total <magnitude> :rows [row…]} and a row is
   {:ids [cat-id…] :name str :depth 0|1 :group? bool :uncategorized? bool :amount <magnitude>}.
   A group row carries its parent + every same-type child (so a click filters the whole group);
   a leaf carries just itself; an Uncategorized row carries [] (the click toggles the $uncat
   chip). The grand total is signed (income + expense; transfers excluded)."
  [txs categories]
  (let [present (set (map :db/id categories))
        parent-of (fn [c] (let [pid (get-in c [:category/parent :db/id])]
                            (when (and pid (present pid)) pid)))
        info (into {} (map (fn [c] [(:db/id c) {:name (:category/name c)
                                                :type (:category/type c)
                                                :parent-id (parent-of c)
                                                :sort-order (or (:category/sort-order c) Long/MAX_VALUE)}])
                           categories))
        children-of (reduce (fn [m c] (if-let [pid (parent-of c)]
                                        (update m pid (fnil conj []) (:db/id c)) m))
                            {} categories)
        attribute (fn [[sums uinc uexp] cat-id amount]
                    (cond
                      (and cat-id (info cat-id)) [(update sums cat-id (fnil + 0) amount) uinc uexp]
                      (>= amount 0)              [sums (+ uinc amount) uexp]
                      :else                      [sums uinc (+ uexp amount)]))
        [sums uinc uexp]
        (reduce (fn [acc tx] (attribute acc (tx-category-id tx) (or (:transaction/amount tx) 0)))
                [{} 0 0] txs)
        signed-for (fn [type] (reduce-kv (fn [acc id sum] (cond-> acc (= type (:type (info id))) (+ sum))) 0 sums))
        income-signed   (+ uinc (signed-for :income))
        expense-signed  (+ uexp (signed-for :expense))
        transfer-signed (signed-for :transfer)
        sort-key (fn [id] (get-in info [id :sort-order]))
        by-sort (fn [ids] (sort-by (juxt sort-key identity) ids))
        top-level-sorted (by-sort (filter #(nil? (:parent-id (info %))) (keys info)))
        section-rows
        (fn [type uncategorized]
          (let [head-ids (filter #(= type (:type (info %))) top-level-sorted)
                groups (keep
                        (fn [head-id]
                          (let [same-type-children (filter #(= type (:type (info %))) (get children-of head-id []))
                                group-ids (vec (cons head-id same-type-children))
                                active-children (by-sort (filter #(contains? sums %) same-type-children))
                                head-sum (get sums head-id 0)]
                            (cond
                              (and (not (contains? sums head-id)) (empty? active-children)) nil
                              (seq active-children)
                              {:emitted (cons head-id active-children)
                               :rows (cons {:ids group-ids :name (:name (info head-id)) :depth 0 :group? true
                                            :uncategorized? false
                                            :amount (mag (+ head-sum (reduce + 0 (map #(get sums % 0) active-children))))}
                                           (map (fn [cid] {:ids [cid] :name (:name (info cid)) :depth 1 :group? false
                                                           :uncategorized? false :amount (mag (get sums cid 0))})
                                                active-children))}
                              :else
                              {:emitted [head-id]
                               :rows [{:ids group-ids :name (:name (info head-id)) :depth 0 :group? false
                                       :uncategorized? false :amount (mag head-sum)}]})))
                        head-ids)
                emitted (set (mapcat :emitted groups))
                orphan-ids (by-sort (filter #(and (= type (:type (info %))) (not (emitted %))) (keys sums)))
                orphan-rows (map (fn [id] {:ids [id] :name (:name (info id)) :depth 0 :group? false
                                           :uncategorized? false :amount (mag (get sums id 0))}) orphan-ids)]
            (concat (mapcat :rows groups)
                    orphan-rows
                    (when-not (zero? uncategorized)
                      [{:ids [] :name "Uncategorized" :depth 0 :group? false
                        :uncategorized? true :amount (mag uncategorized)}]))))]
    {:income      {:type :income   :rows (section-rows :income uinc)    :total (mag income-signed)}
     :expenses    {:type :expense  :rows (section-rows :expense uexp)   :total (mag expense-signed)}
     :transfers   {:type :transfer :rows (section-rows :transfer 0)     :total (mag transfer-signed)}
     :grand-total (+ income-signed expense-signed)}))

;; --- Monthly close: per-account reconciliation ------------------------------
;; Coverage-strict closing: does EVERY one of an account's month transactions fall inside a
;; reconciled period? A month-boundary snapshot reconciling is one way to cover the whole
;; month (its span IS the calendar month); a credit card's statements tying out is another —
;; and two adjacent statements can jointly cover a month even though neither alone spans it.
;; See data.ledger/month-coverage for the coverage math this folds through.

(defn reconcile-month
  "Coverage-strict per-account reconciliation for the month's `txs`. `reported` = {account-eid
   reported-delta} from the snapshot history (the month-boundary balances' delta);
   `month-span` = {:start opening-date :end month-end-date} (Dates, the same calendar span for
   every account); `statements-by-account` = {account-eid [statement…]}, each statement already
   annotated with its own period-delta verdict (reconcile-statement).

   Returns {:rows [row…] :all-reconciled? bool} where a row is
   {:account-id :name :computed-delta :reported-delta :status :uncovered :first-uncovered
    :difference} — the per-account confidence readout the close panel renders. :status is
   :reconciled/:partial/:no-snapshot (coverage-strict — reconciled needs EVERY month txn
   covered, not just the month-boundary check). :difference (the overview's 'off by $X'
   wording) is populated ONLY for the single-number case — a :partial account whose
   month-boundary balance is entered and which has no statements at all — since a
   statement-covered or multi-period account has no one figure to blame. Pure."
  [txs reported month-span statements-by-account]
  (let [deltas (ledger/account-computed-deltas txs)
        txs-by-account (group-by #(get-in % [:transaction/account :db/id]) txs)
        rows (for [[account-id {:keys [name computed-delta]}] deltas
                   :let [rdelta (get reported account-id)
                         acct-txs (get txs-by-account account-id [])
                         statements (get statements-by-account account-id [])
                         boundary-reconciled? (and (some? rdelta)
                                                    (<= (abs (- rdelta computed-delta)) ledger/default-tolerance))
                         statement-spans (->> statements
                                              (filter #(= :reconciled (:status %)))
                                              (map (fn [s] {:start (:start-date s) :end (:end-date s)})))
                         spans (cond-> statement-spans boundary-reconciled? (conj month-span))
                         any-periods? (or (some? rdelta) (seq statements))
                         cov (ledger/month-coverage acct-txs spans any-periods?)
                         difference (when (and (= :partial (:status cov)) (some? rdelta) (empty? statements))
                                      (- rdelta computed-delta))]]
               {:account-id      account-id
                :name            name
                :computed-delta  computed-delta
                :reported-delta  rdelta
                :status          (:status cov)
                :uncovered       (:uncovered cov)
                :first-uncovered (:first-uncovered cov)
                :difference      difference})
        rows (vec (sort-by :name rows))]
    {:rows rows :all-reconciled? (ledger/all-reconciled? rows)}))

(defn month-close
  "The monthly-close panel model. Pure. Combines the per-account reconciliation, the
   completeness gate over the whole month, and the persisted close state.
     :reconciliation   — reconcile-month output {:rows :all-reconciled?}
     :close            — the persisted :reconciliation/* event map, or nil
     :net-now          — the month's current signed net (rollup :grand-total), for drift
   Returns
     {:rows [reconcile-row…]
      :gate {:unreviewed n :uncategorized n :all-reviewed? b :all-categorized? b
             :balanced? b :ready? b}
      :closed? b :closed-at inst
      :drift {:frozen bd :now bd} | nil}
   `:ready?` — the month may be closed cleanly — needs everything reviewed AND
   categorized AND every account's balance reconciled. `:drift` is present only for a
   CLOSED month whose current net no longer matches the frozen net (it changed since
   the lock)."
  [txs {:keys [reconciliation close net-now]}]
  (let [unreviewed       (count (remove #(true? (:transaction/reviewed %)) txs))
        uncategorized    (count (filter needs-category? txs))
        balanced?        (boolean (:all-reconciled? reconciliation))
        all-reviewed?    (zero? unreviewed)
        all-categorized? (zero? uncategorized)
        closed?          (some? close)
        frozen-net       (:reconciliation/net close)]
    {:rows            (:rows reconciliation)
     :gate            {:unreviewed unreviewed :uncategorized uncategorized
                       :all-reviewed? all-reviewed? :all-categorized? all-categorized?
                       :balanced? balanced?
                       :ready? (and all-reviewed? all-categorized? balanced?)}
     :closed?         closed?
     :closed-at       (:reconciliation/closed-at close)
     :drift           (when (and closed? net-now (not= frozen-net net-now))
                        {:frozen frozen-net :now net-now})}))

(defn focus-close
  "The focused single-account reconcile card model. Pure. Given the month's `txs`, the
   drilled-into `account-eid`, the opening/closing bank balances currently on file (bigdec
   or nil when not yet entered), their app-owned boundary dates, and the account's `statements`
   (already annotated with their own period-delta verdicts — reconcile-statement), returns
     {:account-id :name :opening :closing :opening-date :closing-date :expected :tracked
      :boundary-status :boundary-difference :coverage :statements}
   `:expected` is the reported change (closing − opening, nil if either missing) and `:tracked`
   is Σ the account's month transactions. `:boundary-status`/`:boundary-difference` are the
   month-boundary PERIOD's own verdict (reconcile-period) — the month-end section's per-period
   readout ('this period matches' / 'off by $X'). `:coverage` (data.ledger/month-coverage) is
   the ACCOUNT-LEVEL headline: whether EVERY month transaction is covered by a reconciled
   period — the boundary span (when it reconciles) and/or a reconciled statement — the
   coverage-strict close check scoped to this one account. `name-fallback` names the account
   when it has no activity."
  [txs {:keys [account-eid name-fallback opening closing opening-date closing-date statements]}]
  (let [row          (get (ledger/account-computed-deltas txs) account-eid)
        tracked      (or (:computed-delta row) 0M)
        nm           (or (:name row) name-fallback "Account")
        acct-txs     (filter #(= account-eid (get-in % [:transaction/account :db/id])) txs)
        expected     (when (and opening closing) (- closing opening))
        boundary     (ledger/reconcile-period opening closing acct-txs)
        statement-spans (->> statements
                             (filter #(= :reconciled (:status %)))
                             (map (fn [s] {:start (:start-date s) :end (:end-date s)})))
        spans        (cond-> statement-spans
                       (= :reconciled (:status boundary)) (conj {:start opening-date :end closing-date}))
        any-periods? (or (some? expected) (seq statements))
        coverage     (ledger/month-coverage acct-txs spans any-periods?)]
    {:account-id          account-eid
     :name                nm
     :opening             opening
     :closing             closing
     :opening-date        opening-date
     :closing-date        closing-date
     :expected            expected
     :tracked             tracked
     :boundary-status     (:status boundary)
     :boundary-difference (:difference boundary)
     :coverage            coverage
     :statements          statements}))

(defn reconcile-statement
  "A statement (db.statements display shape: :id :start-date :start-balance :end-date
   :end-balance …) annotated with its period-delta verdict over `span-txns` (the account's
   transactions in the statement's span). Statement balance polarity can differ from synced
   month-boundary snapshots, so the statement-specific ledger path chooses the balance delta
   direction that ties closest to tracked activity. Adds :reported :computed :difference
   :status. Pure."
  [statement span-txns]
  (merge statement
         (ledger/reconcile-statement-period (:start-balance statement) (:end-balance statement) span-txns)))

;; --- Presenter: the response view-model -------------------------------------
;; The single transformation entry point a handler routes a month's transactions through to get
;; everything a transactions response renders. Bundling it here keeps the handler pure glue —
;; it never needs to know which view primitives feed which fragment.

(defn present
  "Assemble the derived view-model a transactions response renders from. Pure.
   `:result` is the paginated page — lingering (an edited-out row stays visible) when a
   `:linger` set is supplied, a plain view otherwise; `:counts` are the faceted toolbar counts;
   the three `:*-options` are the faceted funnel option lists; `:rollup` (only when
   `:categories` is supplied) is the whole-month category breakdown. The monthly-close
   panel model is assembled separately by the handler (it needs the account filter + the
   snapshot history), not here."
  [txs view-st {:keys [linger categories]}]
  (let [rollup (when categories (category-rollup txs categories))]
    (cond-> {:result              (if (some? linger)
                                    (view-with-linger txs view-st linger)
                                    (view txs view-st))
             :counts              (facet-counts txs view-st)
             :account-options     (account-options txs view-st)
             :institution-options (institution-options txs view-st)
             :category-options    (category-funnel-options txs view-st)}
      rollup (assoc :rollup rollup))))

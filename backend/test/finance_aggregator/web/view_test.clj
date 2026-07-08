(ns finance-aggregator.web.view-test
  "Tests for the pure server-side view engine — the rules that used to live in client
   data-show expressions and the sort/pagination islands, now in plain Clojure."
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.web.view :as view]))

;; --- Fixtures ---------------------------------------------------------------

(defn- acct [id name inst-id inst-name]
  {:db/id id :account/external-name name
   :account/institution {:db/id inst-id :institution/name inst-name}})

(def ^:private bank #(acct % %2 1000 "Test Bank"))

(defn- tx [m]
  (merge {:transaction/payee "" :transaction/effective-description ""
          :transaction/reviewed false :transaction/transfer-hidden false
          :transaction/splits []}
         m))

(def ^:private t1
  (tx {:db/id 1 :transaction/posted-date #inst "2025-01-01"
       :transaction/payee "Acme Payroll" :transaction/amount 4000
       :transaction/category {:db/id 10 :category/name "Salary"}
       :transaction/account (bank 100 "Chequing")}))

(def ^:private t2
  (tx {:db/id 2 :transaction/posted-date #inst "2025-01-05"
       :transaction/payee "Superstore" :transaction/amount -85
       :transaction/reviewed true
       :transaction/category {:db/id 11 :category/name "Groceries"}
       :transaction/account (bank 101 "Visa")}))

(def ^:private t3
  (tx {:db/id 3 :transaction/posted-date #inst "2025-01-15"
       :transaction/payee "Mortgage Payment" :transaction/amount -2000
       :transaction/transfer-hidden true
       :transaction/category {:db/id 12 :category/name "Housing"}
       :transaction/account (bank 100 "Chequing")}))

(def ^:private t4
  (tx {:db/id 4 :transaction/posted-date #inst "2025-01-12"
       :transaction/payee "Uncat thing" :transaction/amount -50
       :transaction/category nil
       :transaction/account (bank 101 "Visa")}))

(def ^:private t5
  (tx {:db/id 5 :transaction/posted-date #inst "2025-01-20"
       :transaction/payee "Costco" :transaction/amount -200
       :transaction/account (bank 101 "Visa")
       :transaction/splits [{:split/order 0 :split/memo "food"
                             :split/category {:db/id 11 :category/name "Groceries"}}
                            {:split/order 1 :split/memo "household"
                             :split/category nil}]}))

(def ^:private txs [t1 t2 t3 t4 t5])
(defn- ids [ts] (map :db/id ts))

;; --- Scope ------------------------------------------------------------------

(deftest scope-filter
  (is (= [1 2 3 4 5] (ids (view/filter-txs txs {:scope :all})))
      "all shows everything")
  (is (= [1 3 4 5] (ids (view/filter-txs txs {:scope :needs-review})))
      "needs-review drops the reviewed row (t2)"))

;; --- Search -----------------------------------------------------------------

(deftest search-filter
  (testing "matches across payee / description / category / split memo + category"
    (is (= [2] (ids (view/filter-txs txs {:scope :all :search "superstore"}))) "payee")
    (is (= [3] (ids (view/filter-txs txs {:scope :all :search "housing"})))   "category name")
    (is (= [5] (ids (view/filter-txs txs {:scope :all :search "food"})))      "split memo")
    (is (= [2 5] (ids (view/filter-txs txs {:scope :all :search "groceries"})))
        "category on a normal row AND a split part"))
  (testing "case-insensitive + blank = no filter"
    (is (= [2] (ids (view/filter-txs txs {:scope :all :search "SUPERSTORE"}))))
    (is (= [1 2 3 4 5] (ids (view/filter-txs txs {:scope :all :search ""}))))))

;; --- Hide transfers ---------------------------------------------------------

(deftest hide-transfers-filter
  (is (= [1 2 4 5] (ids (view/filter-txs txs {:scope :all :hide-transfers true})))
      "drops the transfer-hidden row (t3)")
  (is (= [1 2 3 4 5] (ids (view/filter-txs txs {:scope :all :hide-transfers false})))))

;; --- Account / institution funnels ------------------------------------------

(deftest account-funnel
  (is (= [2 4 5] (ids (view/filter-txs txs {:scope :all :accounts #{101}}))) "Visa only")
  (is (= [1 2 3 4 5] (ids (view/filter-txs txs {:scope :all :accounts #{}})))
      "empty selection = no filter")
  (is (= [1 2 3 4 5] (ids (view/filter-txs txs {:scope :all :institutions #{1000}})))
      "all share the one institution"))

;; --- Category funnel ∪ Uncategorized chip -----------------------------------

(deftest category-funnel-and-uncat
  (is (= [1] (ids (view/filter-txs txs {:scope :all :categories #{10}}))) "Salary")
  (is (= [2 5] (ids (view/filter-txs txs {:scope :all :categories #{11}})))
      "Groceries — split-aware (t5's part touches it)")
  (is (= [4 5] (ids (view/filter-txs txs {:scope :all :uncat true})))
      "uncat chip: t4 (no category) + t5 (a split part lacks one)")
  (is (= [3 4 5] (ids (view/filter-txs txs {:scope :all :categories #{12} :uncat true})))
      "union: Housing(t3) ∪ uncategorized(t4,t5)"))

;; --- Sorting ----------------------------------------------------------------

(deftest sorting
  (is (= [3 5 2 4 1] (ids (view/sort-txs txs {:col :amount :dir :asc}))) "amount asc")
  (is (= [1 4 2 5 3] (ids (view/sort-txs txs {:col :amount :dir :desc}))) "amount desc")
  (is (= [1 5 3 2 4] (ids (view/sort-txs txs {:col :payee :dir :asc})))
      "payee asc, lower-cased")
  (is (= [1 2 3 4 5] (ids (view/sort-txs txs nil))) "no sort = unchanged")
  (is (= [1 2 3 4 5] (ids (view/sort-txs txs {:col :unknown :dir :asc})))
      "unknown column = unchanged"))

;; --- Pagination -------------------------------------------------------------

(deftest pagination
  (let [p0 (view/paginate txs 0 2)]
    (is (= [1 2] (ids (:rows p0))))
    (is (= {:total 5 :page 0 :page-count 3 :page-size 2}
           (dissoc p0 :rows))))
  (is (= [3 4] (ids (:rows (view/paginate txs 1 2)))) "second page")
  (let [over (view/paginate txs 5 2)]
    (is (= [5] (ids (:rows over))) "out-of-range page clamps to the last")
    (is (= 2 (:page over))))
  (let [zero (view/paginate txs 0 0)]
    (is (= 25 (:page-size zero)) "non-positive page size defaults to 25")
    (is (= 5 (count (:rows zero))))))

;; --- Compose ----------------------------------------------------------------

(deftest view-composition
  (let [v (view/view txs {:scope :needs-review
                          :sort {:col :amount :dir :asc}
                          :page 0 :page-size 2})]
    (is (= [3 5] (ids (:rows v)))
        "needs-review → [t1 t3 t4 t5], amount asc → [3 5 4 1], page 0/2 → [3 5]")
    (is (= 4 (:total v)) "filtered total drives pagination")
    (is (= 2 (:page-count v)))))

;; --- Presenter: the response view-model -------------------------------------
;; `present` is the single transformation entry point the handlers route through; it bundles the
;; already-tested primitives so the handler stays pure glue. These guard the bundling + the
;; linger/categories flags.

(deftest present-bundles-the-view-model
  (let [vs {:scope :all :page 0 :page-size 25}]
    (testing "no linger, no categories → plain view + all faceted lists, no rollup"
      (let [m (view/present txs vs {})]
        (is (= (view/view txs vs) (:result m)) ":result is a plain (non-lingering) view")
        (is (= (view/facet-counts txs vs) (:counts m)))
        (is (= (view/account-options txs vs) (:account-options m)))
        (is (= (view/institution-options txs vs) (:institution-options m)))
        (is (= (view/category-funnel-options txs vs) (:category-options m)))
        (is (not (contains? m :rollup)) "no :categories → no rollup")))
    (testing "a :linger set switches :result to the lingering view (carries :stale-ids)"
      (let [m (view/present txs {:scope :needs-review} {:linger #{2}})]
        (is (= #{2} (:stale-ids (:result m))) "edited-out t2 kept stale")))
    (testing ":categories add the whole-month rollup"
      (is (= (view/category-rollup txs []) (:rollup (view/present txs vs {:categories []})))))
    (testing "the monthly-close panel is assembled by the handler, never by present"
      (is (not (contains? (view/present txs vs {}) :close)))
      (is (not (contains? (view/present txs vs {:categories []}) :close))))))

;; --- Monthly close ----------------------------------------------------------

(deftest month-close-gate
  (testing "everything reviewed + categorized + balanced → ready to close"
    (let [done  [(tx {:db/id 1 :transaction/amount 100 :transaction/reviewed true
                      :transaction/category {:db/id 10 :category/name "X"}
                      :transaction/account (bank 100 "A")})]
          recon {:rows [{:status :reconciled}] :all-reconciled? true}
          m     (view/month-close done {:reconciliation recon :net-now 100M})]
      (is (true? (get-in m [:gate :ready?])))
      (is (zero? (get-in m [:gate :unreviewed])))
      (is (false? (:closed? m)))))
  (testing "unreviewed / uncategorized rows block ready"
    (let [txs2  [(tx {:db/id 2 :transaction/amount -5 :transaction/reviewed false
                      :transaction/category nil :transaction/account (bank 100 "A")})]
          recon {:rows [{:status :reconciled}] :all-reconciled? true}
          m     (view/month-close txs2 {:reconciliation recon :net-now -5M})]
      (is (= 1 (get-in m [:gate :unreviewed])))
      (is (= 1 (get-in m [:gate :uncategorized])))
      (is (false? (get-in m [:gate :ready?])))))
  (testing "an unreconciled (no-snapshot) account blocks ready even when reviewed+categorized"
    (let [done  [(tx {:db/id 1 :transaction/amount 100 :transaction/reviewed true
                      :transaction/category {:db/id 10 :category/name "X"}
                      :transaction/account (bank 100 "A")})]
          recon {:rows [{:status :no-snapshot}] :all-reconciled? false}
          m     (view/month-close done {:reconciliation recon :net-now 100M})]
      (is (false? (get-in m [:gate :balanced?])))
      (is (false? (get-in m [:gate :ready?]))))))

(deftest month-close-drift
  (let [close-evt {:reconciliation/net 1000M :reconciliation/closed-at #inst "2026-03-01"}
        recon     {:rows [] :all-reconciled? true}]
    (testing "closed with the net unchanged → no drift"
      (is (nil? (:drift (view/month-close [] {:reconciliation recon :close close-evt :net-now 1000M})))))
    (testing "closed but the net changed since → drift carries frozen vs now"
      (is (= {:frozen 1000M :now 1200M}
             (:drift (view/month-close [] {:reconciliation recon :close close-evt :net-now 1200M})))))
    (testing "closed? and closed-at reflect the persisted event"
      (let [m (view/month-close [] {:reconciliation recon :close close-evt :net-now 1000M})]
        (is (true? (:closed? m)))
        (is (= #inst "2026-03-01" (:closed-at m)))))))

;; --- Focused single-account reconcile card ----------------------------------
;; The drill-in card: one account's opening/closing balances vs its tracked activity, the
;; same period-delta verdict as the overview, scoped to one account. Chequing (eid 100):
;; +4000 -2000 = 2000; Visa (eid 101): -85 -50 -200 = -335.

(deftest focus-close-single-account-verdict
  (testing "matches when opening + tracked = closing (the period-delta ties out)"
    (let [f (view/focus-close txs {:account-eid 100 :opening 500M :closing 2500M
                                   :opening-date #inst "2025-01-31" :closing-date #inst "2025-02-28"})]
      (is (= "Chequing" (:name f)) "name comes from the account's activity")
      (is (= 2000M (:tracked f)))
      (is (= 2000M (:expected f)) "closing − opening")
      (is (= :reconciled (:status f)))
      (is (= #inst "2025-01-31" (:opening-date f)) "boundary dates pass through for the labels")))
  (testing "off by the difference when they don't tie out (drift)"
    (let [f (view/focus-close txs {:account-eid 100 :opening 0M :closing 2500M})]
      (is (= 2500M (:expected f)))
      (is (= 500M (:difference f)) "expected − tracked = 2500 − 2000")
      (is (= :drift (:status f)))))
  (testing "no verdict until both balances are entered (no-snapshot)"
    (let [f (view/focus-close txs {:account-eid 101 :opening nil :closing -335M})]
      (is (= "Visa" (:name f)))
      (is (= -335M (:tracked f)))
      (is (nil? (:expected f)))
      (is (= :no-snapshot (:status f)))))
  (testing "an account with no activity this month tracks 0 and uses the name fallback"
    (let [f (view/focus-close txs {:account-eid 999 :name-fallback "Savings" :opening 10M :closing 10M})]
      (is (= "Savings" (:name f)))
      (is (= 0M (:tracked f)))
      (is (= 0M (:expected f)))
      (is (= :reconciled (:status f)) "0 change explained by 0 activity → reconciled"))))

(deftest reconcile-statement-uses-statement-balance-polarity
  (let [statement {:id 7 :start-balance 44.02M :end-balance -90.15M}
        span [{:transaction/amount 44.02M}
              {:transaction/amount -31.92M}
              {:transaction/amount -4.56M}
              {:transaction/amount 36.48M}
              {:transaction/amount 90.15M}]
        r (view/reconcile-statement statement span)]
    (is (= :reconciled (:status r)))
    (is (= 134.17M (:computed r)))
    (is (= 134.17M (:reported r)))
    (is (= 0.00M (:difference r)))))

;; --- Lingering --------------------------------------------------------------
;; A row edited out of the active filter should stay visible *in its original position*
;; (de-emphasised) until the next pure view change, instead of vanishing or jumping to the
;; bottom. These guard bugs 7 (Uncategorized chip → categorize → row disappears) and 8
;; (Needs-review → review → row jumps to the bottom): both were the linger composition
;; *appending* the lingered row instead of keeping it in place.

(deftest view-with-linger-keeps-original-position
  (testing "BUG 8 (no sort): a now-reviewed row lingers in its natural position, not at the end"
    ;; t2 (db/id 2) is reviewed; under :needs-review it no longer matches, but it's lingering.
    (let [v (view/view-with-linger txs {:scope :needs-review} #{2})]
      (is (= [1 2 3 4 5] (ids (:rows v)))
          "t2 stays between t1 and t3 (source order), NOT appended after t5")
      (is (= #{2} (:stale-ids v)) "the lingered-but-unmatching row is reported stale")))
  (testing "BUG 7 (no sort): a now-categorized row lingers in place under the Uncat chip"
    ;; With :uncat the chip matches t4 + t5 (both lack a category somewhere). Pretend t4 was
    ;; just categorized so it no longer matches; it must hold its slot, not move/disappear.
    (let [categorized-t4 (assoc t4 :transaction/category {:db/id 99 :category/name "Dining"})
          month          [t1 t2 t3 categorized-t4 t5]
          v              (view/view-with-linger month {:scope :all :uncat true} #{4})]
      (is (= [4 5] (ids (:rows v)))
          "t4 keeps its position ahead of t5 even though it no longer matches the chip")
      (is (= #{4} (:stale-ids v)) "t4 reported stale"))))

(deftest view-with-linger-sorts-stale-rows-in-place
  (testing "with an active sort, a lingered non-matching row sorts into its natural slot"
    ;; :needs-review drops t2 (reviewed); linger keeps it. amount asc over [1 2 3 4 5] is
    ;; [3(-2000) 5(-200) 2(-85) 4(-50) 1(4000)] — t2 sorts between t5 and t4, not appended.
    (let [v (view/view-with-linger txs {:scope :needs-review
                                        :sort {:col :amount :dir :asc}}
                                   #{2})]
      (is (= [3 5 2 4 1] (ids (:rows v)))
          "t2 sorts to its amount position, not the bottom")
      (is (= #{2} (:stale-ids v))))))

(deftest view-with-linger-matching-rows-not-stale
  (testing "a lingered row that STILL matches the filter is not stale (just re-rendered)"
    (let [v (view/view-with-linger txs {:scope :all} #{1})]
      (is (= [1 2 3 4 5] (ids (:rows v))) "all rows present, normal order")
      (is (= #{} (:stale-ids v)) "t1 still matches :all, so nothing is stale"))))

(deftest view-with-linger-empty-and-clear-semantics
  (testing "no linger set = plain view, never stale"
    (let [v (view/view-with-linger txs {:scope :needs-review} #{})]
      (is (= [1 3 4 5] (ids (:rows v))) "reviewed t2 dropped, none lingering")
      (is (= #{} (:stale-ids v))))
    (let [v (view/view-with-linger txs {:scope :needs-review} nil)]
      (is (= [1 3 4 5] (ids (:rows v))) "nil linger set tolerated as empty")
      (is (= #{} (:stale-ids v)))))
  (testing "clearing the linger set (the next view change) drops the stale row entirely"
    ;; Same view-state as the lingering case, but with the set cleared: t2 is simply gone.
    (let [lingered (view/view-with-linger txs {:scope :needs-review} #{2})
          cleared  (view/view-with-linger txs {:scope :needs-review} #{})]
      (is (= [1 2 3 4 5] (ids (:rows lingered))) "while lingering: t2 visible")
      (is (= [1 3 4 5]   (ids (:rows cleared)))  "after clear: t2 gone"))))

(deftest view-with-linger-paginates-after-injection
  (testing ":total + pagination count the injected stale row"
    ;; needs-review base = [1 3 4 5] (4 rows); linger t2 → 5 visible. page 0, size 2.
    (let [v (view/view-with-linger txs {:scope :needs-review :page 0 :page-size 2} #{2})]
      (is (= 5 (:total v)) "stale row included in the total")
      (is (= 3 (:page-count v)) "5 rows / 2 = 3 pages")
      (is (= [1 2] (ids (:rows v))) "first page holds t1 then the in-place stale t2"))))

;; --- Funnel options ---------------------------------------------------------
;; The header funnels list the distinct account/institution/category values present this
;; month, each with a per-id transaction count, label-sorted. These were duplicated in the
;; page; they now live here next to the id-extracting fns they share.

(deftest account-funnel-options
  (testing "no other filter → every account with its full-month count, label-sorted"
    (is (= [{:id 100 :label "Chequing" :count 2}
            {:id 101 :label "Visa" :count 3}]
           (view/account-options txs {}))))
  (testing "the funnel's OWN selection doesn't affect its option counts (so nothing zeroes out)"
    (is (= [{:id 100 :label "Chequing" :count 2}
            {:id 101 :label "Visa" :count 3}]
           (view/account-options txs {:accounts #{101}}))))
  (testing "but ANOTHER active filter is faceted in: needs-review drops t2 → Visa 3→2"
    (is (= [{:id 100 :label "Chequing" :count 2}
            {:id 101 :label "Visa" :count 2}]
           (view/account-options txs {:scope :needs-review}))))
  (testing "missing-account rows contribute no option"
    (is (= [] (view/account-options [(tx {:db/id 9 :transaction/amount 1})] {})))))

(deftest institution-funnel-options
  (is (= [{:id 1000 :label "Test Bank" :count 5}] (view/institution-options txs {}))))

(deftest category-funnel-options-split-aware
  (testing "real categories, split-aware counts, Uncategorized excluded, label-sorted"
    (is (= [{:id 11 :label "Groceries" :count 2}
            {:id 12 :label "Housing" :count 1}
            {:id 10 :label "Salary" :count 1}]
           (view/category-funnel-options txs {}))))
  (testing "the full category list always shows; counts faceted (hide-transfers zeroes Housing)"
    (is (= [{:id 11 :label "Groceries" :count 2}
            {:id 12 :label "Housing" :count 0}
            {:id 10 :label "Salary" :count 1}]
           (view/category-funnel-options txs {:hide-transfers true}))))
  (testing "a fully-uncategorized month yields no category options"
    (is (= [] (view/category-funnel-options [t4] {})))))

(deftest facet-counts-compose
  (testing "no filters → the faceted counts equal the full-month tallies"
    (let [fc (view/facet-counts txs {})]
      (is (= 5 (:total fc)))
      (is (= 4 (:unreviewed fc)) "t2 reviewed")
      (is (= 2 (:uncategorized fc)) "t4 + t5")
      (is (= 1 (:transfers-hidden fc)) "t3")))
  (testing "scope/uncat compose: each count reflects the OTHER active filters, not its own"
    ;; uncat chip on, scope=needs-review. Category-dim removed for :total/:unreviewed leaves
    ;; scope, but those use drop scope: All = the 2 uncategorized rows, Needs review = those
    ;; that are unreviewed (both) = 2.
    (let [fc (view/facet-counts txs {:scope :needs-review :uncat true})]
      (is (= 2 (:total fc)) "All = uncategorized rows (scope neutralized)")
      (is (= 2 (:unreviewed fc)) "Needs review = those also unreviewed")
      (is (= 2 (:uncategorized fc)) "Uncategorized = needs-review rows that are uncategorized"))))

;; --- Split editor seed ------------------------------------------------------

(deftest split-editor-seed-rows
  (testing "parts seed in :split/order; magnitude string + signed seed-cents + category/memo"
    (let [seed (view/split-editor-seed
                {:transaction/splits
                 [{:split/order 1 :split/amount -8.00M :split/memo "household"
                   :split/category {:db/id 11 :category/name "Groceries"}}
                  {:split/order 0 :split/amount -42.00M :split/memo nil
                   :split/category nil}]})]
      (is (= 2 (count seed)))
      (is (= {:amount "42.00" :category-id nil :memo nil :seed-cents -4200} (first seed))
          "order 0 leads; outflow magnitude positive, seed-cents signed")
      (is (= {:amount "8.00" :category-id 11 :memo "household" :seed-cents -800} (second seed)))))
  (testing "a positive (inflow) split keeps its sign in seed-cents but shows magnitude"
    (is (= {:amount "15.50" :category-id 7 :memo nil :seed-cents 1550}
           (first (view/split-editor-seed
                   {:transaction/splits [{:split/order 0 :split/amount 15.50M
                                          :split/category {:db/id 7}}]}))))))

(deftest split-editor-seed-empty-when-unsplit
  (is (= [] (view/split-editor-seed {:transaction/splits []})))
  (is (= [] (view/split-editor-seed {}))))

;; --- Category rollup (ported from frontend categoryRollup.test.ts) -----------

(defn- rcat
  ([id nm type] (rcat id nm type nil nil))
  ([id nm type parent sort]
   (cond-> {:db/id id :category/name nm :category/type type}
     parent (assoc :category/parent {:db/id parent})
     sort   (assoc :category/sort-order sort))))

(defn- rtx [id amount cat-id]
  (cond-> {:db/id id :transaction/amount amount}
    cat-id (assoc :transaction/category {:db/id cat-id})))

(defn- rsplit-tx [id parts]
  {:db/id id
   :transaction/amount (reduce + 0 (map :amount parts))
   :transaction/splits (map-indexed (fn [i p] (cond-> {:split/order i :split/amount (:amount p)}
                                                (:cat p) (assoc :split/category {:db/id (:cat p)})))
                                    parts)})

(defn- ≈ [a b] (< (abs (- (double a) (double b))) 0.005))
(defn- row-ok? [{:keys [ids name depth group? amount]} actual]
  (and (= ids (:ids actual)) (= name (:name actual)) (= depth (:depth actual))
       (= (boolean group?) (boolean (:group? actual))) (≈ amount (:amount actual))))

(deftest rollup-groups-children-under-parent-with-subtotal
  (let [cats [(rcat 1 "Housing" :expense nil 1) (rcat 2 "Mortgage" :expense 1 1) (rcat 3 "Property tax" :expense 1 2)]
        rows (get-in (view/category-rollup [(rtx 10 -1854M 2) (rtx 11 -3390.93M 3)] cats) [:expenses :rows])]
    (is (row-ok? {:ids [1 2 3] :name "Housing" :depth 0 :group? true :amount 5244.93} (nth rows 0)))
    (is (row-ok? {:ids [2] :name "Mortgage" :depth 1 :amount 1854} (nth rows 1)))
    (is (= "Property tax" (:name (nth rows 2))))))

(deftest rollup-parent-own-txns-in-subtotal
  (let [cats [(rcat 1 "Housing" :expense nil 1) (rcat 2 "Mortgage" :expense 1 1)]
        r (view/category-rollup [(rtx 10 -100M 1) (rtx 11 -50M 2)] cats)
        rows (get-in r [:expenses :rows])]
    (is (row-ok? {:ids [1 2] :name "Housing" :depth 0 :group? true :amount 150} (nth rows 0)))
    (is (row-ok? {:ids [2] :name "Mortgage" :depth 1 :amount 50} (nth rows 1)))
    (is (≈ 150 (get-in r [:expenses :total])))))

(deftest rollup-inactive-children-in-group-ids-not-rows
  (let [cats [(rcat 1 "Housing" :expense nil 1) (rcat 2 "Mortgage" :expense 1 1) (rcat 3 "Repairs" :expense 1 2)]
        rows (get-in (view/category-rollup [(rtx 10 -1854M 2)] cats) [:expenses :rows])]
    (is (= [1 2 3] (:ids (nth rows 0))) "the inactive child still rides the group's filter ids")
    (is (= ["Housing" "Mortgage"] (map :name rows)) "but is not rendered as its own row")))

(deftest rollup-childless-leaf
  (let [rows (get-in (view/category-rollup [(rtx 10 -43.76M 1)] [(rcat 1 "Groceries" :expense nil 1)]) [:expenses :rows])]
    (is (row-ok? {:ids [1] :name "Groceries" :depth 0 :group? false :amount 43.76} (nth rows 0)))))

(deftest rollup-splits-attribute-to-part-categories
  (let [cats [(rcat 1 "Groceries" :expense nil 1) (rcat 2 "Home supplies" :expense nil 2)]
        r (view/category-rollup [(rsplit-tx 10 [{:amount -30M :cat 1} {:amount -20M :cat 2}])] cats)
        rows (get-in r [:expenses :rows])]
    (is (≈ 30 (:amount (first (filter #(= "Groceries" (:name %)) rows)))))
    (is (≈ 20 (:amount (first (filter #(= "Home supplies" (:name %)) rows)))))
    (is (≈ 50 (get-in r [:expenses :total])))))

(deftest rollup-separates-types-and-excludes-transfers-from-net
  (let [cats [(rcat 1 "Paycheck" :income nil 1) (rcat 2 "Groceries" :expense nil 1) (rcat 3 "CC Payment" :transfer nil 1)]
        r (view/category-rollup [(rtx 10 5000M 1) (rtx 11 -3000M 2) (rtx 12 -2000M 3)] cats)]
    (is (≈ 5000 (get-in r [:income :total])))
    (is (≈ 3000 (get-in r [:expenses :total])))
    (is (≈ 2000 (get-in r [:transfers :total])))
    (is (= "CC Payment" (:name (first (get-in r [:transfers :rows])))))
    (is (≈ 2000 (:grand-total r)) "net = income − expenses; transfers excluded")))

(deftest rollup-uncategorized-bucketed-by-sign
  (let [r (view/category-rollup [(rtx 10 5000M 1) (rtx 11 -200M nil) (rtx 12 300M nil)] [(rcat 1 "Paycheck" :income nil 1)])
        inc-uncat (first (filter #(= "Uncategorized" (:name %)) (get-in r [:income :rows])))
        exp-uncat (first (filter #(= "Uncategorized" (:name %)) (get-in r [:expenses :rows])))]
    (is (and (:uncategorized? inc-uncat) (= [] (:ids inc-uncat)) (≈ 300 (:amount inc-uncat))))
    (is (and (:uncategorized? exp-uncat) (= [] (:ids exp-uncat)) (≈ 200 (:amount exp-uncat))))
    (is (≈ 5100 (:grand-total r)) "uncategorized real money still counts toward net")))

(deftest rollup-signed-net-negative-when-expenses-exceed-income
  (let [r (view/category-rollup [(rtx 10 4740.66M 1) (rtx 11 -5244.93M 2)]
                                [(rcat 1 "Paycheck" :income nil 1) (rcat 2 "Housing" :expense nil 1)])]
    (is (≈ -504.27 (:grand-total r)))))

(deftest rollup-group-ids-stay-within-section
  (let [cats [(rcat 1 "Misc" :expense nil 1) (rcat 2 "Supplies" :expense 1 1) (rcat 3 "Refunds" :income 1 2)]
        r (view/category-rollup [(rtx 10 -40M 2) (rtx 11 25M 3)] cats)
        misc (first (filter #(= "Misc" (:name %)) (get-in r [:expenses :rows])))
        refunds (first (filter #(= "Refunds" (:name %)) (get-in r [:income :rows])))]
    (is (= [1 2] (:ids misc)) "the cross-type income child is excluded from the expense group")
    (is (row-ok? {:ids [3] :name "Refunds" :depth 0 :group? false :amount 25} refunds)
        "and surfaces as a standalone income leaf")))

(deftest rollup-empty
  (let [r (view/category-rollup [] [])]
    (is (= [] (get-in r [:income :rows]) (get-in r [:expenses :rows]) (get-in r [:transfers :rows])))
    (is (zero? (get-in r [:income :total])))
    (is (zero? (:grand-total r)))))

(deftest rollup-orders-by-sort-order
  (let [cats [(rcat 1 "Transportation" :expense nil 2) (rcat 2 "Housing" :expense nil 1)]
        rows (get-in (view/category-rollup [(rtx 10 -50M 1) (rtx 11 -100M 2)] cats) [:expenses :rows])]
    (is (= ["Housing" "Transportation"] (map :name rows)))))

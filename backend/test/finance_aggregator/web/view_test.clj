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
          :transaction/reconciled false :transaction/transfer-hidden false}
         m))

(def ^:private t1
  (tx {:db/id 1 :transaction/posted-date #inst "2025-01-01"
       :transaction/payee "Acme Payroll" :transaction/amount 4000
       :transaction/category {:db/id 10 :category/name "Salary"}
       :transaction/account (bank 100 "Chequing")}))

(def ^:private t2
  (tx {:db/id 2 :transaction/posted-date #inst "2025-01-05"
       :transaction/payee "Superstore" :transaction/amount -85
       :transaction/reconciled true
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

;; t5 + t6 model a split family: two first-class part rows (each carries
;; :transaction/split-parent, which the view engine ignores — a part is a plain row).
;; They share the parent's payee/posted-date (copied at split time), sum to the
;; excluded parent's -200, and carry their own category/memo-as-description.

(def ^:private t5
  (tx {:db/id 5 :transaction/posted-date #inst "2025-01-20"
       :transaction/payee "Costco" :transaction/amount -120
       :transaction/effective-description "food"
       :transaction/split-parent {:db/id 50}
       :transaction/category {:db/id 11 :category/name "Groceries"}
       :transaction/account (bank 101 "Visa")}))

(def ^:private t6
  (tx {:db/id 6 :transaction/posted-date #inst "2025-01-20"
       :transaction/payee "Costco" :transaction/amount -80
       :transaction/effective-description "household"
       :transaction/split-parent {:db/id 50}
       :transaction/category nil
       :transaction/account (bank 101 "Visa")}))

(def ^:private txs [t1 t2 t3 t4 t5 t6])
(defn- ids [ts] (map :db/id ts))

;; --- Scope ------------------------------------------------------------------

(deftest scope-filter
  (is (= [1 2 3 4 5 6] (ids (view/filter-txs txs {:scope :all})))
      "all shows everything")
  (is (= [1 3 4 5 6] (ids (view/filter-txs txs {:scope :to-reconcile})))
      "to-reconcile drops the reconciled row (t2)"))

;; --- Search -----------------------------------------------------------------

(deftest search-filter
  (testing "matches across payee / effective description / category"
    (is (= [2] (ids (view/filter-txs txs {:scope :all :search "superstore"}))) "payee")
    (is (= [3] (ids (view/filter-txs txs {:scope :all :search "housing"})))   "category name")
    (is (= [5] (ids (view/filter-txs txs {:scope :all :search "food"})))
        "a part's memo is its effective description — a plain-row match")
    (is (= [2 5] (ids (view/filter-txs txs {:scope :all :search "groceries"})))
        "category on a normal row AND a part row")
    (is (= [5 6] (ids (view/filter-txs txs {:scope :all :search "costco"})))
        "a family's rows share the parent's payee, so both parts match"))
  (testing "case-insensitive + blank = no filter"
    (is (= [2] (ids (view/filter-txs txs {:scope :all :search "SUPERSTORE"}))))
    (is (= [1 2 3 4 5 6] (ids (view/filter-txs txs {:scope :all :search ""}))))))

;; --- Hide transfers ---------------------------------------------------------

(deftest hide-transfers-filter
  (is (= [1 2 4 5 6] (ids (view/filter-txs txs {:scope :all :hide-transfers true})))
      "drops the transfer-hidden row (t3)")
  (is (= [1 2 3 4 5 6] (ids (view/filter-txs txs {:scope :all :hide-transfers false})))))

;; --- Account / institution funnels ------------------------------------------

(deftest account-funnel
  (is (= [2 4 5 6] (ids (view/filter-txs txs {:scope :all :accounts #{101}}))) "Visa only")
  (is (= [1 2 3 4 5 6] (ids (view/filter-txs txs {:scope :all :accounts #{}})))
      "empty selection = no filter")
  (is (= [1 2 3 4 5 6] (ids (view/filter-txs txs {:scope :all :institutions #{1000}})))
      "all share the one institution"))

;; --- Category funnel ∪ Uncategorized chip -----------------------------------

(deftest category-funnel-and-uncat
  (is (= [1] (ids (view/filter-txs txs {:scope :all :categories #{10}}))) "Salary")
  (is (= [2 5] (ids (view/filter-txs txs {:scope :all :categories #{11}})))
      "Groceries — a normal row and a part row, each by its own category")
  (is (= [4 6] (ids (view/filter-txs txs {:scope :all :uncat true})))
      "uncat chip: t4 (no category) + t6 (an uncategorized part row)")
  (is (= [3 4 6] (ids (view/filter-txs txs {:scope :all :categories #{12} :uncat true})))
      "union: Housing(t3) ∪ uncategorized(t4,t6)"))

;; --- Sorting ----------------------------------------------------------------

(deftest sorting
  (is (= [3 5 2 6 4 1] (ids (view/sort-txs txs {:col :amount :dir :asc}))) "amount asc")
  (is (= [1 4 6 2 5 3] (ids (view/sort-txs txs {:col :amount :dir :desc}))) "amount desc")
  (is (= [1 5 6 3 2 4] (ids (view/sort-txs txs {:col :payee :dir :asc})))
      "payee asc, lower-cased; the family's parts cluster on their shared payee (stable)")
  (is (= [1 2 3 4 5 6] (ids (view/sort-txs txs nil))) "no sort = unchanged")
  (is (= [1 2 3 4 5 6] (ids (view/sort-txs txs {:col :unknown :dir :asc})))
      "unknown column = unchanged"))

(deftest date-sort-uses-effective-posted-date
  (testing "the :date column sorts on :transaction/effective-posted-date, not the raw
            imported :transaction/posted-date — a manual override wins"
    (let [overridden (assoc t3 :transaction/effective-posted-date #inst "2024-06-01") ; earlier than t1
          plain (map #(assoc % :transaction/effective-posted-date (:transaction/posted-date %)) [t1 t2 t4 t5 t6])
          rows (conj (vec plain) overridden)]
      (is (= [3 1 2 4 5 6] (ids (view/sort-txs rows {:col :date :dir :asc})))
          "t3's override (June 2024) sorts it before every posted-date-only row")))
  (testing "a missing effective-posted-date falls back to epoch 0, same as the old posted-date fallback"
    (let [no-date (dissoc t1 :transaction/effective-posted-date :transaction/posted-date)
          dated (assoc t2 :transaction/effective-posted-date (:transaction/posted-date t2))]
      (is (= [1 2] (ids (view/sort-txs [no-date dated] {:col :date :dir :asc})))))))

;; --- Pagination -------------------------------------------------------------

(deftest pagination
  (let [p0 (view/paginate txs 0 2)]
    (is (= [1 2] (ids (:rows p0))))
    (is (= {:total 6 :page 0 :page-count 3 :page-size 2}
           (dissoc p0 :rows))))
  (is (= [3 4] (ids (:rows (view/paginate txs 1 2)))) "second page")
  (let [over (view/paginate txs 5 2)]
    (is (= [5 6] (ids (:rows over))) "out-of-range page clamps to the last")
    (is (= 2 (:page over))))
  (let [zero (view/paginate txs 0 0)]
    (is (= 25 (:page-size zero)) "non-positive page size defaults to 25")
    (is (= 6 (count (:rows zero))))))

;; --- Compose ----------------------------------------------------------------

(deftest view-composition
  (let [v (view/view txs {:scope :to-reconcile
                          :sort {:col :amount :dir :asc}
                          :page 0 :page-size 2})]
    (is (= [3 5] (ids (:rows v)))
        "to-reconcile → [t1 t3 t4 t5 t6], amount asc → [3 5 6 4 1], page 0/2 → [3 5]")
    (is (= 5 (:total v)) "filtered total drives pagination")
    (is (= 3 (:page-count v)))))

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
      (let [m (view/present txs {:scope :to-reconcile} {:linger #{2}})]
        (is (= #{2} (:stale-ids (:result m))) "edited-out t2 kept stale")))
    (testing ":categories add the whole-month rollup"
      (is (= (view/category-rollup txs []) (:rollup (view/present txs vs {:categories []})))))
    (testing "the monthly-close panel is assembled by the handler, never by present"
      (is (not (contains? (view/present txs vs {}) :close)))
      (is (not (contains? (view/present txs vs {:categories []}) :close))))))

;; --- Monthly close ----------------------------------------------------------
;; Coverage-strict reconciliation: Chequing (eid 100) has t1 (+4000, 2025-01-01) and t3
;; (-2000, 2025-01-15) — net 2000; Visa (eid 101) has t2 (-85, 2025-01-05), t4 (-50,
;; 2025-01-12) and the split family's part rows t5 (-120) + t6 (-80) on 2025-01-20 —
;; net -335 (the parts carry the account and sum to the excluded parent's -200, so the
;; account total is unchanged). `month-span` is the calendar month's own (open, close]
;; span, wide enough to cover every January date the fixture uses.

(def ^:private month-span {:start #inst "2024-12-31" :end #inst "2025-01-31"})

(deftest reconcile-month-boundary-only-still-works
  (testing "a reconciled month-boundary balance → :reconciled, no :difference (nothing to blame)"
    (let [m (view/reconcile-month txs {100 2000M} month-span {})
          row (first (filter #(= 100 (:account-id %)) (:rows m)))]
      (is (= :reconciled (:status row)))
      (is (zero? (:uncovered row)))
      (is (nil? (:difference row)) "no single number to blame once it reconciles")))
  (testing "a drifting month-boundary balance with no statements → :partial, :difference set"
    (let [m (view/reconcile-month txs {100 2500M} month-span {})
          row (first (filter #(= 100 (:account-id %)) (:rows m)))]
      (is (= :partial (:status row)))
      (is (= 500M (:difference row)) "reported − computed = 2500 − 2000"))))

(deftest reconcile-month-statement-covered-account-reconciles
  (testing "two adjacent statements jointly covering the month → :reconciled even with no boundary balance"
    (let [stmts {101 [{:start-date #inst "2024-12-31" :end-date #inst "2025-01-12" :status :reconciled}
                      {:start-date #inst "2025-01-12" :end-date #inst "2025-01-31" :status :reconciled}]}
          m (view/reconcile-month txs {} month-span stmts)
          visa (first (filter #(= 101 (:account-id %)) (:rows m)))]
      (is (= :reconciled (:status visa)))
      (is (zero? (:uncovered visa)))
      (is (nil? (:difference visa)) "statement-covered accounts have no single boundary number")))
  (testing "the whole month reconciles when every account is covered (boundary or statements)"
    (let [stmts {101 [{:start-date #inst "2024-12-31" :end-date #inst "2025-01-12" :status :reconciled}
                      {:start-date #inst "2025-01-12" :end-date #inst "2025-01-31" :status :reconciled}]}
          m (view/reconcile-month txs {100 2000M} month-span stmts)]
      (is (true? (:all-reconciled? m))))))

(deftest reconcile-month-partial-coverage-account
  (testing "a single statement covering only part of the month → :partial with the right :uncovered count"
    (let [stmts {101 [{:start-date #inst "2024-12-31" :end-date #inst "2025-01-12" :status :reconciled}]}
          m (view/reconcile-month txs {} month-span stmts)
          visa (first (filter #(= 101 (:account-id %)) (:rows m)))]
      (is (= :partial (:status visa)))
      (is (= 2 (:uncovered visa)) "the part rows t5+t6 (2025-01-20) fall outside the one statement")
      (is (= #inst "2025-01-20" (:first-uncovered visa)))
      (is (false? (:all-reconciled? m)) "one uncovered account blocks the whole month"))))

(deftest month-close-gate
  (testing "every transaction reconciled + categorized + balanced → ready to close"
    (let [done  [(tx {:db/id 1 :transaction/amount 100 :transaction/reconciled true
                      :transaction/category {:db/id 10 :category/name "X"}
                      :transaction/account (bank 100 "A")})]
          recon {:rows [{:status :reconciled}] :all-reconciled? true}
          m     (view/month-close done {:reconciliation recon :net-now 100M})]
      (is (true? (get-in m [:gate :ready?])))
      (is (zero? (get-in m [:gate :unreconciled])))
      (is (false? (:closed? m)))))
  (testing "unreconciled / uncategorized rows block ready"
    (let [txs2  [(tx {:db/id 2 :transaction/amount -5 :transaction/reconciled false
                      :transaction/category nil :transaction/account (bank 100 "A")})]
          recon {:rows [{:status :reconciled}] :all-reconciled? true}
          m     (view/month-close txs2 {:reconciliation recon :net-now -5M})]
      (is (= 1 (get-in m [:gate :unreconciled])))
      (is (= 1 (get-in m [:gate :uncategorized])))
      (is (false? (get-in m [:gate :ready?])))))
  (testing "an unreconciled (no-snapshot) account blocks ready even when reconciled+categorized"
    (let [done  [(tx {:db/id 1 :transaction/amount 100 :transaction/reconciled true
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
;; The drill-in card: one account's opening/closing balances vs its tracked activity.
;; Chequing (eid 100): +4000 -2000 = 2000; Visa (eid 101): -85 -50 -200 = -335. Opening/closing
;; dates below bound the calendar month (Dec 31 open, Jan 31 close) so the coverage headline —
;; not just the boundary period's own verdict — can be checked against the fixture's dates.

(deftest focus-close-single-account-verdict
  (testing "matches when opening + tracked = closing (the period-delta ties out), coverage follows"
    (let [f (view/focus-close txs {:account-eid 100 :opening 500M :closing 2500M
                                   :opening-date #inst "2024-12-31" :closing-date #inst "2025-01-31"})]
      (is (= "Chequing" (:name f)) "name comes from the account's activity")
      (is (= 2000M (:tracked f)))
      (is (= 2000M (:expected f)) "closing − opening")
      (is (= :reconciled (:boundary-status f)))
      (is (= #inst "2024-12-31" (:opening-date f)) "boundary dates pass through for the labels")
      (is (= :reconciled (:status (:coverage f)))
          "the boundary span covers every Chequing txn this month")
      (is (zero? (:uncovered (:coverage f))))))
  (testing "off by the difference when they don't tie out (drift) → coverage is :partial"
    (let [f (view/focus-close txs {:account-eid 100 :opening 0M :closing 2500M})]
      (is (= 2500M (:expected f)))
      (is (= 500M (:boundary-difference f)) "expected − tracked = 2500 − 2000")
      (is (= :drift (:boundary-status f)))
      (is (= :partial (:status (:coverage f)))
          "a non-reconciling boundary contributes no coverage span")))
  (testing "no verdict until both balances are entered (no-snapshot), coverage follows"
    (let [f (view/focus-close txs {:account-eid 101 :opening nil :closing -335M})]
      (is (= "Visa" (:name f)))
      (is (= -335M (:tracked f)))
      (is (nil? (:expected f)))
      (is (= :no-snapshot (:boundary-status f)))
      (is (= :no-snapshot (:status (:coverage f))))))
  (testing "an account with no activity this month tracks 0 and uses the name fallback"
    (let [f (view/focus-close txs {:account-eid 999 :name-fallback "Savings" :opening 10M :closing 10M})]
      (is (= "Savings" (:name f)))
      (is (= 0M (:tracked f)))
      (is (= 0M (:expected f)))
      (is (= :reconciled (:boundary-status f)) "0 change explained by 0 activity → reconciled")
      (is (= :reconciled (:status (:coverage f))))))
  (testing "a statement covering the whole span reconciles the account even with no boundary balance"
    (let [stmt {:start-date #inst "2024-12-31" :end-date #inst "2025-01-31" :status :reconciled}
          f (view/focus-close txs {:account-eid 101 :opening nil :closing nil :statements [stmt]})]
      (is (= :no-snapshot (:boundary-status f)) "no boundary balances on file")
      (is (= :reconciled (:status (:coverage f)))
          "the statement alone covers every Visa txn this month")
      (is (= [stmt] (:statements f)))))
  (testing "a statement covering only part of the span leaves coverage :partial"
    (let [stmt {:start-date #inst "2024-12-31" :end-date #inst "2025-01-12" :status :reconciled}
          f (view/focus-close txs {:account-eid 101 :opening nil :closing nil :statements [stmt]})
          cov (:coverage f)]
      (is (= :partial (:status cov)))
      (is (= 2 (:uncovered cov)) "the part rows t5+t6 (2025-01-20) fall outside the statement")
      (is (= #inst "2025-01-20" (:first-uncovered cov))))))

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
;; (To-reconcile → reconcile → row jumps to the bottom): both were the linger composition
;; *appending* the lingered row instead of keeping it in place.

(deftest view-with-linger-keeps-original-position
  (testing "BUG 8 (no sort): a now-reconciled row lingers in its natural position, not at the end"
    ;; t2 (db/id 2) is reconciled; under :to-reconcile it no longer matches, but it's lingering.
    (let [v (view/view-with-linger txs {:scope :to-reconcile} #{2})]
      (is (= [1 2 3 4 5 6] (ids (:rows v)))
          "t2 stays between t1 and t3 (source order), NOT appended after t6")
      (is (= #{2} (:stale-ids v)) "the lingered-but-unmatching row is reported stale")))
  (testing "BUG 7 (no sort): a now-categorized row lingers in place under the Uncat chip"
    ;; With :uncat the chip matches t4 + t6 (both lack a category). Pretend t4 was just
    ;; categorized so it no longer matches; it must hold its slot, not move/disappear.
    (let [categorized-t4 (assoc t4 :transaction/category {:db/id 99 :category/name "Dining"})
          month          [t1 t2 t3 categorized-t4 t5 t6]
          v              (view/view-with-linger month {:scope :all :uncat true} #{4})]
      (is (= [4 6] (ids (:rows v)))
          "t4 keeps its position ahead of t6 even though it no longer matches the chip")
      (is (= #{4} (:stale-ids v)) "t4 reported stale"))))

(deftest view-with-linger-sorts-stale-rows-in-place
  (testing "with an active sort, a lingered non-matching row sorts into its natural slot"
    ;; :to-reconcile drops t2 (reconciled); linger keeps it. amount asc over [1 2 3 4 5 6] is
    ;; [3(-2000) 5(-120) 2(-85) 6(-80) 4(-50) 1(4000)] — t2 sorts between t5 and t6, not appended.
    (let [v (view/view-with-linger txs {:scope :to-reconcile
                                        :sort {:col :amount :dir :asc}}
                                   #{2})]
      (is (= [3 5 2 6 4 1] (ids (:rows v)))
          "t2 sorts to its amount position, not the bottom")
      (is (= #{2} (:stale-ids v))))))

(deftest view-with-linger-matching-rows-not-stale
  (testing "a lingered row that STILL matches the filter is not stale (just re-rendered)"
    (let [v (view/view-with-linger txs {:scope :all} #{1})]
      (is (= [1 2 3 4 5 6] (ids (:rows v))) "all rows present, normal order")
      (is (= #{} (:stale-ids v)) "t1 still matches :all, so nothing is stale"))))

(deftest view-with-linger-empty-and-clear-semantics
  (testing "no linger set = plain view, never stale"
    (let [v (view/view-with-linger txs {:scope :to-reconcile} #{})]
      (is (= [1 3 4 5 6] (ids (:rows v))) "reconciled t2 dropped, none lingering")
      (is (= #{} (:stale-ids v))))
    (let [v (view/view-with-linger txs {:scope :to-reconcile} nil)]
      (is (= [1 3 4 5 6] (ids (:rows v))) "nil linger set tolerated as empty")
      (is (= #{} (:stale-ids v)))))
  (testing "clearing the linger set (the next view change) drops the stale row entirely"
    ;; Same view-state as the lingering case, but with the set cleared: t2 is simply gone.
    (let [lingered (view/view-with-linger txs {:scope :to-reconcile} #{2})
          cleared  (view/view-with-linger txs {:scope :to-reconcile} #{})]
      (is (= [1 2 3 4 5 6] (ids (:rows lingered))) "while lingering: t2 visible")
      (is (= [1 3 4 5 6]   (ids (:rows cleared)))  "after clear: t2 gone"))))

(deftest view-with-linger-paginates-after-injection
  (testing ":total + pagination count the injected stale row"
    ;; to-reconcile base = [1 3 4 5 6] (5 rows); linger t2 → 6 visible. page 0, size 2.
    (let [v (view/view-with-linger txs {:scope :to-reconcile :page 0 :page-size 2} #{2})]
      (is (= 6 (:total v)) "stale row included in the total")
      (is (= 3 (:page-count v)) "6 rows / 2 = 3 pages")
      (is (= [1 2] (ids (:rows v))) "first page holds t1 then the in-place stale t2"))))

;; --- Funnel options ---------------------------------------------------------
;; The header funnels list the distinct account/institution/category values present this
;; month, each with a per-id transaction count, label-sorted. These were duplicated in the
;; page; they now live here next to the id-extracting fns they share.

(deftest account-funnel-options
  (testing "no other filter → every account with its full-month count, label-sorted"
    (is (= [{:id 100 :label "Chequing" :count 2}
            {:id 101 :label "Visa" :count 4}]
           (view/account-options txs {}))))
  (testing "the funnel's OWN selection doesn't affect its option counts (so nothing zeroes out)"
    (is (= [{:id 100 :label "Chequing" :count 2}
            {:id 101 :label "Visa" :count 4}]
           (view/account-options txs {:accounts #{101}}))))
  (testing "but ANOTHER active filter is faceted in: to-reconcile drops t2 → Visa 4→3"
    (is (= [{:id 100 :label "Chequing" :count 2}
            {:id 101 :label "Visa" :count 3}]
           (view/account-options txs {:scope :to-reconcile}))))
  (testing "missing-account rows contribute no option"
    (is (= [] (view/account-options [(tx {:db/id 9 :transaction/amount 1})] {})))))

(deftest institution-funnel-options
  (is (= [{:id 1000 :label "Test Bank" :count 6}] (view/institution-options txs {}))))

(deftest category-funnel-option-counts
  (testing "real categories with per-row counts (a part row counts like any row),
            Uncategorized excluded, label-sorted"
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
      (is (= 6 (:total fc)))
      (is (= 5 (:unreconciled fc)) "t2 reconciled")
      (is (= 2 (:uncategorized fc)) "t4 + the uncategorized part row t6")
      (is (= 1 (:transfers-hidden fc)) "t3")))
  (testing "scope/uncat compose: each count reflects the OTHER active filters, not its own"
    ;; uncat chip on, scope=to-reconcile. Category-dim removed for :total/:unreconciled leaves
    ;; scope, but those use drop scope: All = the 2 uncategorized rows, To reconcile = those
    ;; that are unreconciled (both) = 2.
    (let [fc (view/facet-counts txs {:scope :to-reconcile :uncat true})]
      (is (= 2 (:total fc)) "All = uncategorized rows (scope neutralized)")
      (is (= 2 (:unreconciled fc)) "To reconcile = those also unreconciled")
      (is (= 2 (:uncategorized fc)) "Uncategorized = to-reconcile rows that are uncategorized"))))

;; --- Split editor seed ------------------------------------------------------

(deftest split-editor-seed-rows
  (testing "parts (pulled via :transaction/_split-parent) seed in :transaction/split-order;
            magnitude string + signed seed-cents + category/memo/id"
    (let [seed (view/split-editor-seed
                {:transaction/_split-parent
                 [{:db/id 101 :transaction/split-order 1 :transaction/amount -8.00M
                   :transaction/description "household"
                   :transaction/category {:db/id 11 :category/name "Groceries"}}
                  {:db/id 100 :transaction/split-order 0 :transaction/amount -42.00M
                   :transaction/description nil
                   :transaction/category nil}]})]
      (is (= 2 (count seed)))
      (is (= {:id 100 :amount "42.00" :category-id nil :memo nil :seed-cents -4200} (first seed))
          "order 0 leads; outflow magnitude positive, seed-cents signed")
      (is (= {:id 101 :amount "8.00" :category-id 11 :memo "household" :seed-cents -800} (second seed)))))
  (testing "a positive (inflow) split keeps its sign in seed-cents but shows magnitude"
    (is (= {:id 7 :amount "15.50" :category-id 7 :memo nil :seed-cents 1550}
           (first (view/split-editor-seed
                   {:transaction/_split-parent
                    [{:db/id 7 :transaction/split-order 0 :transaction/amount 15.50M
                      :transaction/category {:db/id 7}}]}))))))

(deftest split-editor-seed-empty-when-unsplit
  (is (= [] (view/split-editor-seed {:transaction/_split-parent []})))
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

(deftest rollup-part-rows-attribute-their-own-amounts
  ;; A split family's parts are plain rows: each attributes its own amount to its own
  ;; category (the parent is excluded from the list fns upstream), so the family lands in
  ;; different rollup rows and the totals tie out with the category filter. The engine
  ;; ignores :transaction/split-parent — included here to prove it changes nothing.
  (let [cats [(rcat 1 "Groceries" :expense nil 1) (rcat 2 "Home supplies" :expense nil 2)]
        part (fn [id amount cat] (assoc (rtx id amount cat) :transaction/split-parent {:db/id 99}))
        r (view/category-rollup [(part 10 -30M 1) (part 11 -20M 2)] cats)
        rows (get-in r [:expenses :rows])]
    (is (≈ 30 (:amount (first (filter #(= "Groceries" (:name %)) rows)))))
    (is (≈ 20 (:amount (first (filter #(= "Home supplies" (:name %)) rows)))))
    (is (≈ 50 (get-in r [:expenses :total])) "the family's parts sum to the parent's amount")))

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

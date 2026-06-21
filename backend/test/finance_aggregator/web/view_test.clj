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

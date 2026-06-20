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

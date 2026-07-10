(ns finance-aggregator.db.transactions-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [finance-aggregator.db.transactions :as transactions]
            [finance-aggregator.db.categories :as categories]
            [finance-aggregator.db.transfers :as db-transfers]
            [finance-aggregator.test-utils.setup :as setup]))

(use-fixtures :each setup/with-empty-db)

(defn- make-category! [name ident]
  (:db/id (categories/create! setup/*test-conn*
                              {:category/name name :category/type :expense :category/ident ident})))

(defn- make-tx!
  "Create an institution/account/transaction and return the transaction's :db/id.
   `tx-attrs` is merged over the base transaction map (e.g. to set :transaction/amount)."
  [external-id tx-attrs]
  (d/transact! setup/*test-conn* [{:institution/id (str "inst-" external-id)
                                   :institution/name "Test Bank"}])
  (d/transact! setup/*test-conn* [{:account/external-id (str "acct-" external-id)
                                   :account/external-name "Test Account"
                                   :account/institution [:institution/id (str "inst-" external-id)]}])
  (d/transact! setup/*test-conn*
               [(merge {:transaction/external-id external-id
                        :transaction/account [:account/external-id (str "acct-" external-id)]
                        :transaction/amount -100.00M
                        :transaction/payee "Costco"
                        :transaction/posted-date (java.util.Date.)}
                       tx-attrs)])
  (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:transaction/external-id external-id])))

(defn- live-part-ids
  "The db ids of tx-id's live split parts (the new :transaction/split-parent model)."
  [tx-id]
  (d/q '[:find [?p ...] :in $ ?tx :where [?p :transaction/split-parent ?tx]]
       (d/db setup/*test-conn*) tx-id))

(defn- live-parts
  "tx-id's live split parts, pulled in full and ordered by :transaction/split-order —
   for asserting on a part's amount/category/memo/reconciled/external-id/transfer-pair
   and its inherited date/posted-date/account."
  [tx-id]
  (->> (d/q '[:find [(pull ?p [:db/id :transaction/amount :transaction/split-order
                               :transaction/description :transaction/reconciled
                               :transaction/external-id :transaction/date :transaction/posted-date
                               {:transaction/category [:db/id]}
                               {:transaction/account [:db/id]}
                               {:transaction/transfer-pair [:db/id]}]) ...]
             :in $ ?tx :where [?p :transaction/split-parent ?tx]]
           (d/db setup/*test-conn*) tx-id)
       (sort-by :transaction/split-order)))

(defn- range-date [y m d]
  (-> (java.time.LocalDate/of y m d)
      (.atStartOfDay java.time.ZoneOffset/UTC) .toInstant java.util.Date/from))

(deftest datalevin-split-primitives-test
  ;; Prove the Datalevin features the split-part model builds on, before building
  ;; on them: a `not` clause excluding parents-with-parts from a query, and
  ;; reverse-ref pulls (:transaction/_split-parent) both at top level and nested
  ;; inside a forward join.
  (let [parent (make-tx! "cap-parent" {:transaction/amount -100.00M})]
    (d/transact! setup/*test-conn*
                 [{:transaction/external-id "cap-part-1"
                   :transaction/split-parent parent
                   :transaction/split-order 0
                   :transaction/amount -60.00M}
                  {:transaction/external-id "cap-part-2"
                   :transaction/split-parent parent
                   :transaction/split-order 1
                   :transaction/amount -40.00M}])
    (let [db (d/db setup/*test-conn*)]
      (testing "a `not` clause excludes transactions that have parts"
        (is (= #{"cap-part-1" "cap-part-2"}
               (set (d/q '[:find [?ext ...]
                           :where
                           [?e :transaction/external-id ?ext]
                           (not [?p :transaction/split-parent ?e])]
                         db)))))
      (testing "a reverse ref pulls at top level (parent → its parts)"
        (let [pulled (d/pull db '[:db/id {:transaction/_split-parent
                                          [:db/id :transaction/amount]}] parent)]
          (is (= 2 (count (:transaction/_split-parent pulled))))))
      (testing "a reverse ref pulls nested inside a forward join (part → parent → siblings)"
        (let [part (d/pull db '[{:transaction/split-parent
                                 [:db/id :transaction/amount
                                  {:transaction/_split-parent [:db/id :transaction/amount]}]}]
                           [:transaction/external-id "cap-part-1"])]
          (is (= 2 (count (get-in part [:transaction/split-parent
                                        :transaction/_split-parent])))))))))

(deftest list-for-account-range-test
  (testing "returns the account's txns in the reconcile span (from, to], posted-date ascending"
    (d/transact! setup/*test-conn* [{:account/external-id "acct-r" :account/external-name "Visa"}])
    (let [acct (d/q '[:find ?a . :in $ ?e :where [?a :account/external-id ?e]]
                    (d/db setup/*test-conn*) "acct-r")]
      (d/transact! setup/*test-conn*
                   [{:transaction/external-id "r1" :transaction/account acct
                     :transaction/amount 10M :transaction/posted-date (range-date 2026 4 16)}
                    {:transaction/external-id "r2" :transaction/account acct
                     :transaction/amount 20M :transaction/posted-date (range-date 2026 4 20)}
                    {:transaction/external-id "r3" :transaction/account acct
                     :transaction/amount 30M :transaction/posted-date (range-date 2026 5 16)}
                    {:transaction/external-id "r4" :transaction/account acct
                     :transaction/amount 40M :transaction/posted-date (range-date 2026 5 20)}])
      (let [rows (transactions/list-for-account-range setup/*test-conn* acct
                                                      (range-date 2026 4 16) (range-date 2026 5 16))]
        (is (= ["r2" "r3"] (map :transaction/external-id rows))
            "excludes the start-date txn (its balance is the opening), includes the end-date txn")
        (is (= [(range-date 2026 4 20) (range-date 2026 5 16)] (map :transaction/posted-date rows))
            "posted-date ascending"))))

  (testing "a parent with live parts is excluded; its parts show up instead"
    (d/transact! setup/*test-conn* [{:account/external-id "acct-rx" :account/external-name "Visa"}])
    (let [acct (d/q '[:find ?a . :in $ ?e :where [?a :account/external-id ?e]]
                    (d/db setup/*test-conn*) "acct-rx")]
      (d/transact! setup/*test-conn*
                   [{:transaction/external-id "rx1" :transaction/account acct
                     :transaction/amount -100M :transaction/posted-date (range-date 2026 4 18)}])
      (let [tx-id (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:transaction/external-id "rx1"]))]
        (transactions/set-splits! setup/*test-conn* tx-id [{:amount "-60.00"} {:amount "-40.00"}])
        (let [rows (transactions/list-for-account-range setup/*test-conn* acct
                                                         (range-date 2026 4 16) (range-date 2026 5 1))]
          (is (not (contains? (set (map :transaction/external-id rows)) "rx1"))
              "the parent is excluded once it has parts")
          (is (= 2 (count rows)) "both parts show up instead"))))))

(deftest list-for-span-test
  (testing "returns transactions whose effective posted date falls in [start-date, end-date)"
    (make-tx! "span-1" {:transaction/posted-date (range-date 2025 6 5)})
    (make-tx! "span-2" {:transaction/posted-date (range-date 2025 6 20)})
    (make-tx! "span-3" {:transaction/posted-date (range-date 2025 7 3)})
    (is (= #{"span-2" "span-3"}
           (set (map :transaction/external-id
                     (transactions/list-for-span setup/*test-conn*
                                                 (range-date 2025 6 15) (range-date 2025 7 10)))))
        "a span crossing the month boundary returns the June 20 + July 3 rows, excluding June 5")
    (is (= #{"span-1" "span-2"}
           (set (map :transaction/external-id
                     (transactions/list-for-month setup/*test-conn* "2025-06"))))
        "list-for-month (the wrapper) still returns exactly the June rows")))

(deftest with-derived-fields-effective-posted-date-test
  (testing "annotates :transaction/effective-posted-date, falling back through the chain"
    (let [tx-id (make-tx! "tx-eff-1" {:transaction/posted-date (range-date 2026 3 10)})
          tx (transactions/by-id setup/*test-conn* tx-id)]
      (is (= (range-date 2026 3 10) (:transaction/effective-posted-date tx))
          "no override — falls back to the imported posted-date")))

  (testing "an override wins over the imported posted-date"
    (let [tx-id (make-tx! "tx-eff-2" {:transaction/posted-date (range-date 2026 3 10)})]
      (d/transact! setup/*test-conn* [{:db/id tx-id :transaction/user-posted-date (range-date 2026 4 1)}])
      (let [tx (transactions/by-id setup/*test-conn* tx-id)]
        (is (= (range-date 2026 4 1) (:transaction/effective-posted-date tx)))
        (is (= (range-date 2026 3 10) (:transaction/posted-date tx))
            "the imported posted-date is never mutated")))))

(deftest list-for-month-override-moves-row-across-boundary-test
  (testing "a manual override moves a row OUT of the month it was imported into and INTO
            the month the override names — boundary-exact on both months' edges"
    (let [tx-id (make-tx! "tx-move-1" {:transaction/posted-date (range-date 2026 3 15)})]
      (is (= ["tx-move-1"] (map :transaction/external-id
                                (transactions/list-for-month setup/*test-conn* "2026-03")))
          "initially in March, by the imported posted-date")
      (is (= [] (transactions/list-for-month setup/*test-conn* "2026-04")))

      ;; Override to April 1st 00:00 UTC — the exact inclusive start of April / exclusive
      ;; end of March.
      (transactions/set-user-posted-date! setup/*test-conn* tx-id (range-date 2026 4 1))
      (is (= [] (transactions/list-for-month setup/*test-conn* "2026-03"))
          "moved OUT of March")
      (is (= ["tx-move-1"] (map :transaction/external-id
                                (transactions/list-for-month setup/*test-conn* "2026-04")))
          "moved INTO April, at the exact boundary instant")

      ;; Clearing the override falls back to the imported posted-date, reverting the move.
      (transactions/set-user-posted-date! setup/*test-conn* tx-id nil)
      (is (= ["tx-move-1"] (map :transaction/external-id
                                (transactions/list-for-month setup/*test-conn* "2026-03")))
          "clearing the override reverts to March")
      (is (= [] (transactions/list-for-month setup/*test-conn* "2026-04"))))))

(deftest list-for-account-range-override-moves-row-across-statement-boundary-test
  (testing "a manual override moves a row across a statement-span boundary; results stay
            sorted by the effective date"
    (d/transact! setup/*test-conn* [{:account/external-id "acct-stmt-move" :account/external-name "Visa"}])
    (let [acct (d/q '[:find ?a . :in $ ?e :where [?a :account/external-id ?e]]
                    (d/db setup/*test-conn*) "acct-stmt-move")]
      (d/transact! setup/*test-conn*
                   [{:transaction/external-id "sm-1" :transaction/account acct
                     :transaction/amount 10M :transaction/posted-date (range-date 2026 4 20)}
                    {:transaction/external-id "sm-2" :transaction/account acct
                     :transaction/amount 20M :transaction/posted-date (range-date 2026 5 20)}])
      (let [tx-id (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:transaction/external-id "sm-1"]))
            span1 [(range-date 2026 4 16) (range-date 2026 5 16)]
            span2 [(range-date 2026 5 16) (range-date 2026 6 16)]]
        (is (= ["sm-1"] (map :transaction/external-id
                             (apply transactions/list-for-account-range setup/*test-conn* acct span1)))
            "sm-1 starts in span1 (April 20)")
        (is (= ["sm-2"] (map :transaction/external-id
                             (apply transactions/list-for-account-range setup/*test-conn* acct span2)))
            "sm-2 already sits in span2 (May 20)")

        ;; Override sm-1's posted-date into span2.
        (transactions/set-user-posted-date! setup/*test-conn* tx-id (range-date 2026 5 25))
        (is (= [] (apply transactions/list-for-account-range setup/*test-conn* acct span1))
            "moved OUT of span1")
        (let [rows (apply transactions/list-for-account-range setup/*test-conn* acct span2)]
          (is (= ["sm-2" "sm-1"] (map :transaction/external-id rows))
              "moved INTO span2, sorted by effective date ascending (sm-2's May 20 before sm-1's overridden May 25)")
          (is (= [(range-date 2026 5 20) (range-date 2026 5 25)]
                 (map :transaction/effective-posted-date rows))))))))

(deftest set-user-posted-date-test
  (testing "sets and clears the override on a plain (unsplit) transaction"
    (let [tx-id (make-tx! "tx-upd-1" {:transaction/posted-date (range-date 2026 3 10)})
          set-result (transactions/set-user-posted-date! setup/*test-conn* tx-id (range-date 2026 4 1))]
      (is (= (range-date 2026 4 1) (:transaction/user-posted-date set-result)))
      (is (= (range-date 2026 4 1) (:transaction/effective-posted-date set-result)))
      (is (= (range-date 2026 3 10) (:transaction/posted-date set-result))
          "the imported posted-date is never mutated")
      (is (= (range-date 2026 4 1) (transactions/user-posted-date setup/*test-conn* tx-id))
          "the reader returns the current override")

      (let [cleared (transactions/set-user-posted-date! setup/*test-conn* tx-id nil)]
        (is (nil? (:transaction/user-posted-date cleared)))
        (is (= (range-date 2026 3 10) (:transaction/effective-posted-date cleared))
            "falls back to the imported posted-date")
        (is (nil? (transactions/user-posted-date setup/*test-conn* tx-id))))))

  (testing "setting the override on a split PART writes the ROOT and every live part uniformly"
    (let [tx-id (make-tx! "tx-upd-2" {:transaction/amount -100.00M
                                      :transaction/posted-date (range-date 2026 3 10)})]
      (transactions/set-splits! setup/*test-conn* tx-id [{:amount "-60.00"} {:amount "-40.00"}])
      (let [[p1 p2] (live-parts tx-id)
            override (range-date 2026 4 15)]
        (transactions/set-user-posted-date! setup/*test-conn* (:db/id p1) override)
        (is (= override (:transaction/user-posted-date
                         (d/pull (d/db setup/*test-conn*) '[:transaction/user-posted-date] tx-id)))
            "the root got the override too")
        (doseq [p [p1 p2]]
          (is (= override (:transaction/user-posted-date
                           (d/pull (d/db setup/*test-conn*) '[:transaction/user-posted-date] (:db/id p))))
              "every live part carries the same override"))
        (is (= override (transactions/user-posted-date setup/*test-conn* (:db/id p1)))
            "reader resolves a part to the root's value")
        (is (= override (transactions/user-posted-date setup/*test-conn* tx-id))
            "reader on the root itself agrees")

        ;; Clearing via a different part still clears everywhere.
        (transactions/set-user-posted-date! setup/*test-conn* (:db/id p2) nil)
        (is (nil? (:transaction/user-posted-date
                   (d/pull (d/db setup/*test-conn*) '[:transaction/user-posted-date] tx-id))))
        (doseq [p [p1 p2]]
          (is (nil? (:transaction/user-posted-date
                     (d/pull (d/db setup/*test-conn*) '[:transaction/user-posted-date] (:db/id p))))))))))

(deftest list-for-month-excludes-split-parents-test
  (testing "a parent with live parts is excluded from the month list; its parts show up,
            inheriting its posted-date"
    (let [tx-id (make-tx! "tx-lm-1" {:transaction/amount -100.00M
                                     :transaction/posted-date (range-date 2026 3 10)})]
      (transactions/set-splits! setup/*test-conn* tx-id
                                [{:amount "-60.00"} {:amount "-40.00"}])
      (let [rows (transactions/list-for-month setup/*test-conn* "2026-03")
            ext-ids (set (map :transaction/external-id rows))
            part-rows (filter #(str/starts-with? (:transaction/external-id %) "split-") rows)]
        (is (not (contains? ext-ids "tx-lm-1")) "the parent is excluded once it has parts")
        (is (= 2 (count part-rows)) "both parts show up")
        (is (every? #(= (range-date 2026 3 10) (:transaction/posted-date %)) part-rows)
            "parts inherit the parent's posted-date")))))

(deftest list-all-excludes-split-parents-test
  (testing "a parent with live parts is excluded from list-all; its parts show up instead"
    (let [tx-id (make-tx! "tx-la-1" {:transaction/amount -100.00M})]
      (transactions/set-splits! setup/*test-conn* tx-id [{:amount "-60.00"} {:amount "-40.00"}])
      (let [rows (transactions/list-all setup/*test-conn*)
            ext-ids (set (map :transaction/external-id rows))]
        (is (not (contains? ext-ids "tx-la-1")))
        (is (= 2 (count (filter #(str/starts-with? (:transaction/external-id %) "split-") rows))))))))

(deftest set-splits-test
  (testing "happy path: creates parts with bigdec amounts, category refs and order"
    (let [groceries (make-category! "Groceries" :category/groceries)
          household (make-category! "Household" :category/household)
          tx-id (make-tx! "tx-split-1" {:transaction/amount -100.00M})
          updated (transactions/set-splits! setup/*test-conn* tx-id
                                            [{:amount "-60.00" :category-id groceries}
                                             {:amount "-40.00" :category-id household :memo "paper towels"}])
          parts (live-parts tx-id)]
      (is (= 2 (count parts)))
      (is (== -60.00M (:transaction/amount (first parts))))
      (is (== -40.00M (:transaction/amount (second parts))))
      (is (= [0 1] (map :transaction/split-order parts)))
      (is (= groceries (get-in (first parts) [:transaction/category :db/id])))
      (is (= household (get-in (second parts) [:transaction/category :db/id])))
      (is (= "paper towels" (:transaction/description (second parts))))
      (is (= tx-id (:db/id updated)) "the returned shape is the refreshed PARENT, not a part")))

  (testing "a new part inherits the parent's account/user/date/posted-date/payee"
    (let [a (make-category! "IF" :category/if)
          date (java.util.Date. 1700000000000)
          tx-id (make-tx! "tx-split-inherit" {:transaction/amount -100.00M :transaction/date date
                                              :transaction/posted-date date})
          acct (get-in (d/pull (d/db setup/*test-conn*) '[{:transaction/account [:db/id]}] tx-id)
                       [:transaction/account :db/id])]
      (transactions/set-splits! setup/*test-conn* tx-id
                                [{:amount "-60.00" :category-id a} {:amount "-40.00" :category-id a}])
      (let [[p1] (live-parts tx-id)
            payee (:transaction/payee (d/pull (d/db setup/*test-conn*) '[:transaction/payee] (:db/id p1)))]
        (is (= date (:transaction/date p1)))
        (is (= date (:transaction/posted-date p1)))
        (is (= "Costco" payee))
        (is (= acct (get-in p1 [:transaction/account :db/id]))))))

  (testing "the original transaction is never mutated"
    (let [a (make-category! "A" :category/a)
          b (make-category! "B" :category/b)
          cat (make-category! "Orig" :category/orig)
          tx-id (make-tx! "tx-split-2" {:transaction/amount -100.00M :transaction/category cat})]
      (transactions/set-splits! setup/*test-conn* tx-id
                                [{:amount "-60.00" :category-id a} {:amount "-40.00" :category-id b}])
      (let [tx (d/pull (d/db setup/*test-conn*)
                       '[:transaction/external-id :transaction/amount
                         {:transaction/category [:db/id]}] tx-id)]
        (is (= "tx-split-2" (:transaction/external-id tx)))
        (is (== -100.00M (:transaction/amount tx)))
        (is (= cat (get-in tx [:transaction/category :db/id]))))))

  (testing "resubmitting without ids replaces every part (all rows are treated as new)"
    (let [a (make-category! "A2" :category/a2)
          b (make-category! "B2" :category/b2)
          c (make-category! "C2" :category/c2)
          tx-id (make-tx! "tx-split-3" {:transaction/amount -90.00M})
          _ (transactions/set-splits! setup/*test-conn* tx-id
                                      [{:amount "-50.00" :category-id a}
                                       {:amount "-40.00" :category-id b}])
          first-ids (set (live-part-ids tx-id))]
      (transactions/set-splits! setup/*test-conn* tx-id
                                [{:amount "-30.00" :category-id a}
                                 {:amount "-30.00" :category-id b}
                                 {:amount "-30.00" :category-id c}])
      (is (= 3 (count (live-part-ids tx-id))))
      (is (not-any? first-ids (live-part-ids tx-id)) "the old parts were retracted, not reused")))

  (testing "clear: an empty vector removes all parts, leaving the transaction"
    (let [a (make-category! "A3" :category/a3)
          b (make-category! "B3" :category/b3)
          tx-id (make-tx! "tx-split-4" {:transaction/amount -100.00M})]
      (transactions/set-splits! setup/*test-conn* tx-id
                                [{:amount "-60.00" :category-id a} {:amount "-40.00" :category-id b}])
      (transactions/set-splits! setup/*test-conn* tx-id [])
      (is (empty? (live-part-ids tx-id)))
      (is (== -100.00M (:transaction/amount (d/pull (d/db setup/*test-conn*) '[:transaction/amount] tx-id))))))

  (testing "non-reconciling splits throw :bad-request and write nothing"
    (let [a (make-category! "A4" :category/a4)
          b (make-category! "B4" :category/b4)
          tx-id (make-tx! "tx-split-5" {:transaction/amount -100.00M})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"add up"
                            (transactions/set-splits! setup/*test-conn* tx-id
                                                      [{:amount "-60.00" :category-id a}
                                                       {:amount "-30.00" :category-id b}])))
      (is (empty? (live-part-ids tx-id)))))

  (testing "a missing transaction throws :not-found"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                          (transactions/set-splits! setup/*test-conn* 999999
                                                    [{:amount "-1.00" :category-id 1}
                                                     {:amount "-1.00" :category-id 2}]))))

  (testing "a provider re-sync (upsert by external-id) does not clobber the parts"
    (let [a (make-category! "A5" :category/a5)
          b (make-category! "B5" :category/b5)
          tx-id (make-tx! "tx-split-6" {:transaction/amount -100.00M})]
      (transactions/set-splits! setup/*test-conn* tx-id
                                [{:amount "-60.00" :category-id a} {:amount "-40.00" :category-id b}])
      ;; Re-sync the same provider map — the parts are separate entities, not a
      ;; ref the parent's own upsert could ever touch.
      (d/transact! setup/*test-conn* [{:transaction/external-id "tx-split-6"
                                       :transaction/amount -100.00M
                                       :transaction/payee "Costco Wholesale"}])
      (is (= 2 (count (live-part-ids tx-id))))
      (is (= "Costco Wholesale"
             (:transaction/payee (d/pull (d/db setup/*test-conn*) '[:transaction/payee] tx-id)))))))

(deftest set-splits-category-existence-test
  (testing "a category id that does not exist is rejected with :bad-request and writes nothing"
    (let [a (make-category! "A7" :category/a7)
          tx-id (make-tx! "tx-split-8" {:transaction/amount -100.00M})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"existing category"
                            (transactions/set-splits! setup/*test-conn* tx-id
                                                      [{:amount "-60.00" :category-id a}
                                                       {:amount "-40.00" :category-id 999999}])))
      (is (empty? (live-part-ids tx-id)))))

  (testing "an id that points at a non-category entity (e.g. an account) is rejected"
    (let [a (make-category! "A8" :category/a8)
          tx-id (make-tx! "tx-split-9" {:transaction/amount -100.00M})
          acct-id (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id]
                                  [:account/external-id "acct-tx-split-9"]))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"existing category"
                            (transactions/set-splits! setup/*test-conn* tx-id
                                                      [{:amount "-60.00" :category-id a}
                                                       {:amount "-40.00" :category-id acct-id}])))
      (is (empty? (live-part-ids tx-id)))))

  (testing "a nil category-id is allowed — the Uncategorized chip owns that part"
    (let [a (make-category! "A9" :category/a9)
          tx-id (make-tx! "tx-split-10" {:transaction/amount -100.00M})]
      (transactions/set-splits! setup/*test-conn* tx-id
                                [{:amount "-60.00" :category-id a} {:amount "-40.00" :category-id nil}])
      (is (nil? (get-in (second (live-parts tx-id)) [:transaction/category :db/id]))))))

(deftest set-splits-guard-part-of-split-test
  (testing "calling set-splits! on a live PART (instead of its parent) is rejected"
    (let [a (make-category! "PG" :category/pg)
          tx-id (make-tx! "tx-partguard-1" {:transaction/amount -100.00M})]
      (transactions/set-splits! setup/*test-conn* tx-id
                                [{:amount "-60.00" :category-id a} {:amount "-40.00" :category-id a}])
      (let [part-id (:db/id (first (live-parts tx-id)))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"edit the split on the original transaction"
                              (transactions/set-splits! setup/*test-conn* part-id
                                                        [{:amount "-1.00"} {:amount "-2.00"}])))))))

(deftest set-splits-guard-matched-transfer-test
  (testing "an already-matched transfer can't be split until it's unmatched"
    (let [a (make-category! "MT" :category/mt)
          out-id (make-tx! "tx-mtxfer-out" {:transaction/amount -100.00M})
          in-id (make-tx! "tx-mtxfer-in" {:transaction/amount 100.00M})]
      (d/transact! setup/*test-conn* [{:db/id out-id :transaction/transfer-pair in-id}
                                      {:db/id in-id :transaction/transfer-pair out-id}])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unmatch this transfer"
                            (transactions/set-splits! setup/*test-conn* out-id
                                                      [{:amount "-60.00" :category-id a}
                                                       {:amount "-40.00" :category-id a}])))
      (is (empty? (live-part-ids out-id)) "nothing was written")
      ;; [] (un-split) is a no-op on an unsplit, matched transaction — the guard only
      ;; blocks an attempt to actually create parts.
      (transactions/set-splits! setup/*test-conn* out-id [])
      (is (= in-id (get-in (d/pull (d/db setup/*test-conn*)
                                   '[{:transaction/transfer-pair [:db/id]}] out-id)
                           [:transaction/transfer-pair :db/id]))
          "the match survives an un-split call"))))

(deftest set-splits-stale-id-creates-test
  (testing "a row whose :id doesn't name a live part of this parent is treated as a fresh row"
    (let [a (make-category! "SI" :category/si)
          tx-id (make-tx! "tx-stale-1" {:transaction/amount -100.00M})]
      (transactions/set-splits! setup/*test-conn* tx-id
                                [{:amount "-60.00" :category-id a} {:amount "-40.00" :category-id a}])
      (let [gone-id (:db/id (first (live-parts tx-id)))]
        ;; Un-split retracts both live parts, so gone-id now names nothing live.
        (transactions/set-splits! setup/*test-conn* tx-id [])
        (is (empty? (live-part-ids tx-id)))
        ;; Re-splitting with that now-stale id creates fresh parts rather than
        ;; throwing or silently no-op'ing an update against a retracted entity —
        ;; this is what an undo-then-redo of a part removal replays through. (Note:
        ;; Datalevin can recycle a retracted entity's id for a new entity, so this
        ;; doesn't assert id inequality — it asserts the row was genuinely CREATED,
        ;; not "updated" in place.)
        (transactions/set-splits! setup/*test-conn* tx-id
                                  [{:id gone-id :amount "-70.00" :category-id a}
                                   {:amount "-30.00" :category-id a}])
        (let [parts (live-parts tx-id)]
          (is (= 2 (count parts)))
          (is (every? #(str/starts-with? (:transaction/external-id %) "split-") parts)
              "both rows became freshly-created parts")
          (is (= #{-70.00M -30.00M} (into #{} (map :transaction/amount) parts))
              "amounts as submitted"))))))

(deftest set-splits-update-preserves-state-test
  (testing "updating a live part in place preserves its reconciled flag and external-id"
    (let [a (make-category! "UP" :category/up)
          b (make-category! "UQ" :category/uq)
          tx-id (make-tx! "tx-update-1" {:transaction/amount -100.00M})]
      (transactions/set-splits! setup/*test-conn* tx-id
                                [{:amount "-60.00" :category-id a} {:amount "-40.00" :category-id b}])
      (let [[p1 p2] (live-parts tx-id)
            p1-id (:db/id p1)
            p1-ext (:transaction/external-id p1)]
        ;; A part is a plain transaction — the generic set-reconciled! already works on it.
        (transactions/set-reconciled! setup/*test-conn* p1-id true)
        ;; Editing the split (amount + category change, same :id) must not touch
        ;; p1's reconciled flag or regenerate its external-id.
        (transactions/set-splits! setup/*test-conn* tx-id
                                  [{:id p1-id :amount "-70.00" :category-id b}
                                   {:id (:db/id p2) :amount "-30.00" :category-id a}])
        (let [p1' (first (filter #(= p1-id (:db/id %)) (live-parts tx-id)))]
          (is (== -70.00M (:transaction/amount p1')) "amount updated")
          (is (= b (get-in p1' [:transaction/category :db/id])) "category updated")
          (is (true? (:transaction/reconciled p1')) "reconciled flag preserved across the edit")
          (is (= p1-ext (:transaction/external-id p1')) "external-id stable")))))

  (testing "a nil category-id / blank memo on an update retracts them"
    (let [a (make-category! "MZ" :category/mz)
          tx-id (make-tx! "tx-clearmemo-1" {:transaction/amount -100.00M})]
      (transactions/set-splits! setup/*test-conn* tx-id
                                [{:amount "-60.00" :category-id a :memo "temp"}
                                 {:amount "-40.00" :category-id a}])
      (let [[p1 p2] (live-parts tx-id)]
        (transactions/set-splits! setup/*test-conn* tx-id
                                  [{:id (:db/id p1) :amount "-60.00" :category-id nil :memo "  "}
                                   {:id (:db/id p2) :amount "-40.00" :category-id a}])
        (let [p1' (first (live-parts tx-id))]
          (is (nil? (get-in p1' [:transaction/category :db/id])) "category retracted")
          (is (nil? (:transaction/description p1')) "blank memo retracted"))))))

(deftest set-splits-retract-unlinks-transfer-test
  (testing "dropping a matched part from the split unlinks its partner, not just the part"
    (let [a (make-category! "TA" :category/ta)
          tx-id (make-tx! "tx-unlink-1" {:transaction/amount -100.00M})
          partner-id (make-tx! "tx-unlink-partner" {:transaction/amount 30.00M})
          _ (transactions/set-splits! setup/*test-conn* tx-id
                                      [{:amount "-30.00" :category-id a}
                                       {:amount "-30.00" :category-id a}
                                       {:amount "-40.00" :category-id a}])
          [p1 p2 p3] (live-parts tx-id)]
      (db-transfers/confirm-match! setup/*test-conn* (:db/id p1) partner-id)
      ;; Resubmit without p1 (drop it), folding its share into p2.
      (transactions/set-splits! setup/*test-conn* tx-id
                                [{:id (:db/id p2) :amount "-60.00" :category-id a}
                                 {:id (:db/id p3) :amount "-40.00" :category-id a}])
      (is (= 2 (count (live-part-ids tx-id))) "p1 retracted, p2/p3 remain")
      (is (nil? (:transaction/external-id
                 (d/pull (d/db setup/*test-conn*) '[:transaction/external-id] (:db/id p1))))
          "p1 is gone")
      (is (nil? (:transaction/transfer-pair
                 (d/pull (d/db setup/*test-conn*) '[{:transaction/transfer-pair [:db/id]}] partner-id)))
          "the partner's back-ref is cleared, not left dangling"))))

(deftest split-drift-annotation-test
  (testing "a part is flagged :transaction/split-drift once its siblings no longer sum to the parent's amount"
    (let [a (make-category! "DR" :category/dr)
          tx-id (make-tx! "tx-drift-1" {:transaction/amount -100.00M})]
      (transactions/set-splits! setup/*test-conn* tx-id
                                [{:amount "-60.00" :category-id a} {:amount "-40.00" :category-id a}])
      (let [part-id (:db/id (first (live-parts tx-id)))
            pulled #(transactions/with-derived-fields
                     (d/pull (d/db setup/*test-conn*) transactions/transaction-pull-pattern %))]
        (is (not (:transaction/split-drift (pulled part-id))) "reconciled: no drift flag")
        ;; A re-sync changes the parent's amount in place; the stored parts no longer reconcile.
        (d/transact! setup/*test-conn* [{:transaction/external-id "tx-drift-1" :transaction/amount -105.00M}])
        (is (true? (:transaction/split-drift (pulled part-id)))
            "drift flagged on the part after the parent amount changed")
        (is (not (contains? (pulled tx-id) :transaction/split-drift))
            "the parent itself (not a part) is never flagged")))))

(deftest split-editor-root-test
  (testing "resolves the transaction itself for a non-part, and the PARENT for a part —
            so every path into the split editor lands on the family's parent (depth is 1)"
    (let [a (make-category! "ER" :category/er)
          tx-id (make-tx! "tx-root-1" {:transaction/amount -100.00M})]
      (is (= tx-id (:db/id (transactions/split-editor-root setup/*test-conn* tx-id)))
          "an unsplit transaction is its own editor root")
      (transactions/set-splits! setup/*test-conn* tx-id
                                [{:amount "-60.00" :category-id a} {:amount "-40.00" :category-id a}])
      (is (= tx-id (:db/id (transactions/split-editor-root setup/*test-conn* tx-id)))
          "a split parent is its own editor root")
      (let [part-id (:db/id (first (live-parts tx-id)))
            root (transactions/split-editor-root setup/*test-conn* part-id)]
        (is (= tx-id (:db/id root)) "a part resolves to its parent")
        (is (= 2 (count (:transaction/_split-parent root)))
            "the root is pulled like by-id — the editor's parts ride along"))))

  (testing "nil for a missing transaction, like by-id"
    (is (nil? (transactions/split-editor-root setup/*test-conn* 99999999)))))

(deftest set-reconciled-test
  (testing "marks a transaction reconciled and clears it again"
    (let [tx-id (make-tx! "tx-rev-1" {:transaction/amount -100.00M})]
      (is (true? (:transaction/reconciled (transactions/set-reconciled! setup/*test-conn* tx-id true))))
      (is (true? (:transaction/reconciled
                  (d/pull (d/db setup/*test-conn*) '[:transaction/reconciled] tx-id))))
      ;; Clearing retracts the datom so it nil-puns to not-reconciled.
      (transactions/set-reconciled! setup/*test-conn* tx-id false)
      (is (nil? (:transaction/reconciled
                 (d/pull (d/db setup/*test-conn*) '[:transaction/reconciled] tx-id))))))

  (testing "an unsplit transaction's reconciled flag is left absent when never set"
    (let [tx-id (make-tx! "tx-rev-2" {:transaction/amount -100.00M})
          tx (transactions/with-derived-fields
              (d/pull (d/db setup/*test-conn*) transactions/transaction-pull-pattern tx-id))]
      (is (not (contains? tx :transaction/reconciled)))))

  (testing "splitting clears the parent's own reconciled flag so it can't resurface on un-split"
    (let [a (make-category! "RE" :category/re)
          b (make-category! "RF" :category/rf)
          tx-id (make-tx! "tx-rev-5" {:transaction/amount -100.00M})]
      (transactions/set-reconciled! setup/*test-conn* tx-id true)
      (transactions/set-splits! setup/*test-conn* tx-id
                                [{:amount "-60.00" :category-id a}
                                 {:amount "-40.00" :category-id b}])
      ;; The stored parent flag is gone the moment it's split...
      (is (nil? (:transaction/reconciled
                 (d/pull (d/db setup/*test-conn*) '[:transaction/reconciled] tx-id))))
      ;; ...so after clearing the splits the transaction is not reconciled again.
      (transactions/set-splits! setup/*test-conn* tx-id [])
      (is (not (:transaction/reconciled
                (transactions/with-derived-fields
                 (d/pull (d/db setup/*test-conn*) transactions/transaction-pull-pattern tx-id))))))))

(deftest split-part-reconciled-via-generic-endpoint-test
  (testing "a part is a plain transaction — the generic set-reconciled! reconciles it
            independently of its siblings, with no split-specific endpoint needed"
    (let [a (make-category! "RA" :category/ra)
          b (make-category! "RB" :category/rb)
          tx-id (make-tx! "tx-rev-3" {:transaction/amount -100.00M})]
      (transactions/set-splits! setup/*test-conn* tx-id
                                [{:amount "-60.00" :category-id a}
                                 {:amount "-40.00" :category-id b}])
      (let [[p1] (live-parts tx-id)]
        (transactions/set-reconciled! setup/*test-conn* (:db/id p1) true)
        (let [[p1' p2'] (live-parts tx-id)]
          (is (true? (:transaction/reconciled p1')) "p1 reconciled")
          (is (not (:transaction/reconciled p2')) "p2 untouched"))))))

(deftest set-user-description-test
  (testing "sets a user description and exposes it as the effective description"
    (let [tx-id (make-tx! "tx-desc-1" {:transaction/amount -100.00M
                                       :transaction/description "STARBUCKS #1234"})
          updated (transactions/set-user-description! setup/*test-conn* tx-id "Coffee with Sam")]
      (is (= "Coffee with Sam" (:transaction/user-description updated)))
      (is (= "Coffee with Sam" (:transaction/effective-description updated)))
      ;; The imported description is never mutated — it's still available alongside.
      (is (= "STARBUCKS #1234" (:transaction/description updated)))))

  (testing "the imported description is never touched in the DB"
    (let [tx-id (make-tx! "tx-desc-2" {:transaction/amount -100.00M
                                       :transaction/description "RAW IMPORT"})]
      (transactions/set-user-description! setup/*test-conn* tx-id "Cleaned up")
      (is (= "RAW IMPORT" (:transaction/description
                           (d/pull (d/db setup/*test-conn*) '[:transaction/description] tx-id))))))

  (testing "a blank description clears the override, falling back to the import"
    (let [tx-id (make-tx! "tx-desc-3" {:transaction/amount -100.00M
                                       :transaction/description "IMPORTED"})]
      (transactions/set-user-description! setup/*test-conn* tx-id "Override")
      (let [cleared (transactions/set-user-description! setup/*test-conn* tx-id "")]
        ;; Cleared override retracts the datom so it nil-puns to no override.
        (is (nil? (:transaction/user-description cleared)))
        ;; ...and the effective description falls back to the import.
        (is (= "IMPORTED" (:transaction/effective-description cleared)))
        (is (nil? (:transaction/user-description
                   (d/pull (d/db setup/*test-conn*) '[:transaction/user-description] tx-id)))))))

  (testing "with no override, the effective description is the imported one"
    (let [tx-id (make-tx! "tx-desc-4" {:transaction/amount -100.00M
                                       :transaction/description "JUST IMPORTED"})
          tx (transactions/with-derived-fields
              (d/pull (d/db setup/*test-conn*) transactions/transaction-pull-pattern tx-id))]
      (is (= "JUST IMPORTED" (:transaction/effective-description tx)))
      (is (not (contains? tx :transaction/user-description)))))

  (testing "an override fills in a missing imported description"
    (let [tx-id (make-tx! "tx-desc-5" {:transaction/amount -100.00M})
          updated (transactions/set-user-description! setup/*test-conn* tx-id "Filled in")]
      (is (= "Filled in" (:transaction/effective-description updated)))))

  (testing "a whitespace-only description is trimmed away and clears the override"
    (let [tx-id (make-tx! "tx-desc-6" {:transaction/amount -100.00M
                                       :transaction/description "IMPORTED"})]
      (transactions/set-user-description! setup/*test-conn* tx-id "Override")
      (let [cleared (transactions/set-user-description! setup/*test-conn* tx-id "   ")]
        (is (nil? (:transaction/user-description cleared)))
        (is (= "IMPORTED" (:transaction/effective-description cleared))))))

  (testing "leading/trailing whitespace is trimmed from a stored override"
    (let [tx-id (make-tx! "tx-desc-7" {:transaction/amount -100.00M})
          updated (transactions/set-user-description! setup/*test-conn* tx-id "  Trader Joe's  ")]
      (is (= "Trader Joe's" (:transaction/user-description updated)))
      (is (= "Trader Joe's" (:transaction/effective-description updated))))))

(deftest current-splits-test
  (testing "reads the live parts in set-splits! input shape, ordered by split-order, ids included"
    (let [a (make-category! "CS" :category/cs)
          tx-id (make-tx! "tx-current-1" {:transaction/amount -100.00M})]
      (transactions/set-splits! setup/*test-conn* tx-id
                                [{:amount "-60.00" :category-id a :memo "groceries"}
                                 {:amount "-40.00" :category-id nil}])
      (let [rows (transactions/current-splits setup/*test-conn* tx-id)]
        (is (= 2 (count rows)))
        (is (= "-60.00" (:amount (first rows))))
        (is (= a (:category-id (first rows))))
        (is (= "groceries" (:memo (first rows))))
        (is (every? (comp int? :id) rows) "each row carries its part's db id")
        (is (nil? (:category-id (second rows))))
        (is (not (contains? (second rows) :memo)) "no memo → no :memo key"))))

  (testing "an unsplit transaction has no current splits"
    (let [tx-id (make-tx! "tx-current-2" {:transaction/amount -100.00M})]
      (is (= [] (transactions/current-splits setup/*test-conn* tx-id))))))

(deftest update-transaction-category-test
  (testing "assigns a category to a transaction"
    ;;  First create a category
    (let [category (categories/create! setup/*test-conn* {:category/name "Groceries"
                                                           :category/type :expense
                                                           :category/ident :category/groceries})
          category-id (:db/id category)

          ;; Create a transaction
          _ (d/transact! setup/*test-conn* [{:institution/id "inst-1"
                                               :institution/name "Test Bank"}])
          _ (d/transact! setup/*test-conn* [{:account/external-id "acct-1"
                                               :account/external-name "Test Account"
                                               :account/institution [:institution/id "inst-1"]}])
          _ (d/transact! setup/*test-conn* [{:transaction/external-id "tx-1"
                                               :transaction/account [:account/external-id "acct-1"]
                                               :transaction/amount -50.00M
                                               :transaction/payee "Whole Foods"
                                               :transaction/posted-date (java.util.Date.)}])

          ;; Get the transaction
          db (d/db setup/*test-conn*)
          tx-before (d/pull db '[*] [:transaction/external-id "tx-1"])
          tx-id (:db/id tx-before)]

      ;; Transaction should not have a category initially
      (is (nil? (:transaction/category tx-before)))

      ;; Assign category
      (let [updated (transactions/update-category! setup/*test-conn* tx-id category-id)]
        (is (= tx-id (:db/id updated)))
        (is (some? (:transaction/category updated)))
        (is (= category-id (get-in updated [:transaction/category :db/id]))))

      ;; Verify persistence
      (let [db (d/db setup/*test-conn*)
            tx-after (d/pull db '[* {:transaction/category [*]}] tx-id)]
        (is (= category-id (get-in tx-after [:transaction/category :db/id])))
        (is (= "Groceries" (get-in tx-after [:transaction/category :category/name]))))))

  (testing "the returned transfer-pair snapshot carries the partner amount, not a bare ref"
    ;; Regression: the pull used a wildcard for :transaction/transfer-pair, which
    ;; returns the partner as a bare {:db/id} with no amount. The frontend's Zod
    ;; schema requires :transaction/amount on the snapshot, so categorizing an
    ;; already-matched transfer returned a 200 the client then failed to parse —
    ;; surfacing as an error even though the write had succeeded.
    (let [category (make-category! "Transfer" :category/transfer)
          out-id (make-tx! "tx-xfer-out" {:transaction/amount -500.00M})
          in-id (make-tx! "tx-xfer-in" {:transaction/amount 500.00M})]
      (d/transact! setup/*test-conn* [{:db/id out-id :transaction/transfer-pair in-id}
                                      {:db/id in-id :transaction/transfer-pair out-id}])
      (let [updated (transactions/update-category! setup/*test-conn* out-id category)
            pair (:transaction/transfer-pair updated)]
        (is (= in-id (:db/id pair)))
        (is (number? (:transaction/amount pair)))
        (is (== 500.00M (:transaction/amount pair))))))

  (testing "removes category from transaction"
    ;; Create category and transaction with category
    (let [category (categories/create! setup/*test-conn* {:category/name "Dining"
                                                           :category/type :expense
                                                           :category/ident :category/dining})
          category-id (:db/id category)

          _ (d/transact! setup/*test-conn* [{:institution/id "inst-2"
                                               :institution/name "Test Bank 2"}])
          _ (d/transact! setup/*test-conn* [{:account/external-id "acct-2"
                                               :account/external-name "Test Account 2"
                                               :account/institution [:institution/id "inst-2"]}])
          _ (d/transact! setup/*test-conn* [{:transaction/external-id "tx-2"
                                               :transaction/account [:account/external-id "acct-2"]
                                               :transaction/amount -25.00M
                                               :transaction/payee "Restaurant"
                                               :transaction/posted-date (java.util.Date.)
                                               :transaction/category category-id}])

          db (d/db setup/*test-conn*)
          tx (d/pull db '[* {:transaction/category [*]}] [:transaction/external-id "tx-2"])
          tx-id (:db/id tx)]

      ;; Should have category
      (is (= category-id (get-in tx [:transaction/category :db/id])))

      ;; Remove category
      (let [updated (transactions/update-category! setup/*test-conn* tx-id nil)]
        (is (= tx-id (:db/id updated)))
        (is (nil? (:transaction/category updated))))

      ;; Verify persistence
      (let [db (d/db setup/*test-conn*)
            tx-after (d/pull db '[* {:transaction/category [*]}] tx-id)]
        (is (nil? (:transaction/category tx-after)))))))

;; --- Manual transactions ---------------------------------------------------

(defn- make-account!
  "Create an account and return its :db/id."
  [external-name]
  (d/transact! setup/*test-conn* [{:account/external-id (str "acct-" external-name)
                                   :account/external-name external-name
                                   :account/provider :plaid}])
  (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:account/external-id (str "acct-" external-name)])))

(deftest create-manual-test
  (testing "stores the canonical signed amount, provider :manual, dates and payee; returns the eid"
    (let [acct (make-account! "Chequing")
          date (java.util.Date. 1700000000000)
          eid  (transactions/create-manual! setup/*test-conn* "test-user"
                                            {:account-eid acct :amount -42.50M :date date
                                             :payee "Cash withdrawal"})
          tx   (d/pull (d/db setup/*test-conn*) '[* {:transaction/account [:db/id]}] eid)]
      (is (some? eid))
      (is (== -42.50M (:transaction/amount tx)) "stored exactly as given (no invert-amount flip)")
      (is (= :manual (:transaction/provider tx)))
      (is (= "Cash withdrawal" (:transaction/payee tx)))
      (is (= date (:transaction/date tx)))
      (is (= date (:transaction/posted-date tx)))
      (is (= acct (get-in tx [:transaction/account :db/id])))
      (is (str/starts-with? (:transaction/external-id tx) "manual-"))))

  (testing "optional category is applied as a post-create overlay"
    (let [acct (make-account! "Visa")
          cat  (make-category! "Groceries" :category/groceries)
          eid  (transactions/create-manual! setup/*test-conn* "test-user"
                                            {:account-eid acct :amount 10.00M
                                             :date (java.util.Date.) :category-id cat})
          tx   (d/pull (d/db setup/*test-conn*) '[{:transaction/category [:db/id]}] eid)]
      (is (= cat (get-in tx [:transaction/category :db/id])))))

  (testing "blank payee/description are omitted, not stored as empty strings"
    (let [acct (make-account! "Savings")
          eid  (transactions/create-manual! setup/*test-conn* "test-user"
                                            {:account-eid acct :amount 1.00M :date (java.util.Date.)
                                             :payee "  " :description ""})
          tx   (d/pull (d/db setup/*test-conn*) '[:transaction/payee :transaction/description] eid)]
      (is (nil? (:transaction/payee tx)))
      (is (nil? (:transaction/description tx)))))

  (testing "missing required fields throw :bad-request"
    (is (thrown? clojure.lang.ExceptionInfo
                 (transactions/create-manual! setup/*test-conn* "test-user"
                                              {:amount 5.00M :date (java.util.Date.)})))))

(deftest delete-manual-test
  (testing "deletes a manually-created transaction, returning no cascaded part ids"
    (let [acct (make-account! "Cash")
          eid  (transactions/create-manual! setup/*test-conn* "test-user"
                                            {:account-eid acct :amount -5.00M :date (java.util.Date.)})]
      (is (= [] (transactions/delete-manual! setup/*test-conn* eid)) "no parts — empty vector")
      (is (nil? (:transaction/external-id
                 (d/pull (d/db setup/*test-conn*) '[:transaction/external-id] eid))))))

  (testing "refuses to delete an imported (non-manual) transaction"
    (let [tx-id (make-tx! "imported-1" {})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"manually-added"
                            (transactions/delete-manual! setup/*test-conn* tx-id)))
      (is (some? (:transaction/external-id
                  (d/pull (d/db setup/*test-conn*) '[:transaction/external-id] tx-id)))))))

(deftest delete-manual-cascades-split-parts-test
  (testing "deleting a split MANUAL parent cascades: its live parts (and any transfer
            link) are retracted in the same transact!, and their ids are returned"
    (let [acct (make-account! "Cash")
          a (make-category! "CascadeA" :category/cascade-a)
          ;; Even split so either live part matches the partner's exact opposite amount
          ;; regardless of query ordering.
          partner-id (make-tx! "tx-cascade-partner" {:transaction/amount 50.00M})
          eid (transactions/create-manual! setup/*test-conn* "test-user"
                                           {:account-eid acct :amount -100.00M :date (java.util.Date.)})
          _ (transactions/set-splits! setup/*test-conn* eid
                                      [{:amount "-50.00" :category-id a}
                                       {:amount "-50.00" :category-id a}])
          [p1 p2] (live-parts eid)]
      (db-transfers/confirm-match! setup/*test-conn* (:db/id p1) partner-id)
      (let [part-ids (transactions/delete-manual! setup/*test-conn* eid)]
        (is (= (set (map :db/id [p1 p2])) (set part-ids)) "returns exactly the cascaded part ids")
        (is (nil? (:transaction/external-id
                   (d/pull (d/db setup/*test-conn*) '[:transaction/external-id] eid)))
            "the parent is gone")
        (is (every? #(nil? (:transaction/external-id
                            (d/pull (d/db setup/*test-conn*) '[:transaction/external-id] %)))
                    part-ids)
            "every part is gone")
        (is (nil? (:transaction/transfer-pair
                   (d/pull (d/db setup/*test-conn*) '[{:transaction/transfer-pair [:db/id]}] partner-id)))
            "the matched partner's back-ref is cleared, not left dangling")))))

(deftest delete-manual-rejects-split-part-test
  (testing "a split part itself (:transaction/provider :split) is rejected by the same
            :manual guard as an imported row — depth is 1, delete via the parent"
    (let [acct (make-account! "Cash")
          eid (transactions/create-manual! setup/*test-conn* "test-user"
                                           {:account-eid acct :amount -100.00M :date (java.util.Date.)})]
      (transactions/set-splits! setup/*test-conn* eid [{:amount "-60.00"} {:amount "-40.00"}])
      (let [part-id (:db/id (first (live-parts eid)))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"manually-added"
                              (transactions/delete-manual! setup/*test-conn* part-id)))
        (is (some? (:transaction/external-id
                    (d/pull (d/db setup/*test-conn*) '[:transaction/external-id] part-id)))
            "the part survives")))))

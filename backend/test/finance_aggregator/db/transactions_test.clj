(ns finance-aggregator.db.transactions-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [finance-aggregator.db.transactions :as transactions]
            [finance-aggregator.db.categories :as categories]
            [finance-aggregator.data.schema :as schema]
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

(defn- split-eids [tx-id]
  (d/q '[:find [?s ...] :in $ ?tx :where [?tx :transaction/splits ?s]]
       (d/db setup/*test-conn*) tx-id))

(defn- sorted-splits [pulled]
  (sort-by :split/order (:transaction/splits pulled)))

(deftest set-splits-test
  (testing "happy path: stores parts with bigdec amounts, category refs and order"
    (let [groceries (make-category! "Groceries" :category/groceries)
          household (make-category! "Household" :category/household)
          tx-id (make-tx! "tx-split-1" {:transaction/amount -100.00M})
          updated (transactions/set-splits! setup/*test-conn* tx-id
                                            [{:amount "-60.00" :category-id groceries}
                                             {:amount "-40.00" :category-id household :memo "paper towels"}])
          parts (sorted-splits updated)]
      (is (= 2 (count parts)))
      (is (== -60.00M (:split/amount (first parts))))
      (is (== -40.00M (:split/amount (second parts))))
      (is (= [0 1] (map :split/order parts)))
      (is (= groceries (get-in (first parts) [:split/category :db/id])))
      (is (= household (get-in (second parts) [:split/category :db/id])))
      (is (= "paper towels" (:split/memo (second parts))))))

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

  (testing "replace: setting new splits removes the old part entities"
    (let [a (make-category! "A2" :category/a2)
          b (make-category! "B2" :category/b2)
          c (make-category! "C2" :category/c2)
          tx-id (make-tx! "tx-split-3" {:transaction/amount -90.00M})
          first-eids (do (transactions/set-splits! setup/*test-conn* tx-id
                                                   [{:amount "-50.00" :category-id a}
                                                    {:amount "-40.00" :category-id b}])
                         (split-eids tx-id))]
      (transactions/set-splits! setup/*test-conn* tx-id
                                [{:amount "-30.00" :category-id a}
                                 {:amount "-30.00" :category-id b}
                                 {:amount "-30.00" :category-id c}])
      (is (= 3 (count (split-eids tx-id))))
      (is (not-any? (set first-eids) (split-eids tx-id)))
      (doseq [eid first-eids]
        (is (nil? (:split/amount (d/pull (d/db setup/*test-conn*) '[:split/amount] eid)))))))

  (testing "clear: an empty vector removes all parts, leaving the transaction"
    (let [a (make-category! "A3" :category/a3)
          b (make-category! "B3" :category/b3)
          tx-id (make-tx! "tx-split-4" {:transaction/amount -100.00M})]
      (transactions/set-splits! setup/*test-conn* tx-id
                                [{:amount "-60.00" :category-id a} {:amount "-40.00" :category-id b}])
      (transactions/set-splits! setup/*test-conn* tx-id [])
      (is (empty? (split-eids tx-id)))
      (is (== -100.00M (:transaction/amount (d/pull (d/db setup/*test-conn*) '[:transaction/amount] tx-id))))))

  (testing "non-reconciling splits throw :bad-request and write nothing"
    (let [a (make-category! "A4" :category/a4)
          b (make-category! "B4" :category/b4)
          tx-id (make-tx! "tx-split-5" {:transaction/amount -100.00M})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"add up"
                            (transactions/set-splits! setup/*test-conn* tx-id
                                                      [{:amount "-60.00" :category-id a}
                                                       {:amount "-30.00" :category-id b}])))
      (is (empty? (split-eids tx-id)))))

  (testing "a missing transaction throws :not-found"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                          (transactions/set-splits! setup/*test-conn* 999999
                                                    [{:amount "-1.00" :category-id 1}
                                                     {:amount "-1.00" :category-id 2}]))))

  (testing "a provider re-sync (upsert by external-id) does not clobber splits"
    (let [a (make-category! "A5" :category/a5)
          b (make-category! "B5" :category/b5)
          tx-id (make-tx! "tx-split-6" {:transaction/amount -100.00M})]
      (transactions/set-splits! setup/*test-conn* tx-id
                                [{:amount "-60.00" :category-id a} {:amount "-40.00" :category-id b}])
      ;; Re-sync the same provider map (no :transaction/splits key), then again with a changed payee.
      (d/transact! setup/*test-conn* [{:transaction/external-id "tx-split-6"
                                       :transaction/amount -100.00M
                                       :transaction/payee "Costco Wholesale"}])
      (is (= 2 (count (split-eids tx-id))))
      (is (= "Costco Wholesale"
             (:transaction/payee (d/pull (d/db setup/*test-conn*) '[:transaction/payee] tx-id))))))

  (testing "retracting the transaction cascades to its split parts (component)"
    (let [a (make-category! "A6" :category/a6)
          b (make-category! "B6" :category/b6)
          tx-id (make-tx! "tx-split-7" {:transaction/amount -100.00M})
          _ (transactions/set-splits! setup/*test-conn* tx-id
                                      [{:amount "-60.00" :category-id a} {:amount "-40.00" :category-id b}])
          eids (split-eids tx-id)]
      (d/transact! setup/*test-conn* [[:db/retractEntity tx-id]])
      (doseq [eid eids]
        (is (nil? (:split/amount (d/pull (d/db setup/*test-conn*) '[:split/amount] eid))))))))

(deftest set-splits-category-existence-test
  (testing "a category id that does not exist is rejected with :bad-request and writes nothing"
    (let [a (make-category! "A7" :category/a7)
          tx-id (make-tx! "tx-split-8" {:transaction/amount -100.00M})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"existing category"
                            (transactions/set-splits! setup/*test-conn* tx-id
                                                      [{:amount "-60.00" :category-id a}
                                                       {:amount "-40.00" :category-id 999999}])))
      (is (empty? (split-eids tx-id)))))

  (testing "an id that points at a non-category entity (e.g. an account) is rejected"
    (let [a (make-category! "A8" :category/a8)
          tx-id (make-tx! "tx-split-9" {:transaction/amount -100.00M})
          acct-id (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id]
                                  [:account/external-id "acct-tx-split-9"]))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"existing category"
                            (transactions/set-splits! setup/*test-conn* tx-id
                                                      [{:amount "-60.00" :category-id a}
                                                       {:amount "-40.00" :category-id acct-id}])))
      (is (empty? (split-eids tx-id))))))

(deftest set-splits-balance-annotation-test
  (testing "the returned transaction is annotated balanced when its parts reconcile"
    (let [a (make-category! "BA" :category/ba)
          b (make-category! "BB" :category/bb)
          tx-id (make-tx! "tx-bal-1" {:transaction/amount -100.00M})
          updated (transactions/set-splits! setup/*test-conn* tx-id
                                            [{:amount "-60.00" :category-id a}
                                             {:amount "-40.00" :category-id b}])]
      (is (true? (:transaction/splits-balanced updated)))))

  (testing "drift after the parent amount changes is reported as not balanced"
    (let [a (make-category! "BC" :category/bc)
          b (make-category! "BD" :category/bd)
          tx-id (make-tx! "tx-bal-2" {:transaction/amount -100.00M})]
      (transactions/set-splits! setup/*test-conn* tx-id
                                [{:amount "-60.00" :category-id a}
                                 {:amount "-40.00" :category-id b}])
      ;; A re-sync overwrites the parent amount in place; the stored parts no longer reconcile.
      (d/transact! setup/*test-conn* [{:transaction/external-id "tx-bal-2" :transaction/amount -105.00M}])
      (let [tx (transactions/with-split-balance
                (d/pull (d/db setup/*test-conn*) transactions/transaction-pull-pattern tx-id))]
        (is (false? (:transaction/splits-balanced tx))))))

  (testing "a transaction with no splits is not annotated"
    (let [tx-id (make-tx! "tx-bal-3" {:transaction/amount -100.00M})
          tx (transactions/with-split-balance
              (d/pull (d/db setup/*test-conn*) transactions/transaction-pull-pattern tx-id))]
      (is (not (contains? tx :transaction/splits-balanced))))))

(deftest set-splits-transfer-pair-snapshot-test
  (testing "splitting an already-matched transfer returns the partner amount in the snapshot"
    ;; Same regression as update-category!: set-splits! pulled the partner via a
    ;; wildcard, losing :transaction/amount and breaking the frontend's parse.
    (let [a (make-category! "SA" :category/sa)
          b (make-category! "SB" :category/sb)
          out-id (make-tx! "tx-xfer-split-out" {:transaction/amount -100.00M})
          in-id (make-tx! "tx-xfer-split-in" {:transaction/amount 100.00M})]
      (d/transact! setup/*test-conn* [{:db/id out-id :transaction/transfer-pair in-id}
                                      {:db/id in-id :transaction/transfer-pair out-id}])
      (let [updated (transactions/set-splits! setup/*test-conn* out-id
                                              [{:amount "-60.00" :category-id a}
                                               {:amount "-40.00" :category-id b}])
            pair (:transaction/transfer-pair updated)]
        (is (= in-id (:db/id pair)))
        (is (number? (:transaction/amount pair)))
        (is (== 100.00M (:transaction/amount pair)))))))

(deftest set-reviewed-test
  (testing "marks a transaction reviewed and clears it again"
    (let [tx-id (make-tx! "tx-rev-1" {:transaction/amount -100.00M})]
      (is (true? (:transaction/reviewed (transactions/set-reviewed! setup/*test-conn* tx-id true))))
      (is (true? (:transaction/reviewed
                  (d/pull (d/db setup/*test-conn*) '[:transaction/reviewed] tx-id))))
      ;; Clearing retracts the datom so it nil-puns to not-reviewed.
      (transactions/set-reviewed! setup/*test-conn* tx-id false)
      (is (nil? (:transaction/reviewed
                 (d/pull (d/db setup/*test-conn*) '[:transaction/reviewed] tx-id))))))

  (testing "an unsplit transaction's reviewed flag is left absent when never set"
    (let [tx-id (make-tx! "tx-rev-2" {:transaction/amount -100.00M})
          tx (transactions/with-derived-fields
              (d/pull (d/db setup/*test-conn*) transactions/transaction-pull-pattern tx-id))]
      (is (not (contains? tx :transaction/reviewed))))))

(deftest set-split-reviewed-test
  (testing "marks one split reviewed independently and returns the refreshed parent"
    (let [a (make-category! "RA" :category/ra)
          b (make-category! "RB" :category/rb)
          tx-id (make-tx! "tx-rev-3" {:transaction/amount -100.00M})
          _ (transactions/set-splits! setup/*test-conn* tx-id
                                      [{:amount "-60.00" :category-id a}
                                       {:amount "-40.00" :category-id b}])
          [one-split] (split-eids tx-id)
          updated (transactions/set-split-reviewed! setup/*test-conn* tx-id one-split true)
          parts (sorted-splits updated)]
      ;; Reviewing one leg reviews exactly that leg, never its sibling.
      (is (= 1 (count (filter :split/reviewed parts))))
      (is (= 2 (count parts)))))

  (testing "a split transaction is reviewed only once every part is reviewed"
    (let [a (make-category! "RC" :category/rc)
          b (make-category! "RD" :category/rd)
          tx-id (make-tx! "tx-rev-4" {:transaction/amount -100.00M})
          _ (transactions/set-splits! setup/*test-conn* tx-id
                                      [{:amount "-60.00" :category-id a}
                                       {:amount "-40.00" :category-id b}])
          [s1 s2] (split-eids tx-id)]
      ;; No parts reviewed yet: roll-up is false.
      (is (false? (:transaction/reviewed
                   (transactions/with-derived-fields
                    (d/pull (d/db setup/*test-conn*) transactions/transaction-pull-pattern tx-id)))))
      (transactions/set-split-reviewed! setup/*test-conn* tx-id s1 true)
      ;; One of two reviewed: still false.
      (is (false? (:transaction/reviewed
                   (transactions/with-derived-fields
                    (d/pull (d/db setup/*test-conn*) transactions/transaction-pull-pattern tx-id)))))
      (let [updated (transactions/set-split-reviewed! setup/*test-conn* tx-id s2 true)]
        ;; Both reviewed: the parent row's effective reviewed roll-up flips true.
        (is (true? (:transaction/reviewed updated)))))))

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

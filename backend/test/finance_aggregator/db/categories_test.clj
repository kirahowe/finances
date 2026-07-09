(ns finance-aggregator.db.categories-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [finance-aggregator.db.categories :as categories]
            [finance-aggregator.test-utils.setup :as setup]))

(use-fixtures :each setup/with-empty-db)

(deftest create-category-test
  (testing "creates a new category with name and type"
    (let [category-data {:category/name "Groceries"
                         :category/type :expense
                         :category/ident :category/groceries}
          result (categories/create! setup/*test-conn* category-data)
          db-id (:db/id result)]

      (is (some? db-id) "Should return entity with db/id")
      (is (= "Groceries" (:category/name result)))
      (is (= :expense (:category/type result)))
      (is (= :category/groceries (:category/ident result)))

      ;; Verify it was persisted
      (let [db (d/db setup/*test-conn*)
            fetched (d/pull db '[*] db-id)]
        (is (= "Groceries" (:category/name fetched))))))

  (testing "creates an income category"
    (let [category-data {:category/name "Salary"
                         :category/type :income
                         :category/ident :category/salary}
          result (categories/create! setup/*test-conn* category-data)]

      (is (= :income (:category/type result)))))

  (testing "unique ident behavior (upsert)"
    (let [category-data {:category/name "Groceries"
                         :category/type :expense
                         :category/ident :category/groceries}
          first-create (categories/create! setup/*test-conn* category-data)
          first-id (:db/id first-create)
          ;; Creating with same ident updates the existing entity (upsert)
          updated-data (assoc category-data :category/name "Updated Groceries")
          second-create (categories/create! setup/*test-conn* updated-data)
          second-id (:db/id second-create)]

      ;; Should be the same entity (upserted)
      (is (= first-id second-id))
      (is (= "Updated Groceries" (:category/name second-create))))))

(deftest list-categories-test
  (testing "returns empty list when no categories"
    (let [categories (categories/list-all setup/*test-conn*)]
      (is (empty? categories))))

  (testing "returns all categories"
    (categories/create! setup/*test-conn* {:category/name "Groceries"
                                            :category/type :expense
                                            :category/ident :category/groceries})
    (categories/create! setup/*test-conn* {:category/name "Dining"
                                            :category/type :expense
                                            :category/ident :category/dining})
    (categories/create! setup/*test-conn* {:category/name "Salary"
                                            :category/type :income
                                            :category/ident :category/salary})

    (let [categories (categories/list-all setup/*test-conn*)]
      (is (= 3 (count categories)))
      (is (every? :db/id categories))
      (is (every? :category/name categories))
      (is (every? :category/type categories)))))

(deftest get-category-by-id-test
  (testing "retrieves category by db/id"
    (let [created (categories/create! setup/*test-conn* {:category/name "Groceries"
                                                          :category/type :expense
                                                          :category/ident :category/groceries})
          db-id (:db/id created)
          fetched (categories/get-by-id setup/*test-conn* db-id)]

      (is (some? fetched))
      (is (= db-id (:db/id fetched)))
      (is (= "Groceries" (:category/name fetched)))))

  (testing "returns nil for non-existent id"
    (let [fetched (categories/get-by-id setup/*test-conn* 999999)]
      (is (nil? fetched)))))

(deftest update-category-test
  (testing "updates category name"
    (let [created (categories/create! setup/*test-conn* {:category/name "Groceries"
                                                          :category/type :expense
                                                          :category/ident :category/groceries})
          db-id (:db/id created)
          updated (categories/update! setup/*test-conn* db-id {:category/name "Food & Groceries"})]

      (is (= db-id (:db/id updated)))
      (is (= "Food & Groceries" (:category/name updated)))

      ;; Verify persistence
      (let [db (d/db setup/*test-conn*)
            fetched (d/pull db '[*] db-id)]
        (is (= "Food & Groceries" (:category/name fetched))))))

  (testing "updates category type"
    (let [created (categories/create! setup/*test-conn* {:category/name "Misc"
                                                          :category/type :expense
                                                          :category/ident :category/misc})
          db-id (:db/id created)
          updated (categories/update! setup/*test-conn* db-id {:category/type :income})]

      (is (= :income (:category/type updated))))))

(deftest delete-category-test
  (testing "deletes category with no transactions"
    (let [created (categories/create! setup/*test-conn* {:category/name "Groceries"
                                                          :category/type :expense
                                                          :category/ident :category/groceries})
          db-id (:db/id created)]

      (categories/delete! setup/*test-conn* db-id)

      ;; Verify it's gone
      (let [fetched (categories/get-by-id setup/*test-conn* db-id)]
        (is (nil? fetched)))))

  (testing "in-use? is false for an unreferenced category"
    (let [cat (categories/create! setup/*test-conn* {:category/name "Unused"
                                                     :category/type :expense
                                                     :category/ident :category/unused})]
      (is (false? (categories/in-use? setup/*test-conn* (:db/id cat))))))

  (testing "in-use? is true when a transaction references the category"
    (let [cat (categories/create! setup/*test-conn* {:category/name "Groceries"
                                                     :category/type :expense
                                                     :category/ident :category/groceries})]
      (d/transact! setup/*test-conn* [{:transaction/external-id "tx-cat-1"
                                       :transaction/amount -10.00M
                                       :transaction/category (:db/id cat)}])
      (is (true? (categories/in-use? setup/*test-conn* (:db/id cat))))))

  (testing "in-use? is true when only a split part references the category (a part is a
            normal :transaction/* row, so :transaction/category covers it)"
    (let [cat (categories/create! setup/*test-conn* {:category/name "SplitOnly"
                                                     :category/type :expense
                                                     :category/ident :category/split-only})]
      (d/transact! setup/*test-conn* [{:transaction/external-id "tx-cat-2"
                                       :transaction/amount -10.00M}])
      (d/transact! setup/*test-conn* [{:transaction/external-id "split-cat-2a"
                                       :transaction/split-parent [:transaction/external-id "tx-cat-2"]
                                       :transaction/split-order 0
                                       :transaction/amount -10.00M
                                       :transaction/category (:db/id cat)}])
      (is (true? (categories/in-use? setup/*test-conn* (:db/id cat)))))))

(deftest get-category-by-ident-test
  (testing "retrieves category by ident"
    (categories/create! setup/*test-conn* {:category/name "Groceries"
                                            :category/type :expense
                                            :category/ident :category/groceries})

    (let [fetched (categories/get-by-ident setup/*test-conn* :category/groceries)]
      (is (some? fetched))
      (is (= "Groceries" (:category/name fetched)))
      (is (= :category/groceries (:category/ident fetched)))))

  (testing "returns nil for non-existent ident"
    (let [fetched (categories/get-by-ident setup/*test-conn* :category/nonexistent)]
      (is (nil? fetched)))))

(deftest category-sort-order-test
  (testing "creates category with sort-order"
    (let [category-data {:category/name "Groceries"
                         :category/type :expense
                         :category/ident :category/groceries
                         :category/sort-order 1}
          result (categories/create! setup/*test-conn* category-data)]
      (is (= 1 (:category/sort-order result)))))

  (testing "updates category sort-order"
    (let [created (categories/create! setup/*test-conn* {:category/name "Groceries"
                                                          :category/type :expense
                                                          :category/ident :category/groceries
                                                          :category/sort-order 1})
          db-id (:db/id created)
          updated (categories/update! setup/*test-conn* db-id {:category/sort-order 5})]
      (is (= 5 (:category/sort-order updated)))))

  (testing "list-all returns categories sorted by sort-order"
    ;; Create categories with different sort orders
    (categories/create! setup/*test-conn* {:category/name "Dining"
                                            :category/type :expense
                                            :category/ident :category/dining
                                            :category/sort-order 2})
    (categories/create! setup/*test-conn* {:category/name "Groceries"
                                            :category/type :expense
                                            :category/ident :category/groceries
                                            :category/sort-order 1})
    (categories/create! setup/*test-conn* {:category/name "Transport"
                                            :category/type :expense
                                            :category/ident :category/transport
                                            :category/sort-order 3})

    (let [categories (categories/list-all setup/*test-conn*)]
      (is (= 3 (count categories)))
      ;; Verify they're sorted by sort-order
      (is (= ["Groceries" "Dining" "Transport"]
             (map :category/name categories)))))

  (testing "categories without sort-order appear last"
    ;; Create a category with sort-order
    (categories/create! setup/*test-conn* {:category/name "Entertainment"
                                            :category/type :expense
                                            :category/ident :category/entertainment
                                            :category/sort-order 1})
    ;; Create a category without sort-order
    (categories/create! setup/*test-conn* {:category/name "Misc"
                                            :category/type :expense
                                            :category/ident :category/misc})

    (let [categories (categories/list-all setup/*test-conn*)
          ;; Filter to just the ones we created in this test
          test-cats (filter #(#{"Entertainment" "Misc"} (:category/name %)) categories)]
      (is (= 2 (count test-cats)))
      ;; Category with sort-order should come first
      (is (= "Entertainment" (:category/name (first test-cats))))
      (is (= "Misc" (:category/name (second test-cats)))))))

(deftest create-category-with-parent-test
  (testing "creates a category referencing an existing parent"
    (let [parent (categories/create! setup/*test-conn* {:category/name "Food"
                                                         :category/type :expense
                                                         :category/ident :category/food})
          parent-id (:db/id parent)
          child (categories/create! setup/*test-conn* {:category/name "Groceries"
                                                       :category/type :expense
                                                       :category/ident :category/groceries
                                                       :category/parent parent-id})]
      (is (= parent-id (get-in child [:category/parent :db/id]))
          "child should reference the parent's db/id"))))

(deftest update-category-parent-test
  (testing "sets a parent via update"
    (let [parent (categories/create! setup/*test-conn* {:category/name "Food"
                                                         :category/type :expense
                                                         :category/ident :category/food})
          child (categories/create! setup/*test-conn* {:category/name "Groceries"
                                                       :category/type :expense
                                                       :category/ident :category/groceries})
          updated (categories/update! setup/*test-conn* (:db/id child)
                                      {:category/parent (:db/id parent)})]
      (is (= (:db/id parent) (get-in updated [:category/parent :db/id])))))

  (testing "clears a parent when set to nil"
    (let [parent (categories/create! setup/*test-conn* {:category/name "Food"
                                                         :category/type :expense
                                                         :category/ident :category/food})
          child (categories/create! setup/*test-conn* {:category/name "Groceries"
                                                       :category/type :expense
                                                       :category/ident :category/groceries
                                                       :category/parent (:db/id parent)})]
      (is (some? (:category/parent (categories/get-by-id setup/*test-conn* (:db/id child))))
          "precondition: child has a parent")
      (let [updated (categories/update! setup/*test-conn* (:db/id child)
                                        {:category/parent nil})]
        (is (nil? (:category/parent updated)) "parent should be cleared")
        (is (nil? (:category/parent (categories/get-by-id setup/*test-conn* (:db/id child))))
            "clear should be persisted")))))

(deftest create-many-test
  (testing "creates multiple categories in a single transaction, in input order"
    (let [result (categories/create-many! setup/*test-conn*
                                           [{:category/name "Food"
                                             :category/type :expense
                                             :category/ident :category/food
                                             :tempid "p"}
                                            {:category/name "Groceries"
                                             :category/type :expense
                                             :category/ident :category/groceries
                                             :tempid "c1"
                                             :parent-tempid "p"}
                                            {:category/name "Dining"
                                             :category/type :expense
                                             :category/ident :category/dining
                                             :tempid "c2"
                                             :parent-tempid "p"}])]
      (is (= 3 (count result)))
      (is (= ["Food" "Groceries" "Dining"] (map :category/name result)))
      (let [[food groceries dining] result]
        (is (nil? (:category/parent food)) "top-level item has no parent")
        (is (= (:db/id food) (get-in groceries [:category/parent :db/id]))
            "child links to within-batch parent")
        (is (= (:db/id food) (get-in dining [:category/parent :db/id]))))
      (is (= 3 (count (categories/list-all setup/*test-conn*))) "all persisted")))

  (testing "creates a flat batch with no parents"
    (let [result (categories/create-many! setup/*test-conn*
                                           [{:category/name "Salary"
                                             :category/type :income
                                             :category/ident :category/salary
                                             :tempid "a"}
                                            {:category/name "Gas"
                                             :category/type :expense
                                             :category/ident :category/gas
                                             :tempid "b"}])]
      (is (= 2 (count result)))
      (is (every? #(nil? (:category/parent %)) result)))))

(deftest category-parent-validation-test
  (testing "create! rejects a non-existent parent"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Parent category does not exist"
                          (categories/create! setup/*test-conn*
                                              {:category/name "Groceries" :category/type :expense
                                               :category/ident :category/groceries
                                               :category/parent 999999}))))

  (testing "create! rejects a parent that is itself a child"
    (let [food (categories/create! setup/*test-conn*
                                   {:category/name "Food" :category/type :expense
                                    :category/ident :category/food})
          groceries (categories/create! setup/*test-conn*
                                        {:category/name "Groceries" :category/type :expense
                                         :category/ident :category/groceries
                                         :category/parent (:db/id food)})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Parent must be a top-level category"
                            (categories/create! setup/*test-conn*
                                                {:category/name "Produce" :category/type :expense
                                                 :category/ident :category/produce
                                                 :category/parent (:db/id groceries)})))))

  (testing "update! rejects demoting a category that has children"
    (let [food (categories/create! setup/*test-conn*
                                   {:category/name "Food" :category/type :expense
                                    :category/ident :category/food})
          other (categories/create! setup/*test-conn*
                                    {:category/name "Transport" :category/type :expense
                                     :category/ident :category/transport})]
      (categories/create! setup/*test-conn*
                          {:category/name "Groceries" :category/type :expense
                           :category/ident :category/groceries
                           :category/parent (:db/id food)})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sub-categories cannot become a child"
                            (categories/update! setup/*test-conn* (:db/id food)
                                                {:category/parent (:db/id other)})))))

  (testing "create-many! rejects nesting deeper than one level"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nests more than one level deep"
                          (categories/create-many! setup/*test-conn*
                                                   [{:category/name "Food" :category/type :expense
                                                     :category/ident :category/food :tempid "p"}
                                                    {:category/name "Groceries" :category/type :expense
                                                     :category/ident :category/groceries
                                                     :tempid "c" :parent-tempid "p"}
                                                    {:category/name "Produce" :category/type :expense
                                                     :category/ident :category/produce
                                                     :tempid "g" :parent-tempid "c"}])))))

(deftest batch-update-sort-orders-test
  (testing "batch updates multiple category sort orders"
    (let [cat1 (categories/create! setup/*test-conn* {:category/name "Groceries"
                                                       :category/type :expense
                                                       :category/ident :category/groceries
                                                       :category/sort-order 1})
          cat2 (categories/create! setup/*test-conn* {:category/name "Dining"
                                                       :category/type :expense
                                                       :category/ident :category/dining
                                                       :category/sort-order 2})
          cat3 (categories/create! setup/*test-conn* {:category/name "Transport"
                                                       :category/type :expense
                                                       :category/ident :category/transport
                                                       :category/sort-order 3})
          ;; Reverse the order
          updates [{:db/id (:db/id cat1) :category/sort-order 3}
                   {:db/id (:db/id cat2) :category/sort-order 2}
                   {:db/id (:db/id cat3) :category/sort-order 1}]
          result (categories/batch-update-sort-orders! setup/*test-conn* updates)]

      (is (= 3 (count result)))

      ;; Verify the updated sort orders
      (let [categories (categories/list-all setup/*test-conn*)]
        (is (= ["Transport" "Dining" "Groceries"]
               (map :category/name categories)))))))

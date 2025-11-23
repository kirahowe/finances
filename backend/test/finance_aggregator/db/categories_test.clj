(ns finance-aggregator.db.categories-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [finance-aggregator.db.categories :as categories]
            [finance-aggregator.data.schema :as schema]
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

  (testing "prevents deletion of category with assigned transactions"
    ;; This test will need to be implemented after we have transaction support
    ;; For now, we'll just document the requirement
    (is true "TODO: Test prevention of deleting categories with transactions")))

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

(ns finance-aggregator.categories-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.categories :as cat]))

(deftest validate-parent-test
  (testing "valid: existing top-level parent, on create"
    (is (nil? (cat/validate-parent 10 {:db/id 10 :category/name "Food"} nil false))))

  (testing "valid: existing top-level parent, on update with no children"
    (is (nil? (cat/validate-parent 10 {:db/id 10 :category/name "Food"} 20 false))))

  (testing "rejects a parent that does not exist"
    (is (= "Parent category does not exist"
           (cat/validate-parent 999 nil nil false))))

  (testing "rejects a category being its own parent"
    (is (= "A category cannot be its own parent"
           (cat/validate-parent 20 {:db/id 20 :category/name "Food"} 20 false))))

  (testing "rejects a parent that is itself a child (single level)"
    (is (= "Parent must be a top-level category"
           (cat/validate-parent 10 {:db/id 10 :category/name "Groceries"
                                    :category/parent {:db/id 5}} nil false))))

  (testing "rejects demoting a category that already has children"
    (is (= "A category with sub-categories cannot become a child"
           (cat/validate-parent 10 {:db/id 10 :category/name "Food"} 20 true)))))

(deftest validate-assignable-test
  (testing "a category with no children is assignable"
    (is (nil? (cat/validate-assignable false))))

  (testing "a category with sub-categories is a header and rejected"
    (is (= "Cannot assign a category that has sub-categories"
           (cat/validate-assignable true)))))

(deftest validate-batch-test
  (testing "valid: flat batch, no parents"
    (is (nil? (cat/validate-batch [{:tempid "a" :category/name "Salary"}
                                   {:tempid "b" :category/name "Gas"}]))))

  (testing "valid: one level of nesting"
    (is (nil? (cat/validate-batch [{:tempid "p" :category/name "Food"}
                                   {:tempid "c" :category/name "Groceries" :parent-tempid "p"}]))))

  (testing "rejects a parent-tempid that isn't in the batch"
    (is (= "Unknown parent reference for \"Groceries\""
           (cat/validate-batch [{:tempid "c" :category/name "Groceries" :parent-tempid "missing"}]))))

  (testing "rejects nesting more than one level deep"
    (is (= "\"Produce\" nests more than one level deep"
           (cat/validate-batch [{:tempid "p" :category/name "Food"}
                                {:tempid "c" :category/name "Groceries" :parent-tempid "p"}
                                {:tempid "g" :category/name "Produce" :parent-tempid "c"}]))))

  (testing "rejects a self-referential parent"
    (is (= "\"Loop\" nests more than one level deep"
           (cat/validate-batch [{:tempid "x" :category/name "Loop" :parent-tempid "x"}])))))

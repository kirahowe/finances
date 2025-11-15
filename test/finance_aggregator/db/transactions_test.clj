(ns finance-aggregator.db.transactions-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [finance-aggregator.db.transactions :as transactions]
            [finance-aggregator.db.categories :as categories]
            [finance-aggregator.data.schema :as schema]
            [finance-aggregator.test-utils.setup :as setup]))

(use-fixtures :each setup/with-empty-db)

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

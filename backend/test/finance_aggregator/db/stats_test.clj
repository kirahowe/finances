(ns finance-aggregator.db.stats-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [finance-aggregator.db.stats :as stats]
            [finance-aggregator.db.transactions :as db-transactions]
            [finance-aggregator.test-utils.setup :as setup]))

(use-fixtures :each setup/with-empty-db)

(defn- make-tx!
  "Create an institution/account/transaction; returns the transaction's :db/id."
  [external-id amount]
  (d/transact! setup/*test-conn* [{:institution/id "inst-1" :institution/name "Test Bank"}])
  (d/transact! setup/*test-conn* [{:account/external-id "acct-1"
                                   :account/external-name "Test Account"
                                   :account/institution [:institution/id "inst-1"]}])
  (d/transact! setup/*test-conn* [{:transaction/external-id external-id
                                   :transaction/account [:account/external-id "acct-1"]
                                   :transaction/amount amount
                                   :transaction/payee "Costco"
                                   :transaction/posted-date (java.util.Date.)}])
  (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:transaction/external-id external-id])))

(deftest entity-counts-test
  (testing "counts institutions, accounts, and transactions"
    (make-tx! "tx-1" -100.00M)
    (make-tx! "tx-2" -50.00M)
    (is (= {:institutions 1 :accounts 1 :transactions 2}
           (stats/entity-counts setup/*test-conn*)))))

(deftest entity-counts-stable-across-a-split-test
  (testing "splitting a transaction doesn't change how many transactions you have:
            part rows (provider :split, :transaction/split-parent) are excluded, so
            the count stays imported + manual rows"
    (let [tx-id (make-tx! "tx-split-1" -100.00M)]
      (make-tx! "tx-plain-1" -50.00M)
      (is (= 2 (:transactions (stats/entity-counts setup/*test-conn*))))
      (db-transactions/set-splits! setup/*test-conn* tx-id
                                   [{:amount "-60.00"} {:amount "-40.00"}])
      (is (= 2 (:transactions (stats/entity-counts setup/*test-conn*)))
          "the split parent still counts once; its parts add nothing")
      (db-transactions/set-splits! setup/*test-conn* tx-id [])
      (is (= 2 (:transactions (stats/entity-counts setup/*test-conn*)))
          "un-splitting changes nothing either"))))

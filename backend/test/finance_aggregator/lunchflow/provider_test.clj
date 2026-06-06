(ns finance-aggregator.lunchflow.provider-test
  "End-to-end Lunchflow sync through the generic orchestrator, with the HTTP
   client stubbed. Verifies accounts/transactions persist tagged :lunchflow,
   pending txns are filtered, the stateless `from` window is derived from the
   latest stored date, and re-sync dedups via the unique-identity constraint."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datalevin.core :as d]
   [finance-aggregator.lunchflow.client :as client]
   [finance-aggregator.lunchflow.provider]
   [finance-aggregator.provider.sync :as sync]
   [finance-aggregator.test-utils.setup :as setup])
  (:import
   [java.util Date]))

(use-fixtures :each setup/with-empty-db)

(def ^:private sample-account
  {:id 1 :name "Everyday Chequing" :institution_name "Tangerine Bank"
   :provider "gocardless" :currency "CAD" :status "ACTIVE"})

(def ^:private txn-recent
  {:id 99 :accountId 1 :amount -12.34 :date "2026-06-05"
   :merchant "Loblaws" :description "groceries" :isPending false})

(def ^:private txn-pending
  {:id 50 :accountId 1 :amount -5 :date "2026-06-04"
   :merchant "Tim Hortons" :isPending true})

(def ^:private txn-older
  {:id 77 :accountId 1 :amount 1000.00 :date "2026-06-01"
   :merchant "Payroll" :isPending false})

(defn- seed-user! [conn]
  (d/transact! conn [{:user/id "test-user" :user/created-at (Date.)}]))

(defn- tx-ids [conn]
  (set (d/q '[:find [?ext ...] :where [?e :transaction/external-id ?ext]]
            (d/db conn))))

(deftest lunchflow-sync-end-to-end
  (let [conn setup/*test-conn*
        deps {:db-conn conn :secrets {:lunchflow "fake-key"}}
        captured (atom [])]
    (seed-user! conn)
    (with-redefs [client/list-accounts (fn [_] [sample-account])
                  client/fetch-account-transactions
                  (fn [_ _account-id opts]
                    (swap! captured conj opts)
                    [txn-recent txn-pending txn-older])]

      (testing "first sync persists accounts/institution and non-pending txns"
        (is (= :synced (sync/sync-provider! deps :lunchflow)))
        (is (= :lunchflow
               (:account/provider (d/pull (d/db conn) '[:account/provider]
                                          [:account/external-id "lunchflow-1"]))))
        (is (= "Tangerine Bank"
               (:institution/name (d/pull (d/db conn) '[:institution/name]
                                          [:institution/id "lunchflow-tangerine-bank"]))))
        (is (= #{"lunchflow-99" "lunchflow-77"} (tx-ids conn))
            "pending transaction is filtered out"))

      (testing "first sync requests a full backfill (no derived `from`)"
        (is (nil? (:from (last @captured)))))

      (testing "re-sync derives `from` from the latest stored date and dedups"
        (reset! captured [])
        (is (= :synced (sync/sync-provider! deps :lunchflow)))
        (is (= "2026-06-05" (:from (last @captured))))
        (is (= 2 (count (tx-ids conn))) "no duplicate transactions on re-sync")))))

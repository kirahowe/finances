(ns finance-aggregator.lunchflow.provider-test
  "End-to-end Lunchflow sync through the generic orchestrator, with the HTTP
   client stubbed. Verifies selection gating (only connected ∪ selected accounts
   are imported and refreshed), accounts/transactions persist tagged :lunchflow,
   pending txns are filtered, the stateless `from` window is derived from the
   latest stored date, and re-sync dedups via the unique-identity constraint."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datalevin.core :as d]
   [finance-aggregator.lunchflow.client :as client]
   [finance-aggregator.lunchflow.provider]
   [finance-aggregator.provider :as provider]
   [finance-aggregator.provider.sync :as sync]
   [finance-aggregator.test-utils.setup :as setup])
  (:import
   [java.util Date]))

(use-fixtures :each setup/with-empty-db)

(def ^:private sample-account
  {:id 1 :name "Everyday Chequing" :institution_name "Tangerine Bank"
   :institution_logo "https://cdn.example/tangerine.png"
   :provider "quiltt" :status "ACTIVE"})

;; A second account at a different institution, never selected for import. Used
;; to prove the selection gate keeps unselected accounts out of the DB.
(def ^:private other-account
  {:id 2 :name "Savings" :institution_name "EQ Bank"
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

(defn- account-ids [conn]
  (set (d/q '[:find [?ext ...] :where [?e :account/external-id ?ext]]
            (d/db conn))))

(deftest lunchflow-sync-end-to-end
  (let [conn setup/*test-conn*
        deps {:db-conn conn :secrets {:lunchflow "fake-key"}}
        ;; Each call records the account-id it fetched for, plus the opts.
        captured (atom [])]
    (seed-user! conn)
    (with-redefs [client/list-accounts (fn [_] [sample-account other-account])
                  client/fetch-account-transactions
                  (fn [_ account-id opts]
                    (swap! captured conj {:account-id account-id :opts opts})
                    (if (= account-id 1) [txn-recent txn-pending txn-older] []))]

      (testing "connect sync imports only the selected account + its institution"
        (is (= :synced
               (sync/sync-provider! (assoc deps :selected-account-ids #{"lunchflow-1"})
                                    :lunchflow)))
        (is (= :lunchflow
               (:account/provider (d/pull (d/db conn) '[:account/provider]
                                          [:account/external-id "lunchflow-1"]))))
        (is (= "Tangerine Bank"
               (:institution/name (d/pull (d/db conn) '[:institution/name]
                                          [:institution/id "lunchflow-tangerine-bank"]))))
        (is (= "https://cdn.example/tangerine.png"
               (:institution/logo (d/pull (d/db conn) '[:institution/logo]
                                          [:institution/id "lunchflow-tangerine-bank"])))
            "the institution logo is persisted on import")
        (is (= #{"lunchflow-1"} (account-ids conn))
            "the unselected account is not imported")
        (is (= #{"lunchflow-99" "lunchflow-77"} (tx-ids conn))
            "pending transaction is filtered out"))

      (testing "transactions are fetched only for the connected account"
        (is (= #{1} (set (map :account-id @captured)))))

      (testing "first sync requests a full backfill (no derived `from`)"
        (is (nil? (:from (:opts (last @captured))))))

      (testing "re-sync (no selection) refreshes connected only, derives `from`, dedups"
        (reset! captured [])
        (is (= :synced (sync/sync-provider! deps :lunchflow)))
        (is (= "2026-06-05" (:from (:opts (last @captured)))))
        (is (= #{1} (set (map :account-id @captured)))
            "still only the connected account is fetched")
        (is (= #{"lunchflow-1"} (account-ids conn))
            "the unselected account stays out of the DB")
        (is (= 2 (count (tx-ids conn))) "no duplicate transactions on re-sync")))))

(deftest lunchflow-per-account-from-is-independent-not-clamped-by-a-fresher-sibling
  (let [conn setup/*test-conn*
        deps {:db-conn conn :secrets {:lunchflow "fake-key"}}
        captured (atom [])
        txn (fn [id account-id amount date merchant]
              {:id id :accountId account-id :amount amount :date date :merchant merchant :isPending false})]
    (seed-user! conn)
    (with-redefs [client/list-accounts (fn [_] [sample-account other-account])
                  client/fetch-account-transactions
                  (fn [_ account-id opts]
                    (swap! captured conj {:account-id account-id :opts opts})
                    (case account-id
                      1 [(txn 201 1 -10.00 "2026-06-05" "Fresh")]
                      2 [(txn 202 2 -20.00 "2026-05-01" "Stale")]
                      []))]
      (testing "connect both accounts (first sync, full backfill for each)"
        (is (= :synced (sync/sync-provider!
                        (assoc deps :selected-account-ids #{"lunchflow-1" "lunchflow-2"}) :lunchflow)))
        (is (= #{"lunchflow-1" "lunchflow-2"} (account-ids conn))))
      (testing "a plain re-sync computes `from` PER ACCOUNT — account 2's stale date is not
                clamped forward by account 1's fresher one (the old global-max bug)"
        (reset! captured [])
        (is (= :synced (sync/sync-provider! deps :lunchflow)))
        (let [by-account (into {} (map (juxt :account-id :opts)) @captured)]
          (is (= "2026-06-05" (:from (get by-account 1))) "account 1's own latest date")
          (is (= "2026-05-01" (:from (get by-account 2)))
              "account 2's own (older) latest date — NOT account 1's 2026-06-05"))))))

(deftest lunchflow-only-account-ids-scopes-fetch-accounts-and-never-adds
  (let [conn setup/*test-conn*
        deps {:db-conn conn :secrets {:lunchflow "fake-key"}}]
    (seed-user! conn)
    (with-redefs [client/list-accounts (fn [_] [sample-account other-account])
                  client/fetch-account-transactions (fn [_ _ _] [])]
      (is (= :synced (sync/sync-provider! (assoc deps :selected-account-ids #{"lunchflow-1"}) :lunchflow))
          "only lunchflow-1 is connected")
      (testing "naming an unconnected account yields nothing — a per-account sync can't add one"
        (is (= #{} (:accounts (provider/fetch-accounts :lunchflow (assoc deps :only-account-ids #{"lunchflow-2"}))))))
      (testing "naming the connected account scopes fetch-accounts to just it"
        (let [result (provider/fetch-accounts :lunchflow (assoc deps :only-account-ids #{"lunchflow-1"}))]
          (is (= #{"lunchflow-1"} (set (map :account/external-id (:accounts result)))))))
      (testing "only-account-ids wins even when selected-account-ids also names an unconnected account"
        (is (= #{} (:accounts (provider/fetch-accounts
                               :lunchflow (assoc deps :selected-account-ids #{"lunchflow-2"}
                                                 :only-account-ids #{"lunchflow-2"})))))))))

(deftest lunchflow-only-account-ids-scopes-fetch-transactions
  (let [conn setup/*test-conn*
        deps {:db-conn conn :secrets {:lunchflow "fake-key"}}
        captured (atom [])]
    (seed-user! conn)
    (with-redefs [client/list-accounts (fn [_] [sample-account other-account])
                  client/fetch-account-transactions
                  (fn [_ account-id _opts] (swap! captured conj account-id) [])]
      (is (= :synced (sync/sync-provider!
                      (assoc deps :selected-account-ids #{"lunchflow-1" "lunchflow-2"}) :lunchflow)))
      (reset! captured [])
      (provider/fetch-transactions :lunchflow (assoc deps :only-account-ids #{"lunchflow-2"}))
      (is (= [2] @captured)
          "only the scoped account's transactions are pulled, not every connected one"))))

(deftest lunchflow-classify-sync-error-surfaces-as-fail
  (testing "Lunchflow has no error-code vocabulary -> :fail with the cause message"
    (is (= {:action :fail :error-code nil :error-message "boom"}
           (provider/classify-sync-error :lunchflow {} (ex-info "boom" {}))))))

(deftest lunchflow-available-accounts-lists-everything
  (with-redefs [client/list-accounts (fn [_] [sample-account other-account])]
    (let [result (provider/available-accounts :lunchflow {:secrets {:lunchflow "fake-key"}})]
      (is (= 2 (count result)) "lists all accounts regardless of what's connected")
      (is (= #{"lunchflow-1" "lunchflow-2"} (set (map :external-id result))))
      (is (= #{"Tangerine Bank" "EQ Bank"} (set (map :institution-name result))))
      (testing "each entry carries display fields for the selection UI"
        (let [a1 (first (filter #(= "lunchflow-1" (:external-id %)) result))]
          (is (= "Everyday Chequing" (:name a1)))
          (is (= "lunchflow-tangerine-bank" (:institution-id a1)))
          (is (= "https://cdn.example/tangerine.png" (:institution-logo a1))))))))

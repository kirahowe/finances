(ns finance-aggregator.provider.sync-test
  "Tests the generic sync orchestrator against a fake in-memory `:test`
   provider (no network). Locks down account-before-transaction ordering,
   cross-page removed-transaction retraction, :more?/:next-opts looping, and
   WebSocket status transitions."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datalevin.core :as d]
   [finance-aggregator.provider :as provider]
   [finance-aggregator.provider.sync :as sync]
   [finance-aggregator.test-utils.setup :as setup]
   [finance-aggregator.ws.state :as ws])
  (:import
   [java.util Date]))

(use-fixtures :each setup/with-empty-db)

;;; Fake `:test` provider --------------------------------------------------

(defn- canonical-txn [ext-id]
  {:transaction/external-id ext-id
   :transaction/account [:account/external-id "test-acc-1"]
   :transaction/date (Date.)
   :transaction/posted-date (Date.)
   :transaction/amount (bigdec "1.00")
   :transaction/payee "Test Payee"
   :transaction/provider :test
   :transaction/user [:user/id "test-user"]})

(defmethod provider/fetch-accounts :test
  [_ _opts]
  {:institutions #{{:institution/id "test-inst" :institution/name "Test Inst"}}
   :accounts #{{:account/external-id "test-acc-1"
                :account/external-name "Test Account"
                :account/currency "USD"
                :account/provider :test
                :account/institution [:institution/id "test-inst"]
                :account/user [:user/id "test-user"]}}})

;; Two pages: page 0 inserts t1+t2; page 1 inserts t3 and removes t1.
(defmethod provider/fetch-transactions :test
  [_ {:keys [page] :or {page 0} :as opts}]
  (case (long page)
    0 {:transactions [(canonical-txn "t1") (canonical-txn "t2")]
       :removed []
       :more? true
       :next-opts (assoc opts :page 1)}
    1 {:transactions [(canonical-txn "t3")]
       :removed ["t1"]
       :more? false}))

;;; Fake `:test-rich` provider: exercises the provider-driven terminal status,
;;; status-opts ws payload, and the post-persist :on-complete hook.

(def ^:private on-complete-calls (atom []))

(defmethod provider/fetch-accounts :test-rich
  [_ _opts]
  {:institutions #{{:institution/id "rich-inst" :institution/name "Rich Inst"}}
   :accounts #{{:account/external-id "rich-acc-1"
                :account/external-name "Rich Account"
                :account/currency "USD"
                :account/provider :test-rich
                :account/institution [:institution/id "rich-inst"]
                :account/user [:user/id "test-user"]}}})

(defmethod provider/fetch-transactions :test-rich
  [_ {:keys [db-conn]}]
  {:transactions [(assoc (canonical-txn "rt-1")
                         :transaction/account [:account/external-id "rich-acc-1"])]
   :removed []
   :more? false
   :status :syncing-historical
   :status-opts {:institution-name "Rich Inst"
                 :transaction-count 1
                 :progress {:added 1 :modified 0 :removed 0}}
   ;; Records what the db looks like at on-complete time, to prove it runs
   ;; AFTER transactions are persisted.
   :on-complete (fn []
                  (swap! on-complete-calls conj
                         (set (d/q '[:find [?e ...]
                                     :where [?e :transaction/external-id "rt-1"]]
                                   (d/db db-conn)))))})

;;; Helpers ----------------------------------------------------------------

(defn- seed-user! [conn]
  (d/transact! conn [{:user/id "test-user" :user/created-at (Date.)}]))

(defn- tx-external-ids [conn]
  (set (d/q '[:find [?ext ...]
              :where [?e :transaction/external-id ?ext]]
            (d/db conn))))

;;; Tests ------------------------------------------------------------------

(deftest sync-provider-persists-accounts-and-pages-transactions
  (let [conn setup/*test-conn*]
    (seed-user! conn)
    (testing "orchestrator returns :synced"
      (is (= :synced (sync/sync-provider! {:db-conn conn} :test))))

    (testing "institution and account are persisted (accounts before txns so refs resolve)"
      (is (= "Test Inst"
             (:institution/name (d/pull (d/db conn) '[:institution/name]
                                        [:institution/id "test-inst"]))))
      (is (= :test
             (:account/provider (d/pull (d/db conn) '[:account/provider]
                                        [:account/external-id "test-acc-1"])))))

    (testing "paged transactions land, and cross-page removed id is retracted"
      ;; t1 inserted on page 0 then removed on page 1; t2 and t3 remain.
      (is (= #{"t2" "t3"} (tx-external-ids conn))))

    (testing "ws status advanced to :synced"
      (is (= :synced (:status (ws/get-sync-status "test")))))))

(deftest sync-provider-marks-failed-and-rethrows-on-error
  (let [conn setup/*test-conn*]
    (seed-user! conn)
    ;; No :boom methods registered -> dispatch throws IllegalArgumentException.
    (is (thrown? IllegalArgumentException
                 (sync/sync-provider! {:db-conn conn} :boom)))
    (is (= :failed (:status (ws/get-sync-status "boom"))))))

(deftest sync-provider-honors-status-key-terminal-status-and-on-complete
  (let [conn setup/*test-conn*]
    (reset! on-complete-calls [])
    (seed-user! conn)
    (testing "returns the provider's terminal status, not a hardcoded :synced"
      (is (= :syncing-historical
             (sync/sync-provider! {:db-conn conn :status-key "custom-key"} :test-rich))))

    (testing ":on-complete runs after transactions are persisted"
      (is (= 1 (count @on-complete-calls)) "on-complete invoked exactly once")
      (is (seq (first @on-complete-calls))
          "rt-1 already in db when on-complete ran (post-persist hook)"))

    (testing "ws status pushed under the custom status-key with status-opts payload"
      (let [st (ws/get-sync-status "custom-key")]
        (is (= :syncing-historical (:status st)))
        (is (= "Rich Inst" (:institution-name st)))
        (is (= 1 (:transaction-count st)))
        (is (= {:added 1 :modified 0 :removed 0} (:progress st)))))

    (testing "default provider-key naming still applies when no status-key given"
      (is (nil? (ws/get-sync-status "test-rich"))
          "nothing was pushed under the bare provider name"))))

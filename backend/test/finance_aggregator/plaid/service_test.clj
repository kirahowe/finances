(ns finance-aggregator.plaid.service-test
  "Behavioral tests for Plaid service orchestration.

   These tests exercise the real sync logic (cursor handling, pagination,
   status determination, removed-transaction retraction, historical-poll
   triggering, and error handling) against a real temporary Datalevin
   database, with only the side-effecting Plaid API client functions stubbed.

   Goal: lock down current behavior so the (intentionally janky) service
   namespace can be refactored without regressions."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datalevin.core :as d]
   [finance-aggregator.db.credentials :as creds]
   [finance-aggregator.plaid.client :as client]
   [finance-aggregator.plaid.service :as service]
   [finance-aggregator.test-utils.setup :as setup]
   [finance-aggregator.ws.state :as ws-state])
  (:import
   [java.util Date]))

;;; Fixtures

(defn clear-ws-state
  "Clear the global in-memory ws sync-state between tests so the
   get-item-sync-status fast path doesn't leak across tests."
  [f]
  (doseq [[item-id _] (ws-state/get-all-sync-states)]
    (ws-state/clear-sync-status! item-id))
  (f))

(use-fixtures :each setup/with-empty-db clear-ws-state)

;;; Helpers

(def ^:private deps
  "Standard deps map; :db-conn is filled in per-test from the fixture."
  {:plaid-config {:days-requested 730}})

(defn- with-conn [m]
  (assoc m :db-conn setup/*test-conn*))

(defn- seed-user! [conn]
  (d/transact! conn [{:user/id "test-user" :user/created-at (Date.)}]))

(defn- seed-account!
  "Seed an institution + account so transactions can resolve their
   :transaction/account lookup ref on insert."
  ([conn] (seed-account! conn "acc-1" "item_1"))
  ([conn account-id item-id]
   (d/transact! conn [{:institution/id "ins_test" :institution/name "Test Bank"}])
   (d/transact! conn [{:account/external-id account-id
                       :account/external-name "Checking"
                       :account/institution [:institution/id "ins_test"]
                       :account/user [:user/id "test-user"]
                       :account/item-id item-id}])))

(defn- seed-credential!
  "Create a Plaid Item credential row directly (bypassing encryption) so the
   cursor/status DB functions used by the service have something to update."
  [conn item-id & {:keys [cursor status]}]
  (d/transact! conn [(cond-> {:credential/id (str "plaid-item-" item-id)
                              :credential/user [:user/id "test-user"]
                              :credential/institution :plaid
                              :credential/item-id item-id
                              :credential/institution-name "Test Bank"
                              :credential/encrypted-data "dummy"
                              :credential/created-at (Date.)
                              :credential/sync-status (or status :pending)}
                       cursor (assoc :credential/sync-cursor cursor))]))

(defn- plaid-txn
  [id & {:keys [pending amount date name merchant account]
         :or {pending false amount 10.0 date "2024-01-15"
              name "TX" merchant nil account "acc-1"}}]
  {:transaction_id id :account_id account :amount amount :date date
   :name name :merchant_name merchant :pending pending})

(defn- sync-response
  [& {:keys [added modified removed next-cursor has-more status]
      :or {added [] modified [] removed []
           next-cursor "cursor-1" has-more false
           status :historical-update-complete}}]
  {:added added :modified modified :removed removed
   :next_cursor next-cursor :has_more has-more
   :transactions_update_status status})

(defn- queued-responder
  "Return a stub for client/sync-transactions that pops successive responses
   from `responses`, one per call (used to simulate pagination)."
  [responses]
  (let [q (atom responses)]
    (fn [& _]
      (let [r (first @q)]
        (swap! q rest)
        r))))

(defn- tx-ids-in-db [conn]
  (set (d/q '[:find [?id ...] :where [_ :transaction/external-id ?id]]
            (d/db conn))))

(defn- no-op-poll
  "Redef target for the private historical-poll fn so tests never spawn the
   30s background future."
  [& _] nil)

;;; ---------------------------------------------------------------------------
;;; Cursor-based sync: sync-item-transactions!
;;; ---------------------------------------------------------------------------

(deftest initial-sync-single-page-historical-complete
  (testing "Initial sync persists non-pending txns, stores cursor, marks :synced"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (seed-account! conn)
      (seed-credential! conn "item_1")
      (with-redefs [client/sync-transactions
                    (constantly
                     (sync-response
                      :added [(plaid-txn "tx-1")
                              (plaid-txn "tx-2" :pending true)]
                      :next-cursor "cursor-A"
                      :status :historical-update-complete))]
        (let [result (service/sync-item-transactions!
                      (with-conn deps)
                      {:item-id "item_1" :access-token "tok" :institution-name "Test Bank"})]
          (testing "return value"
            (is (= 2 (get-in result [:success :added])) "raw added count includes pending")
            (is (= 0 (get-in result [:success :modified])))
            (is (= 0 (get-in result [:success :removed])))
            (is (= 1 (get-in result [:success :transactions])) "persisted excludes pending")
            (is (= "cursor-A" (:cursor result)))
            (is (empty? (:errors result))))
          (testing "persistence: pending filtered out"
            (is (= #{"tx-1"} (tx-ids-in-db conn))))
          (testing "cursor + status persisted to DB"
            (is (= "cursor-A" (creds/get-sync-cursor conn "item_1")))
            (is (= :synced (:sync-status (creds/get-sync-status conn "item_1"))))))))))

(deftest initial-sync-paginates-until-has-more-false
  (testing "Pagination accumulates added across pages and stores final cursor"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (seed-account! conn)
      (seed-credential! conn "item_1")
      (with-redefs [client/sync-transactions
                    (queued-responder
                     [(sync-response :added [(plaid-txn "tx-1")]
                                     :next-cursor "page-1" :has-more true
                                     :status :historical-update-complete)
                      (sync-response :added [(plaid-txn "tx-2")]
                                     :next-cursor "page-2" :has-more false
                                     :status :historical-update-complete)])]
        (let [result (service/sync-item-transactions!
                      (with-conn deps)
                      {:item-id "item_1" :access-token "tok" :institution-name "Test Bank"})]
          (is (= 2 (get-in result [:success :added])))
          (is (= 2 (get-in result [:success :transactions])))
          (is (= "page-2" (:cursor result)) "final page cursor stored")
          (is (= #{"tx-1" "tx-2"} (tx-ids-in-db conn)))
          (is (= "page-2" (creds/get-sync-cursor conn "item_1"))))))))

(deftest initial-sync-initial-complete-marks-syncing-historical
  (testing ":initial-update-complete on initial sync yields :syncing-historical"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (seed-account! conn)
      (seed-credential! conn "item_1")
      (with-redefs [client/sync-transactions
                    (constantly
                     (sync-response :added [(plaid-txn "tx-1")]
                                    :next-cursor "cur-x"
                                    :status :initial-update-complete))
                    ;; Avoid spawning the real 30s background poll future.
                    service/poll-for-historical-transactions! no-op-poll]
        (service/sync-item-transactions!
         (with-conn deps)
         {:item-id "item_1" :access-token "tok" :institution-name "Test Bank"})
        (is (= :syncing-historical (:sync-status (creds/get-sync-status conn "item_1"))))
        (is (= "cur-x" (creds/get-sync-cursor conn "item_1")))))))

(deftest sync-no-data-not-ready-stays-pending-no-cursor
  (testing "Empty :not-ready response leaves status :pending and stores no cursor"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (seed-credential! conn "item_1")
      (with-redefs [client/sync-transactions
                    (constantly
                     (sync-response :added [] :modified [] :removed []
                                    :next-cursor "ignored"
                                    :status :not-ready))]
        (let [result (service/sync-item-transactions!
                      (with-conn deps)
                      {:item-id "item_1" :access-token "tok" :institution-name "Test Bank"})]
          (is (= 0 (get-in result [:success :transactions])))
          (is (= :pending (:sync-status (creds/get-sync-status conn "item_1"))))
          (is (nil? (creds/get-sync-cursor conn "item_1"))
              "cursor not stored when there is no data"))))))

(deftest incremental-sync-retracts-removed-transactions
  (testing "Incremental sync adds new txns and retracts removed ones"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (seed-account! conn)
      ;; Existing cursor => incremental sync (no historical poll path).
      (seed-credential! conn "item_1" :cursor "old-cursor" :status :synced)
      ;; Pre-existing transaction that Plaid will report as removed.
      (with-redefs [client/sync-transactions
                    (constantly (sync-response :added [(plaid-txn "tx-old")]
                                               :next-cursor "seed-cur"
                                               :status :historical-update-complete))]
        (service/sync-item-transactions!
         (with-conn (assoc-in deps [:plaid-config :days-requested] 730))
         {:item-id "item_1" :access-token "tok" :institution-name "Test Bank"}))
      (is (contains? (tx-ids-in-db conn) "tx-old") "precondition: tx-old persisted")
      (with-redefs [client/sync-transactions
                    (constantly
                     (sync-response :added [(plaid-txn "tx-new")]
                                    :modified []
                                    :removed [{:transaction_id "tx-old"}]
                                    :next-cursor "new-cursor"
                                    :status :historical-update-complete))]
        (let [result (service/sync-item-transactions!
                      (with-conn deps)
                      {:item-id "item_1" :access-token "tok" :institution-name "Test Bank"})]
          (is (= 1 (get-in result [:success :added])))
          (is (= 1 (get-in result [:success :removed])))
          (is (= #{"tx-new"} (tx-ids-in-db conn)) "tx-old retracted, tx-new present")
          (is (= "new-cursor" (creds/get-sync-cursor conn "item_1"))))))))

(deftest incremental-sync-modified-treated-as-upsert
  (testing "Modified transactions are parsed and persisted (upsert)"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (seed-account! conn)
      (seed-credential! conn "item_1" :cursor "old-cursor" :status :synced)
      (with-redefs [client/sync-transactions
                    (constantly
                     (sync-response :added []
                                    :modified [(plaid-txn "tx-1" :name "UPDATED NAME")]
                                    :next-cursor "c2"
                                    :status :historical-update-complete))]
        (let [result (service/sync-item-transactions!
                      (with-conn deps)
                      {:item-id "item_1" :access-token "tok" :institution-name "Test Bank"})]
          (is (= 1 (get-in result [:success :modified])))
          (is (= 1 (get-in result [:success :transactions])))
          (is (= "UPDATED NAME"
                 (d/q '[:find ?d . :where [?e :transaction/external-id "tx-1"]
                        [?e :transaction/description ?d]]
                      (d/db conn)))))))))

(deftest sync-error-marks-failed-and-returns-error
  (testing "Client exception is caught, status set :failed, error returned"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (seed-credential! conn "item_1" :status :syncing)
      (with-redefs [client/sync-transactions
                    (fn [& _] (throw (ex-info "plaid boom" {})))]
        (let [result (service/sync-item-transactions!
                      (with-conn deps)
                      {:item-id "item_1" :access-token "tok" :institution-name "Test Bank"})]
          (is (= 0 (get-in result [:success :transactions])))
          (is (= :sync-error (:type (first (:errors result)))))
          (is (= "plaid boom" (:message (first (:errors result)))))
          (is (= :failed (:sync-status (creds/get-sync-status conn "item_1")))))))))

(deftest initial-sync-without-days-requested-errors
  (testing "Initial sync without :days-requested throws -> caught -> error result"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (seed-credential! conn "item_1")
      ;; client should never be reached; if it is, fail loudly.
      (with-redefs [client/sync-transactions
                    (fn [& _] (throw (ex-info "should not call client" {})))]
        (let [result (service/sync-item-transactions!
                      (with-conn {:db-conn conn :plaid-config {}})
                      {:item-id "item_1" :access-token "tok" :institution-name "Test Bank"})]
          (is (= :sync-error (:type (first (:errors result)))))
          (is (re-find #"days-requested" (:message (first (:errors result)))))
          (is (= :failed (:sync-status (creds/get-sync-status conn "item_1")))))))))

;;; ---------------------------------------------------------------------------
;;; Historical poll triggering
;;; ---------------------------------------------------------------------------

(deftest historical-poll-triggered-on-initial-syncing-historical
  (testing "Background poll fires for initial sync that ends :syncing-historical"
    (let [conn setup/*test-conn*
          poll-calls (atom [])]
      (seed-user! conn)
      (seed-account! conn)
      (seed-credential! conn "item_1")
      (with-redefs [client/sync-transactions
                    (constantly
                     (sync-response :added [(plaid-txn "tx-1")]
                                    :next-cursor "cur"
                                    :status :initial-update-complete))
                    service/poll-for-historical-transactions!
                    (fn [_deps cred] (swap! poll-calls conj cred))]
        (service/sync-item-transactions!
         (with-conn deps)
         {:item-id "item_1" :access-token "tok" :institution-name "Test Bank"})
        (is (= 1 (count @poll-calls)) "poll triggered exactly once")
        (is (= "item_1" (:item-id (first @poll-calls))))
        (is (= "tok" (:access-token (first @poll-calls))))))))

(deftest historical-poll-not-triggered-on-incremental
  (testing "Incremental sync (cursor present) never triggers the poll"
    (let [conn setup/*test-conn*
          poll-calls (atom [])]
      (seed-user! conn)
      (seed-account! conn)
      (seed-credential! conn "item_1" :cursor "have-cursor" :status :synced)
      (with-redefs [client/sync-transactions
                    (constantly
                     (sync-response :added [(plaid-txn "tx-1")]
                                    :next-cursor "cur"
                                    :status :initial-update-complete))
                    service/poll-for-historical-transactions!
                    (fn [& args] (swap! poll-calls conj args))]
        (service/sync-item-transactions!
         (with-conn deps)
         {:item-id "item_1" :access-token "tok" :institution-name "Test Bank"})
        (is (empty? @poll-calls) "no poll on incremental sync")))))

(deftest historical-poll-not-triggered-when-complete
  (testing "Initial sync that completes historical does not trigger poll"
    (let [conn setup/*test-conn*
          poll-calls (atom [])]
      (seed-user! conn)
      (seed-account! conn)
      (seed-credential! conn "item_1")
      (with-redefs [client/sync-transactions
                    (constantly
                     (sync-response :added [(plaid-txn "tx-1")]
                                    :next-cursor "cur"
                                    :status :historical-update-complete))
                    service/poll-for-historical-transactions!
                    (fn [& args] (swap! poll-calls conj args))]
        (service/sync-item-transactions!
         (with-conn deps)
         {:item-id "item_1" :access-token "tok" :institution-name "Test Bank"})
        (is (empty? @poll-calls))
        (is (= :synced (:sync-status (creds/get-sync-status conn "item_1"))))))))

;;; ---------------------------------------------------------------------------
;;; Date-range based sync: /transactions/get
;;; ---------------------------------------------------------------------------

(deftest parse-month-to-date-range
  (testing "Parses YYYY-MM into [first-of-month, first-of-next-month]"
    (is (= {:start-date "2025-08-01" :end-date "2025-09-01"}
           (#'service/parse-month-to-date-range "2025-08")))
    (is (= {:start-date "2025-01-01" :end-date "2025-02-01"}
           (#'service/parse-month-to-date-range "2025-01")))
    (testing "December rolls over to next year"
      (is (= {:start-date "2024-12-01" :end-date "2025-01-01"}
             (#'service/parse-month-to-date-range "2024-12"))))))

(deftest sync-item-transactions-for-range-persists-and-filters
  (testing "Date-range sync persists non-pending txns and reports counts"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (seed-account! conn)
      (with-redefs [client/fetch-transactions
                    (constantly [(plaid-txn "tx-1")
                                 (plaid-txn "tx-2")
                                 (plaid-txn "tx-3" :pending true)])]
        (let [result (service/sync-item-transactions-for-range!
                      (with-conn deps)
                      {:item-id "item_1" :access-token "tok" :institution-name "Test Bank"}
                      "2024-01-01" "2024-02-01")]
          (is (= 2 (get-in result [:success :transactions])))
          (is (empty? (:errors result)))
          (is (= #{"tx-1" "tx-2"} (tx-ids-in-db conn))))))))

(deftest sync-item-transactions-for-range-error
  (testing "Date-range sync surfaces client errors without throwing"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (with-redefs [client/fetch-transactions
                    (fn [& _] (throw (ex-info "range boom" {})))]
        (let [result (service/sync-item-transactions-for-range!
                      (with-conn deps)
                      {:item-id "item_1" :access-token "tok" :institution-name "Test Bank"}
                      "2024-01-01" "2024-02-01")]
          (is (= 0 (get-in result [:success :transactions])))
          (is (= :sync-error (:type (first (:errors result))))))))))

(deftest sync-month-transactions-aggregates-across-items
  (testing "sync-month-transactions! fans out to all credentials, legacy format"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (seed-account! conn)
      (with-redefs [creds/get-all-plaid-credentials
                    (constantly [{:item-id "item_1" :access-token "tok"
                                  :institution-name "Test Bank"}])
                    client/fetch-transactions
                    (constantly [(plaid-txn "tx-1") (plaid-txn "tx-2")])]
        (let [result (service/sync-month-transactions!
                      (with-conn (assoc deps :secrets {})) "2024-01")]
          (is (= 2 (get-in result [:success :transactions])))
          (is (= 0 (get-in result [:failed :transactions])))
          (is (empty? (:errors result))))))))

(deftest sync-month-transactions-no-credentials
  (testing "No credentials yields zero counts (legacy format)"
    (let [conn setup/*test-conn*]
      (with-redefs [creds/get-all-plaid-credentials (constantly [])]
        (let [result (service/sync-month-transactions!
                      (with-conn (assoc deps :secrets {})) "2024-01")]
          (is (= 0 (get-in result [:success :transactions])))
          (is (empty? (:errors result))))))))

;;; ---------------------------------------------------------------------------
;;; Account sync: sync-item-accounts!
;;; ---------------------------------------------------------------------------

(deftest sync-item-accounts-persists-institution-and-accounts
  (testing "Account sync fetches item/institution/accounts and persists them"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (with-redefs [client/fetch-item
                    (constantly {:item_id "item_1" :institution_id "ins_1"})
                    client/fetch-accounts
                    (constantly [{:account_id "acc-1" :name "Checking"
                                  :type "depository" :subtype "checking"
                                  :mask "0000" :balance {:iso_currency_code "USD"}}])
                    client/fetch-institution
                    (constantly {:institution_id "ins_1" :name "Test Bank" :url nil})]
        (let [result (service/sync-item-accounts!
                      (with-conn deps)
                      {:item-id "item_1" :access-token "tok" :institution-name "Test Bank"})]
          (is (= 1 (get-in result [:success :institutions])))
          (is (= 1 (get-in result [:success :accounts])))
          (is (empty? (:errors result)))
          (is (= 1 (d/q '[:find (count ?e) . :where [?e :account/external-id _]] (d/db conn))))
          (is (= "Test Bank"
                 (d/q '[:find ?n . :where [?e :institution/id "ins_1"]
                        [?e :institution/name ?n]]
                      (d/db conn)))))))))

(deftest sync-item-accounts-error-on-item-fetch
  (testing "Failure fetching item is caught and reported as failed institution"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (with-redefs [client/fetch-item (fn [& _] (throw (ex-info "item boom" {})))
                    client/fetch-accounts (constantly [])
                    client/fetch-institution (constantly {})]
        (let [result (service/sync-item-accounts!
                      (with-conn deps)
                      {:item-id "item_1" :access-token "tok" :institution-name "Test Bank"})]
          (is (= 1 (get-in result [:failed :institutions])))
          (is (= 0 (get-in result [:success :accounts])))
          (is (= :sync-error (:type (first (:errors result))))))))))

(deftest sync-accounts-empty-credentials
  (testing "sync-accounts! with no credentials returns zeroed legacy format"
    (let [conn setup/*test-conn*]
      (with-redefs [creds/get-all-plaid-credentials (constantly [])]
        (let [result (service/sync-accounts! (with-conn (assoc deps :secrets {})))]
          (is (= 0 (get-in result [:success :accounts])))
          (is (= 0 (get-in result [:success :institutions])))
          (is (empty? (:errors result))))))))

;;; ---------------------------------------------------------------------------
;;; Sync status reporting: get-item-sync-status
;;; ---------------------------------------------------------------------------

(deftest get-item-sync-status-uses-ws-fast-path
  (testing "In-memory ws state is preferred over DB when present"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (seed-credential! conn "item_1" :status :failed)
      (ws-state/update-sync-status! "item_1" :synced
                                    :institution-name "Test Bank"
                                    :transaction-count 7)
      (let [status (service/get-item-sync-status (with-conn deps) "item_1")]
        (is (= :synced (:sync-status status)) "ws state wins over DB :failed")
        (is (= 7 (:transaction-count status)))
        (is (true? (:ready-for-display status)))))))

(deftest get-item-sync-status-falls-back-to-db
  (testing "When no ws state, status comes from DB credential"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (seed-credential! conn "item_1" :cursor "c" :status :synced)
      (creds/update-sync-status! conn "item_1" :synced {:transaction-count 3})
      (let [status (service/get-item-sync-status (with-conn deps) "item_1")]
        (is (= :synced (:sync-status status)))
        (is (= 3 (:transaction-count status)))
        (is (true? (:has-cursor status)))
        (is (true? (:ready-for-display status)))))))

(deftest get-item-sync-status-not-found
  (testing "Unknown item with no ws state reports :not-found"
    (let [conn setup/*test-conn*]
      (let [status (service/get-item-sync-status (with-conn deps) "missing")]
        (is (= :not-found (:sync-status status)))
        (is (false? (:ready-for-display status)))))))

;;; ---------------------------------------------------------------------------
;;; Private parse helpers: error capture + pending filtering
;;; ---------------------------------------------------------------------------

(deftest safe-parse-transactions-captures-errors-and-filters-pending
  (testing "Valid txns succeed, pending dropped, malformed captured as errors"
    (let [result (#'service/safe-parse-transactions
                  [(plaid-txn "ok-1")
                   (plaid-txn "pending-1" :pending true)
                   (plaid-txn "bad-1" :date "not-a-date")]
                  "test-user")]
      (is (= 1 (count (:success result))) "only the valid non-pending txn succeeds")
      (is (= "ok-1" (:transaction/external-id (first (:success result)))))
      (is (= 1 (count (:errors result))) "malformed date captured as error")
      (is (= "bad-1" (:transaction-id (first (:errors result))))))))

(deftest safe-parse-accounts-captures-errors
  (testing "Parse failures are captured per-account rather than thrown"
    (let [result (#'service/safe-parse-accounts
                  [{:account_id "acc-ok" :name "A" :type "depository"
                    :subtype "checking" :balance {:iso_currency_code "USD"}}
                   "not-a-map"]
                  "ins_1" "test-user" "item_1")]
      (is (= 1 (count (:success result))))
      (is (= "acc-ok" (:account/external-id (first (:success result)))))
      (is (= 1 (count (:errors result)))))))

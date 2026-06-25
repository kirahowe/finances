(ns finance-aggregator.resync-test
  "Behavioral tests for the trigger-decoupled resilient-sync core.

   Exercises the real engine - registry reconciliation, per-page cursor
   persistence, the resumable backfill (:backfilling -> :synced), cursor-after-
   persist on a mid-pass crash, and error classification into backoff /
   needs-reconnect / fail - against a real temporary Datalevin database with only
   the side-effecting Plaid client functions and the token lookup stubbed."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datalevin.core :as d]
   [finance-aggregator.db.connections :as connections]
   [finance-aggregator.db.credentials :as creds]
   [finance-aggregator.plaid.client :as client]
   [finance-aggregator.resync :as resync]
   [finance-aggregator.test-utils.setup :as setup]
   [finance-aggregator.ws.state :as ws-state])
  (:import
   [java.util Date]))

;;; Fixtures ---------------------------------------------------------------

(defn clear-ws-state [f]
  (doseq [[item-id _] (ws-state/get-all-sync-states)]
    (ws-state/clear-sync-status! item-id))
  (f))

(use-fixtures :each setup/with-empty-db clear-ws-state)

;;; Helpers ----------------------------------------------------------------

(def ^:private deps
  {:secrets {} :plaid-config {:days-requested 730}})

(defn- with-conn [m] (assoc m :db-conn setup/*test-conn*))

(defn- seed-user! [conn]
  (d/transact! conn [{:user/id "test-user" :user/created-at (Date.)}]))

(defn- seed-account! [conn]
  (d/transact! conn [{:institution/id "ins_test" :institution/name "Test Bank"}])
  (d/transact! conn [{:account/external-id "acc-1"
                      :account/external-name "Checking"
                      :account/provider :plaid
                      :account/institution [:institution/id "ins_test"]
                      :account/user [:user/id "test-user"]}]))

(defn- seed-credential!
  "Seed a raw Plaid Item credential row (encryption bypassed - the token lookup is
   stubbed in tests). Optionally seed a legacy cursor/status for the migration."
  [conn item-id & {:keys [cursor status]}]
  (d/transact! conn [(cond-> {:credential/id (str "plaid-item-" item-id)
                              :credential/user [:user/id "test-user"]
                              :credential/institution :plaid
                              :credential/item-id item-id
                              :credential/institution-name "Test Bank"
                              :credential/encrypted-data "dummy"
                              :credential/created-at (Date.)}
                       cursor (assoc :credential/sync-cursor cursor)
                       status (assoc :credential/sync-status status))]))

(defn- plaid-txn [id & {:keys [account] :or {account "acc-1"}}]
  {:transaction_id id :account_id account :amount 10.0 :date "2024-01-15"
   :name "TX" :merchant_name nil :pending false})

(defn- sync-response
  [& {:keys [added modified removed next-cursor has-more status]
      :or {added [] modified [] removed []
           next-cursor "cursor-1" has-more false
           status :historical-update-complete}}]
  {:added added :modified modified :removed removed
   :next_cursor next-cursor :has_more has-more
   :transactions_update_status status})

(defn- stub-accounts
  "with-redefs map for the account-fetch client fns (item/accounts/institution)."
  []
  {#'client/fetch-item        (constantly {:item_id "item_1" :institution_id "ins_test"})
   #'client/fetch-accounts    (constantly [{:account_id "acc-1" :name "Checking"
                                            :type "depository" :subtype "checking"
                                            :mask "0000" :balance {:iso_currency_code "USD"}}])
   #'client/fetch-institution (constantly {:institution_id "ins_test" :name "Test Bank" :url nil})})

(defn- tx-ids [conn]
  (set (d/q '[:find [?id ...] :where [_ :transaction/external-id ?id]] (d/db conn))))

(defn- get-conn [item-id]
  (connections/get-connection setup/*test-conn* (str "plaid:" item-id)))

;;; Tests ------------------------------------------------------------------

(deftest resync-all-initial-sync-creates-connection-persists-and-marks-synced
  (let [conn setup/*test-conn*]
    (seed-user! conn)
    (seed-account! conn)
    (seed-credential! conn "item_1")
    (with-redefs-fn (merge (stub-accounts)
                           {#'creds/get-plaid-item-credential (constantly "tok")
                            #'client/sync-transactions
                            (constantly (sync-response :added [(plaid-txn "tx-1")]
                                                       :next-cursor "cursor-A"
                                                       :status :historical-update-complete))})
      (fn []
        (let [summary (resync/resync-all! (with-conn deps))]
          (is (= 1 (:total summary)))
          (is (= [{:id "plaid:item_1" :status :synced}] (:results summary))))))
    (let [c (get-conn "item_1")]
      (is (= :synced (:connection/status c)))
      (is (= "cursor-A" (:connection/sync-state c)))
      (is (some? (:connection/last-success-at c)))
      (is (contains? (tx-ids conn) "tx-1")))))

(deftest resync-connection-resumes-from-persisted-cursor
  (testing "A second pass drives /transactions/sync from the stored cursor"
    (let [conn setup/*test-conn*
          cursors (atom [])]
      (seed-user! conn)
      (seed-account! conn)
      (seed-credential! conn "item_1")
      (with-redefs-fn (merge (stub-accounts)
                             {#'creds/get-plaid-item-credential (constantly "tok")
                              #'client/sync-transactions
                              (fn [_config _token cursor _opts]
                                (swap! cursors conj cursor)
                                (sync-response :added [(plaid-txn "tx-1")]
                                               :next-cursor "cursor-A"
                                               :status :historical-update-complete))})
        (fn []
          (resync/resync-all! (with-conn deps))
          (resync/resync-all! (with-conn deps))))
      ;; First pass: nil (initial); second pass: resumes from the stored cursor.
      (is (= [nil "cursor-A"] @cursors)))))

(deftest resync-backfill-parks-at-backfilling-then-completes
  (testing ":initial-update-complete -> :backfilling; next pass -> :synced"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (seed-account! conn)
      (seed-credential! conn "item_1")
      ;; First pass: backfill incomplete.
      (with-redefs-fn (merge (stub-accounts)
                             {#'creds/get-plaid-item-credential (constantly "tok")
                              #'client/sync-transactions
                              (constantly (sync-response :added [(plaid-txn "tx-1")]
                                                         :next-cursor "cur-1"
                                                         :status :initial-update-complete))})
        (fn [] (resync/resync-all! (with-conn deps))))
      (is (= :backfilling (:connection/status (get-conn "item_1"))))
      (is (= "cur-1" (:connection/sync-state (get-conn "item_1"))))
      ;; Second pass: historical complete.
      (with-redefs-fn (merge (stub-accounts)
                             {#'creds/get-plaid-item-credential (constantly "tok")
                              #'client/sync-transactions
                              (constantly (sync-response :added [(plaid-txn "tx-2")]
                                                         :next-cursor "cur-2"
                                                         :status :historical-update-complete))})
        (fn [] (resync/resync-all! (with-conn deps))))
      (is (= :synced (:connection/status (get-conn "item_1"))))
      (is (= "cur-2" (:connection/sync-state (get-conn "item_1"))))
      (is (= #{"tx-1" "tx-2"} (tx-ids conn))))))

(deftest resync-crash-mid-pass-keeps-last-persisted-cursor
  (testing "Cursor-after-persist: a crash on page 2 leaves page 1's cursor stored"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (seed-account! conn)
      (seed-credential! conn "item_1")
      (connections/ensure-from-credential! conn {:credential/item-id "item_1"})
      (with-redefs-fn (merge (stub-accounts)
                             {#'creds/get-plaid-item-credential (constantly "tok")
                              #'client/fetch-item-error (constantly nil)
                              #'client/sync-transactions
                              (fn [_config _token cursor _opts]
                                (if (nil? cursor)
                                  ;; Page 1: persists, advances cursor to "page-1", more pages.
                                  (sync-response :added [(plaid-txn "tx-1")]
                                                 :next-cursor "page-1" :has-more true
                                                 :status :initial-update-complete)
                                  ;; Page 2: boom (network drop) before any further cursor advance.
                                  (throw (ex-info "plaid boom" {}))))})
        (fn [] (resync/resync-connection! (with-conn deps) (get-conn "item_1"))))
      ;; tx-1 persisted; cursor advanced to page-1 (not lost); status reflects the failure.
      (is (contains? (tx-ids conn) "tx-1"))
      (is (= "page-1" (:connection/sync-state (get-conn "item_1")))))))

(deftest resync-transient-error-backs-off-stale
  (testing "A retryable error_code -> :stale with retry-count + next-retry-at"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (seed-credential! conn "item_1")
      (with-redefs-fn (merge (stub-accounts)
                             {#'creds/get-plaid-item-credential (constantly "tok")
                              #'client/fetch-item-error
                              (constantly {:error-code "INSTITUTION_DOWN" :error-message "down"})
                              #'client/sync-transactions (fn [& _] (throw (ex-info "boom" {})))})
        (fn [] (resync/resync-all! (with-conn deps))))
      (let [c (get-conn "item_1")]
        (is (= :stale (:connection/status c)))
        (is (= "INSTITUTION_DOWN" (:connection/error-code c)))
        (is (= 1 (:connection/retry-count c)))
        (is (some? (:connection/first-failure-at c)))
        (is (some? (:connection/next-retry-at c)))))))

(deftest resync-classifies-on-the-failing-calls-error-code
  (testing "The error_code the sync call surfaced drives backoff WITHOUT /item/get"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (seed-credential! conn "item_1")
      (with-redefs-fn (merge (stub-accounts)
                             {#'creds/get-plaid-item-credential (constantly "tok")
                              ;; If the supplement were consulted, this would blow up.
                              #'client/fetch-item-error
                              (fn [& _] (throw (ex-info "should not poll /item/get" {})))
                              #'client/sync-transactions
                              (fn [& _] (throw (ex-info "rate limited"
                                                        {:error-code "RATE_LIMIT_EXCEEDED"})))})
        (fn [] (resync/resync-all! (with-conn deps))))
      (let [c (get-conn "item_1")]
        (is (= :stale (:connection/status c)))
        (is (= "RATE_LIMIT_EXCEEDED" (:connection/error-code c)))
        (is (= 1 (:connection/retry-count c)))))))

(deftest resync-exhausted-backoff-falls-back-to-slow-retry
  (testing "Budget spent -> :stale with the slow cadence (not due every pass)"
    (let [conn setup/*test-conn*
          past (Date. (- (.getTime (Date.)) 1000))]
      (seed-user! conn)
      (seed-credential! conn "item_1")
      (connections/ensure-from-credential! conn {:credential/item-id "item_1"})
      ;; Pretend the fast backoff is already spent: at the retry cap, due now.
      (connections/set-error! conn "plaid:item_1" :stale
                              {:error-code "INSTITUTION_DOWN" :retry-count 8
                               :first-failure-at past :next-retry-at past})
      (let [before (.getTime (Date.))]
        (with-redefs-fn (merge (stub-accounts)
                               {#'creds/get-plaid-item-credential (constantly "tok")
                                #'client/fetch-item-error
                                (constantly {:error-code "INSTITUTION_DOWN" :error-message "down"})
                                #'client/sync-transactions (fn [& _] (throw (ex-info "boom" {})))})
          (fn [] (resync/resync-all! (with-conn deps))))
        (let [c (get-conn "item_1")]
          (is (= :stale (:connection/status c)))
          (is (= 8 (:connection/retry-count c)) "retry-count frozen at the cap")
          (is (> (- (.getTime ^Date (:connection/next-retry-at c)) before) 1800000)
              "next retry is the slow cadence (>30m), not the 15m backoff cap"))))))

(deftest resync-all-skips-in-flight-syncing
  (testing "A connection already :syncing with a recent attempt is not re-driven"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (seed-credential! conn "item_busy")
      (connections/ensure-from-credential! conn {:credential/item-id "item_busy"})
      ;; Simulate an overlapping pass in flight: :syncing + a just-now attempt.
      (connections/record-attempt! conn "plaid:item_busy")
      (with-redefs-fn {#'creds/get-plaid-item-credential (constantly "tok")
                       #'client/sync-transactions
                       (fn [& _] (throw (ex-info "should not be called" {})))}
        (fn []
          (let [summary (resync/resync-all! (with-conn deps))]
            (is (= ["plaid:item_busy"] (:skipped summary)))
            (is (empty? (:results summary)) "the in-flight connection was skipped")))))))

(deftest resync-reconnect-error-parks-needs-reconnect
  (testing "A user-action error_code -> :needs-reconnect, no backoff"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (seed-credential! conn "item_1")
      (with-redefs-fn (merge (stub-accounts)
                             {#'creds/get-plaid-item-credential (constantly "tok")
                              #'client/fetch-item-error
                              (constantly {:error-code "ITEM_LOGIN_REQUIRED" :error-message "re-auth"})
                              #'client/sync-transactions (fn [& _] (throw (ex-info "boom" {})))})
        (fn [] (resync/resync-all! (with-conn deps))))
      (let [c (get-conn "item_1")]
        (is (= :needs-reconnect (:connection/status c)))
        (is (= "ITEM_LOGIN_REQUIRED" (:connection/error-code c)))
        (is (nil? (:connection/next-retry-at c)))))))

(deftest resync-login-repaired-clears-error
  (testing "LOGIN_REPAIRED is a self-heal signal -> clear error, park :pending"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (seed-credential! conn "item_1")
      (with-redefs-fn (merge (stub-accounts)
                             {#'creds/get-plaid-item-credential (constantly "tok")
                              #'client/fetch-item-error
                              (constantly {:error-code "LOGIN_REPAIRED" :error-message "fixed"})
                              #'client/sync-transactions (fn [& _] (throw (ex-info "boom" {})))})
        (fn [] (resync/resync-all! (with-conn deps))))
      (let [c (get-conn "item_1")]
        (is (= :pending (:connection/status c)))
        (is (nil? (:connection/error-code c)))))))

(deftest resync-all-skips-backing-off-and-needs-reconnect
  (testing "Connections backing off or awaiting re-auth are not driven"
    (let [conn setup/*test-conn*
          future-time (Date. (+ (.getTime (Date.)) 600000))]
      (seed-user! conn)
      (seed-credential! conn "item_backoff")
      (seed-credential! conn "item_reconn")
      ;; Reconcile first so the connections exist, then force their states.
      (connections/ensure-from-credential!
       conn {:credential/item-id "item_backoff"})
      (connections/ensure-from-credential!
       conn {:credential/item-id "item_reconn"})
      (connections/set-error! conn "plaid:item_backoff" :stale
                              {:error-code "INSTITUTION_DOWN" :retry-count 2 :next-retry-at future-time})
      (connections/set-error! conn "plaid:item_reconn" :needs-reconnect
                              {:error-code "ITEM_LOGIN_REQUIRED"})
      (with-redefs-fn {#'creds/get-plaid-item-credential (constantly "tok")
                       #'client/sync-transactions
                       (fn [& _] (throw (ex-info "should not be called" {})))}
        (fn []
          (let [summary (resync/resync-all! (with-conn deps))]
            (is (= 2 (:total summary)))
            (is (empty? (:results summary)) "neither connection was driven")
            (is (= #{"plaid:item_backoff" "plaid:item_reconn"} (set (:skipped summary))))))))))

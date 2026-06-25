(ns finance-aggregator.db.connections-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datalevin.core :as d]
   [finance-aggregator.db.connections :as conn]
   [finance-aggregator.test-utils.setup :as setup]))

(use-fixtures :each setup/with-empty-db)

(def ^:private plaid-conn
  {:id "plaid:item_abc" :provider :plaid
   :external-id "item_abc" :institution-name "Chase"})

(deftest ensure-connection-creates-row-and-user
  (testing "Creates the connection row, the user, and starts :pending"
    (let [created (conn/ensure-connection! setup/*test-conn* plaid-conn)]
      (is (= "plaid:item_abc" (:connection/id created)))
      (is (= :plaid (:connection/provider created)))
      (is (= "item_abc" (:connection/external-id created)))
      (is (= "Chase" (:connection/institution-name created)))
      (is (= :pending (:connection/status created)))
      (is (some? (:connection/created-at created)))
      ;; The hardcoded user is created on demand.
      (is (some? (d/entity (d/db setup/*test-conn*) [:user/id "test-user"]))))))

(deftest ensure-connection-is-idempotent
  (testing "A second ensure does not clobber live sync-state or status"
    (conn/ensure-connection! setup/*test-conn* plaid-conn)
    (conn/set-sync-state! setup/*test-conn* "plaid:item_abc" "cursor-1")
    (conn/set-status! setup/*test-conn* "plaid:item_abc" :synced)
    (conn/ensure-connection! setup/*test-conn* plaid-conn)
    (is (= "cursor-1" (conn/get-sync-state setup/*test-conn* "plaid:item_abc")))
    (is (= :synced (:connection/status (conn/get-connection setup/*test-conn* "plaid:item_abc"))))
    (is (= 1 (count (conn/list-connections setup/*test-conn*))))))

(deftest ensure-connection-without-optional-attrs
  (testing "A single-connection provider needs only :id + :provider"
    (let [created (conn/ensure-connection! setup/*test-conn*
                                           {:id "lunchflow" :provider :lunchflow})]
      (is (= :lunchflow (:connection/provider created)))
      (is (nil? (:connection/external-id created)))
      (is (= :pending (:connection/status created))))))

(deftest get-connection-nil-when-absent
  (is (nil? (conn/get-connection setup/*test-conn* "nope"))))

(deftest ensure-from-credential-seeds-cursor-and-status
  (testing "First creation seeds sync-state + status + count from legacy creds fields"
    (let [created (conn/ensure-from-credential!
                   setup/*test-conn*
                   {:credential/item-id "item_seed"
                    :credential/institution-name "Chase"
                    :credential/sync-cursor "legacy-cursor"
                    :credential/sync-status :synced
                    :credential/transaction-count 17})]
      (is (= "plaid:item_seed" (:connection/id created)))
      (is (= :plaid (:connection/provider created)))
      (is (= "item_seed" (:connection/external-id created)))
      (is (= "Chase" (:connection/institution-name created)))
      (is (= "legacy-cursor" (:connection/sync-state created)))
      (is (= :synced (:connection/status created)))
      (is (= 17 (:connection/transaction-count created))))))

(deftest ensure-from-credential-maps-mid-backfill-status
  (testing "A legacy :syncing-historical seeds as :backfilling; transient states seed :pending"
    (is (= :backfilling
           (:connection/status
            (conn/ensure-from-credential!
             setup/*test-conn*
             {:credential/item-id "item_bf" :credential/sync-status :syncing-historical}))))
    (is (= :pending
           (:connection/status
            (conn/ensure-from-credential!
             setup/*test-conn*
             {:credential/item-id "item_fail" :credential/sync-status :failed}))))))

(deftest ensure-from-credential-without-legacy-fields-starts-pending
  (testing "A fresh credential (no cursor/status) yields a :pending connection, no sync-state"
    (let [created (conn/ensure-from-credential!
                   setup/*test-conn* {:credential/item-id "item_new"})]
      (is (= :pending (:connection/status created)))
      (is (nil? (:connection/sync-state created))))))

(deftest ensure-from-credential-is-idempotent
  (testing "A second ensure never re-seeds over live sync-state/status"
    (conn/ensure-from-credential!
     setup/*test-conn*
     {:credential/item-id "item_idem" :credential/sync-cursor "seed-cur" :credential/sync-status :synced})
    (conn/set-sync-state! setup/*test-conn* "plaid:item_idem" "advanced-cur")
    (conn/set-status! setup/*test-conn* "plaid:item_idem" :backfilling)
    ;; Re-ensure with the STALE legacy fields must not clobber advanced state.
    (conn/ensure-from-credential!
     setup/*test-conn*
     {:credential/item-id "item_idem" :credential/sync-cursor "seed-cur" :credential/sync-status :synced})
    (let [c (conn/get-connection setup/*test-conn* "plaid:item_idem")]
      (is (= "advanced-cur" (:connection/sync-state c)))
      (is (= :backfilling (:connection/status c)))
      (is (= 1 (count (conn/list-connections setup/*test-conn*)))))))

(deftest list-connections-all-and-by-provider
  (testing "Lists all connections and filters by provider"
    (conn/ensure-connection! setup/*test-conn* plaid-conn)
    (conn/ensure-connection! setup/*test-conn*
                             {:id "plaid:item_def" :provider :plaid :external-id "item_def"})
    (conn/ensure-connection! setup/*test-conn* {:id "lunchflow" :provider :lunchflow})
    (is (= 3 (count (conn/list-connections setup/*test-conn*))))
    (is (= 2 (count (conn/list-connections setup/*test-conn* :plaid))))
    (is (= 1 (count (conn/list-connections setup/*test-conn* :lunchflow))))
    (is (= #{"plaid:item_abc" "plaid:item_def"}
           (set (map :connection/id (conn/list-connections setup/*test-conn* :plaid)))))))

(deftest sync-state-roundtrip
  (testing "nil before set, roundtrips, independent per connection"
    (conn/ensure-connection! setup/*test-conn* plaid-conn)
    (conn/ensure-connection! setup/*test-conn*
                             {:id "plaid:item_def" :provider :plaid :external-id "item_def"})
    (is (nil? (conn/get-sync-state setup/*test-conn* "plaid:item_abc")))
    (is (true? (conn/set-sync-state! setup/*test-conn* "plaid:item_abc" "cursor-a")))
    (conn/set-sync-state! setup/*test-conn* "plaid:item_def" "cursor-d")
    (is (= "cursor-a" (conn/get-sync-state setup/*test-conn* "plaid:item_abc")))
    (is (= "cursor-d" (conn/get-sync-state setup/*test-conn* "plaid:item_def"))))
  (testing "Returns false for a missing connection"
    (is (nil? (conn/set-sync-state! setup/*test-conn* "missing" "x")))))

(deftest record-attempt-sets-syncing-and-timestamp
  (conn/ensure-connection! setup/*test-conn* plaid-conn)
  (is (true? (conn/record-attempt! setup/*test-conn* "plaid:item_abc")))
  (let [c (conn/get-connection setup/*test-conn* "plaid:item_abc")]
    (is (= :syncing (:connection/status c)))
    (is (some? (:connection/last-attempt-at c)))))

(deftest record-success-sets-status-time-count-and-clears-error
  (testing "Success records timestamp + count and clears a prior error streak"
    (conn/ensure-connection! setup/*test-conn* plaid-conn)
    (conn/set-error! setup/*test-conn* "plaid:item_abc" :stale
                     {:error-code "INSTITUTION_DOWN" :error-message "down" :retry-count 2
                      :first-failure-at (java.util.Date.) :next-retry-at (java.util.Date.)})
    (is (true? (conn/record-success! setup/*test-conn* "plaid:item_abc" :synced {:transaction-count 42})))
    (let [c (conn/get-connection setup/*test-conn* "plaid:item_abc")]
      (is (= :synced (:connection/status c)))
      (is (some? (:connection/last-success-at c)))
      (is (= 42 (:connection/transaction-count c)))
      (is (nil? (:connection/error-code c)))
      (is (nil? (:connection/error-message c)))
      (is (nil? (:connection/first-failure-at c)))
      (is (nil? (:connection/next-retry-at c)))
      (is (= 0 (:connection/retry-count c))))))

(deftest record-success-defaults-to-synced
  (conn/ensure-connection! setup/*test-conn* plaid-conn)
  (conn/record-success! setup/*test-conn* "plaid:item_abc")
  (is (= :synced (:connection/status (conn/get-connection setup/*test-conn* "plaid:item_abc")))))

(deftest set-error-records-status-code-and-backoff
  (conn/ensure-connection! setup/*test-conn* plaid-conn)
  (conn/set-error! setup/*test-conn* "plaid:item_abc" :needs-reconnect
                   {:error-code "ITEM_LOGIN_REQUIRED" :error-message "re-auth"})
  (let [c (conn/get-connection setup/*test-conn* "plaid:item_abc")]
    (is (= :needs-reconnect (:connection/status c)))
    (is (= "ITEM_LOGIN_REQUIRED" (:connection/error-code c)))
    (is (= "re-auth" (:connection/error-message c)))))

(deftest clear-error-is-safe-without-prior-error
  (testing "Clearing when no error present is a no-op that still resets retry-count"
    (conn/ensure-connection! setup/*test-conn* plaid-conn)
    (is (true? (conn/clear-error! setup/*test-conn* "plaid:item_abc")))
    (let [c (conn/get-connection setup/*test-conn* "plaid:item_abc")]
      (is (nil? (:connection/error-code c)))
      (is (= 0 (:connection/retry-count c))))))

(deftest delete-connection-removes-row
  (conn/ensure-connection! setup/*test-conn* plaid-conn)
  (is (true? (conn/delete-connection! setup/*test-conn* "plaid:item_abc")))
  (is (nil? (conn/get-connection setup/*test-conn* "plaid:item_abc")))
  (is (nil? (conn/delete-connection! setup/*test-conn* "plaid:item_abc"))))

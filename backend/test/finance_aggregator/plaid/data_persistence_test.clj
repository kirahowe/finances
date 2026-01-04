(ns finance-aggregator.plaid.data-persistence-test
  "Integration tests for Plaid data persistence to database.

   Verifies that Plaid data:
   - Transforms correctly to database schema
   - Persists to database without errors
   - Can be queried back with correct values
   - Survives connection close/reopen

   Mirrors SimpleFIN data_persistence_test.clj pattern."
  (:require [clojure.test :refer :all]
            [datalevin.core :as d]
            [finance-aggregator.data.schema :as schema]
            [finance-aggregator.plaid.data :as plaid-data]
            [finance-aggregator.db :as db])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def test-db-path (atom nil))
(def test-conn (atom nil))

(defn create-temp-db-dir
  "Create a temporary directory for test database"
  []
  (let [temp-dir (Files/createTempDirectory "datalevin-plaid-test-" (make-array FileAttribute 0))]
    (.toString temp-dir)))

(defn delete-directory-recursive
  "Delete a directory and all its contents"
  [^File dir]
  (when (.exists dir)
    (doseq [file (.listFiles dir)]
      (if (.isDirectory file)
        (delete-directory-recursive file)
        (.delete file)))
    (.delete dir)))

(defn setup-test-db
  "Create an ephemeral test database"
  []
  (let [db-path (create-temp-db-dir)]
    (reset! test-db-path db-path)
    (reset! test-conn (d/get-conn db-path schema/schema))))

(defn teardown-test-db
  "Close and delete the test database"
  []
  (when @test-conn
    (d/close @test-conn)
    (reset! test-conn nil))
  (when @test-db-path
    (delete-directory-recursive (File. @test-db-path))
    (reset! test-db-path nil)))

(defn db-fixture
  "Test fixture that creates and tears down test database"
  [f]
  (setup-test-db)
  (try
    (f)
    (finally
      (teardown-test-db))))

(use-fixtures :each db-fixture)

;; Sample Plaid data (similar to what API returns)
(def sample-plaid-institution
  {:institution_id "ins_38"
   :name "Scotiabank"
   :url nil  ; Important: test nil handling
   :primary_color "#EE0000"
   :logo "base64-encoded-logo"})

(def sample-plaid-account
  {:account_id "acc-plaid-123"
   :name "Plaid Checking"
   :official_name "Plaid Gold Standard Checking"
   :type "depository"
   :subtype "checking"
   :mask "0000"
   :balance {:iso_currency_code "USD"
            :current 1000.0}})

(def sample-plaid-transactions
  [{:transaction_id "tx-plaid-001"
    :account_id "acc-plaid-123"
    :amount 100.50
    :date "2024-01-15"
    :name "STARBUCKS"
    :merchant_name "Starbucks"
    :pending false}
   {:transaction_id "tx-plaid-002"
    :account_id "acc-plaid-123"
    :amount -50.00
    :date "2024-01-16"
    :name "PAYCHECK DEPOSIT"
    :merchant_name nil
    :pending false}
   {:transaction_id "tx-plaid-003"
    :account_id "acc-plaid-123"
    :amount 25.0
    :date "2024-01-17"
    :name "PENDING TRANSACTION"
    :merchant_name nil
    :pending true}])  ; Should be filtered out

(deftest test-parse-institution-handles-nil-url
  (testing "Parse institution with nil URL doesn't include nil in result"
    (let [parsed (plaid-data/parse-institution sample-plaid-institution)]
      (is (= "ins_38" (:institution/id parsed)))
      (is (= "Scotiabank" (:institution/name parsed)))
      ;; Critical: ensure nil url is either absent or handled correctly
      (is (or (not (contains? parsed :institution/url))
              (nil? (:institution/url parsed)))
          "nil URL should either be absent or nil, not cause DB error"))))

(deftest test-insert-institution-with-nil-url
  (testing "Can insert institution with nil URL without errors"
    ;; This is the failing case from the logs
    (let [parsed (plaid-data/parse-institution sample-plaid-institution)]
      ;; Should not throw "Cannot store nil as a value" error
      (is (some? (d/transact! @test-conn [parsed]))
          "Should successfully insert institution without throwing")

      ;; Verify it persisted
      (let [db (d/db @test-conn)
            institutions (d/q '[:find [(pull ?e [*]) ...]
                                :where [?e :institution/id]]
                              db)]
        (is (= 1 (count institutions)))
        (is (= "Scotiabank" (:institution/name (first institutions))))))))

(deftest test-insert-and-query-plaid-data
  (testing "Can insert and query Plaid data in same connection"
    ;; Create test user first
    (d/transact! @test-conn [{:user/id "test-user"
                              :user/created-at (java.util.Date.)}])

    ;; Parse Plaid data
    (let [parsed-institution (plaid-data/parse-institution sample-plaid-institution)
          parsed-account (plaid-data/parse-account sample-plaid-account
                                                   "ins_38"
                                                   "test-user"
                                                   "item_abc123")
          parsed-transactions (keep #(plaid-data/parse-transaction % "test-user")
                                   sample-plaid-transactions)]

      ;; Insert using db/insert! pattern
      (db/insert! {:institutions #{parsed-institution}
                   :accounts #{parsed-account}
                   :transactions parsed-transactions}
                  @test-conn)

      ;; Query in same connection
      (let [db (d/db @test-conn)
            inst-count (d/q '[:find (count ?e) :where [?e :institution/id _]] db)
            acct-count (d/q '[:find (count ?e) :where [?e :account/external-id _]] db)
            tx-count (d/q '[:find (count ?e) :where [?e :transaction/external-id _]] db)]
        (is (= [[1]] inst-count) "Should have 1 institution")
        (is (= [[1]] acct-count) "Should have 1 account")
        (is (= [[2]] tx-count) "Should have 2 transactions (pending filtered)")))))

(deftest test-plaid-data-persists-across-connections
  (testing "Plaid data persists when connection is closed and reopened"
    ;; Create test user
    (d/transact! @test-conn [{:user/id "test-user"
                              :user/created-at (java.util.Date.)}])

    ;; Parse and insert data
    (let [parsed-institution (plaid-data/parse-institution sample-plaid-institution)
          parsed-account (plaid-data/parse-account sample-plaid-account
                                                   "ins_38"
                                                   "test-user"
                                                   "item_abc123")
          parsed-transactions (keep #(plaid-data/parse-transaction % "test-user")
                                   sample-plaid-transactions)]

      (db/insert! {:institutions #{parsed-institution}
                   :accounts #{parsed-account}
                   :transactions parsed-transactions}
                  @test-conn))

    ;; Verify data exists before close
    (let [tx-count-before (d/q '[:find (count ?e) :where [?e :transaction/external-id _]]
                               (d/db @test-conn))]
      (is (= [[2]] tx-count-before) "Should have 2 transactions before close"))

    ;; Close connection
    (d/close @test-conn)

    ;; Reopen connection to same database
    (reset! test-conn (d/get-conn @test-db-path schema/schema))

    ;; Verify data still exists
    (let [db (d/db @test-conn)
          inst-count (d/q '[:find (count ?e) :where [?e :institution/id _]] db)
          acct-count (d/q '[:find (count ?e) :where [?e :account/external-id _]] db)
          tx-count (d/q '[:find (count ?e) :where [?e :transaction/external-id _]] db)]
      (is (= [[1]] inst-count) "Should still have 1 institution after reopen")
      (is (= [[1]] acct-count) "Should still have 1 account after reopen")
      (is (= [[2]] tx-count) "Should still have 2 transactions after reopen"))))

(deftest test-query-plaid-data-values
  (testing "Can query and retrieve actual Plaid data values after persistence"
    ;; Create test user
    (d/transact! @test-conn [{:user/id "test-user"
                              :user/created-at (java.util.Date.)}])

    ;; Parse and insert data
    (let [parsed-institution (plaid-data/parse-institution sample-plaid-institution)
          parsed-account (plaid-data/parse-account sample-plaid-account
                                                   "ins_38"
                                                   "test-user"
                                                   "item_abc123")
          parsed-transactions (keep #(plaid-data/parse-transaction % "test-user")
                                   sample-plaid-transactions)]

      (db/insert! {:institutions #{parsed-institution}
                   :accounts #{parsed-account}
                   :transactions parsed-transactions}
                  @test-conn))

    ;; Close and reopen
    (d/close @test-conn)
    (reset! test-conn (d/get-conn @test-db-path schema/schema))

    ;; Query institution details
    (let [institutions (d/q '[:find [(pull ?e [*]) ...]
                              :where [?e :institution/id]]
                            (d/db @test-conn))]
      (is (= 1 (count institutions)))
      (let [inst (first institutions)]
        (is (= "Scotiabank" (:institution/name inst)))
        (is (= "ins_38" (:institution/id inst)))))

    ;; Query account details
    (let [accounts (d/q '[:find [(pull ?e [*]) ...]
                          :where [?e :account/external-id]]
                        (d/db @test-conn))]
      (is (= 1 (count accounts)))
      (let [acct (first accounts)]
        (is (= "Plaid Checking" (:account/external-name acct)))
        (is (= "depository" (:account/plaid-type acct)))
        (is (= "checking" (:account/plaid-subtype acct)))
        (is (= "item_abc123" (:account/item-id acct)))))

    ;; Query transaction details
    (let [transactions (d/q '[:find [(pull ?e [*]) ...]
                              :where [?e :transaction/external-id]]
                            (d/db @test-conn))]
      (is (= 2 (count transactions)))
      (let [tx-ids (set (map :transaction/external-id transactions))]
        (is (contains? tx-ids "tx-plaid-001"))
        (is (contains? tx-ids "tx-plaid-002"))
        (is (not (contains? tx-ids "tx-plaid-003")) "Pending transaction should be filtered"))

      ;; Verify specific transaction values
      (let [tx-001 (first (filter #(= "tx-plaid-001" (:transaction/external-id %)) transactions))]
        (is (= (bigdec "100.50") (:transaction/amount tx-001)))
        (is (= "STARBUCKS" (:transaction/description tx-001)))
        (is (= "Starbucks" (:transaction/payee tx-001)))
        (is (some? (:transaction/posted-date tx-001))
            "Transaction should have posted-date set")
        (is (instance? java.util.Date (:transaction/posted-date tx-001)))
        (is (= (:transaction/date tx-001) (:transaction/posted-date tx-001))
            "For Plaid, date and posted-date should be the same")))))

(deftest test-plaid-account-institution-reference
  (testing "Plaid account correctly references institution via lookup ref"
    ;; Create test user
    (d/transact! @test-conn [{:user/id "test-user"
                              :user/created-at (java.util.Date.)}])

    ;; Insert institution and account
    (let [parsed-institution (plaid-data/parse-institution sample-plaid-institution)
          parsed-account (plaid-data/parse-account sample-plaid-account
                                                   "ins_38"
                                                   "test-user"
                                                   "item_abc123")]

      (db/insert! {:institutions #{parsed-institution}
                   :accounts #{parsed-account}
                   :transactions []}
                  @test-conn))

    ;; Query account with institution join
    (let [db (d/db @test-conn)
          account (d/q '[:find (pull ?acct [* {:account/institution [*]}]) .
                         :where [?acct :account/external-id "acc-plaid-123"]]
                       db)]
      (is (some? account))
      (is (= "Plaid Checking" (:account/external-name account)))
      (is (= "Scotiabank" (get-in account [:account/institution :institution/name])))
      (is (= "ins_38" (get-in account [:account/institution :institution/id]))))))

(deftest test-plaid-transaction-account-reference
  (testing "Plaid transaction correctly references account via lookup ref"
    ;; Create test user
    (d/transact! @test-conn [{:user/id "test-user"
                              :user/created-at (java.util.Date.)}])

    ;; Insert full dataset
    (let [parsed-institution (plaid-data/parse-institution sample-plaid-institution)
          parsed-account (plaid-data/parse-account sample-plaid-account
                                                   "ins_38"
                                                   "test-user"
                                                   "item_abc123")
          parsed-transactions (keep #(plaid-data/parse-transaction % "test-user")
                                   sample-plaid-transactions)]

      (db/insert! {:institutions #{parsed-institution}
                   :accounts #{parsed-account}
                   :transactions parsed-transactions}
                  @test-conn))

    ;; Query transaction with account join
    (let [db (d/db @test-conn)
          transaction (d/q '[:find (pull ?tx [* {:transaction/account [*]}]) .
                             :where [?tx :transaction/external-id "tx-plaid-001"]]
                           db)]
      (is (some? transaction))
      (is (= "STARBUCKS" (:transaction/description transaction)))
      (is (= "Plaid Checking" (get-in transaction [:transaction/account :account/external-name])))
      (is (= "acc-plaid-123" (get-in transaction [:transaction/account :account/external-id]))))))

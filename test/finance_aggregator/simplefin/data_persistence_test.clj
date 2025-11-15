(ns finance-aggregator.simplefin.data-persistence-test
  (:require [clojure.test :refer :all]
            [datalevin.core :as d]
            [finance-aggregator.data.schema :as schema]
            [finance-aggregator.simplefin.data :as sfd]
            [finance-aggregator.db :as db])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def test-db-path (atom nil))
(def test-conn (atom nil))

(defn create-temp-db-dir
  "Create a temporary directory for test database"
  []
  (let [temp-dir (Files/createTempDirectory "datalevin-persistence-test-" (make-array FileAttribute 0))]
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

;; Sample SimpleFIN account data (similar to what API returns)
(def sample-simplefin-account
  {:id "acc-123"
   :org {:id "inst-001"
         :name "Test Bank"
         :domain "testbank.com"
         :url "https://testbank.com"}
   :name "Test Checking"
   :currency "CAD"
   :balance "1000.00"
   :transactions [{:id "tx-001"
                   :posted 1609459200
                   :amount "-50.00"
                   :description "Coffee Shop"
                   :payee "Starbucks"
                   :memo "Morning coffee"
                   :transacted_at 1609459200}
                  {:id "tx-002"
                   :posted 1609545600
                   :amount "2000.00"
                   :description "Salary deposit"
                   :payee "Employer Inc"
                   :transacted_at 1609545600}]})

(deftest test-parse-entities-structure
  (testing "Parsed entities have correct structure"
    (let [parsed (sfd/parse-entities [sample-simplefin-account])]
      (is (map? parsed))
      (is (contains? parsed :institutions))
      (is (contains? parsed :accounts))
      (is (contains? parsed :transactions))

      ;; Check institutions
      (is (set? (:institutions parsed)))
      (is (= 1 (count (:institutions parsed))))
      (let [inst (first (:institutions parsed))]
        (is (= "inst-001" (:institution/id inst)))
        (is (= "Test Bank" (:institution/name inst)))
        (is (= "testbank.com" (:institution/domain inst)))
        (is (= "https://testbank.com" (:institution/url inst))))

      ;; Check accounts
      (is (set? (:accounts parsed)))
      (is (= 1 (count (:accounts parsed))))
      (let [acct (first (:accounts parsed))]
        (is (= "acc-123" (:account/external-id acct)))
        (is (= "Test Checking" (:account/external-name acct)))
        (is (= "CAD" (:account/currency acct)))
        (is (= [:institution/id "inst-001"] (:account/institution acct))))

      ;; Check transactions
      (is (vector? (:transactions parsed)))
      (is (= 2 (count (:transactions parsed))))
      (let [tx (first (:transactions parsed))]
        (is (= "tx-001" (:transaction/external-id tx)))
        (is (= [:account/external-id "acc-123"] (:transaction/account tx)))
        (is (instance? java.util.Date (:transaction/posted-date tx)))
        (is (instance? java.math.BigDecimal (:transaction/amount tx)))))))

(deftest test-insert-and-query-in-same-connection
  (testing "Can insert and query data in same connection"
    (let [parsed (sfd/parse-entities [sample-simplefin-account])]
      (db/insert! parsed @test-conn)

      ;; Query in same connection
      (let [inst-count (d/q '[:find (count ?e) :where [?e :institution/id _]] (d/db @test-conn))
            acct-count (d/q '[:find (count ?e) :where [?e :account/external-id _]] (d/db @test-conn))
            tx-count (d/q '[:find (count ?e) :where [?e :transaction/external-id _]] (d/db @test-conn))]
        (is (= [[1]] inst-count) "Should have 1 institution")
        (is (= [[1]] acct-count) "Should have 1 account")
        (is (= [[2]] tx-count) "Should have 2 transactions")))))

(deftest test-data-persists-across-connections
  (testing "Data persists when connection is closed and reopened"
    ;; Insert data
    (let [parsed (sfd/parse-entities [sample-simplefin-account])]
      (db/insert! parsed @test-conn))

    ;; Verify data exists
    (let [tx-count-before (d/q '[:find (count ?e) :where [?e :transaction/external-id _]] (d/db @test-conn))]
      (is (= [[2]] tx-count-before) "Should have 2 transactions before close"))

    ;; Close connection
    (d/close @test-conn)

    ;; Reopen connection to same database
    (reset! test-conn (d/get-conn @test-db-path schema/schema))

    ;; Verify data still exists
    (let [inst-count (d/q '[:find (count ?e) :where [?e :institution/id _]] (d/db @test-conn))
          acct-count (d/q '[:find (count ?e) :where [?e :account/external-id _]] (d/db @test-conn))
          tx-count (d/q '[:find (count ?e) :where [?e :transaction/external-id _]] (d/db @test-conn))]
      (is (= [[1]] inst-count) "Should still have 1 institution after reopen")
      (is (= [[1]] acct-count) "Should still have 1 account after reopen")
      (is (= [[2]] tx-count) "Should still have 2 transactions after reopen"))))

(deftest test-query-actual-data-values
  (testing "Can query and retrieve actual data values after persistence"
    ;; Insert data
    (let [parsed (sfd/parse-entities [sample-simplefin-account])]
      (db/insert! parsed @test-conn))

    ;; Close and reopen
    (d/close @test-conn)
    (reset! test-conn (d/get-conn @test-db-path schema/schema))

    ;; Query institution details
    (let [institutions (d/q '[:find [(pull ?e [*]) ...]
                              :where [?e :institution/id]]
                            (d/db @test-conn))]
      (is (= 1 (count institutions)))
      (let [inst (first institutions)]
        (is (= "Test Bank" (:institution/name inst)))))

    ;; Query account details
    (let [accounts (d/q '[:find [(pull ?e [*]) ...]
                          :where [?e :account/external-id]]
                        (d/db @test-conn))]
      (is (= 1 (count accounts)))
      (let [acct (first accounts)]
        (is (= "Test Checking" (:account/external-name acct)))))

    ;; Query transaction details
    (let [transactions (d/q '[:find [(pull ?e [*]) ...]
                              :where [?e :transaction/external-id]]
                            (d/db @test-conn))]
      (is (= 2 (count transactions)))
      (let [tx-ids (set (map :transaction/external-id transactions))]
        (is (contains? tx-ids "tx-001"))
        (is (contains? tx-ids "tx-002"))))))

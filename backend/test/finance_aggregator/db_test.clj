(ns finance-aggregator.db-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [finance-aggregator.data.schema :as schema]
            [finance-aggregator.db :as db])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def test-db-path (atom nil))
(def test-conn (atom nil))

(defn create-temp-db-dir
  "Create a temporary directory for test database"
  []
  (let [temp-dir (Files/createTempDirectory "datalevin-test-" (make-array FileAttribute 0))]
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

;; Test data fixtures
;; Test data is already in database-ready format (as if transformed by simplefin/data)
(def test-institutions
  [{:institution/id "inst-001"
    :institution/name "Test Bank"
    :institution/domain "testbank.com"
    :institution/url "https://testbank.com"}
   {:institution/id "inst-002"
    :institution/name "Example Credit Union"
    :institution/domain "examplecu.org"
    :institution/url "https://examplecu.org"}])

(def test-accounts
  [{:account/external-id "acct-001"
    :account/external-name "Test Chequing"
    :account/institution [:institution/id "inst-001"]
    :account/currency "CAD"}
   {:account/external-id "acct-002"
    :account/external-name "Example Savings"
    :account/institution [:institution/id "inst-002"]
    :account/currency "USD"}])

(def test-transactions
  [{:transaction/external-id "tx-001"
    :transaction/account [:account/external-id "acct-001"]
    :transaction/date (java.util.Date. 1609459200000)
    :transaction/amount (bigdec "100.50")
    :transaction/payee "Test Payee 1"
    :transaction/description "Test transaction 1"}
   {:transaction/external-id "tx-002"
    :transaction/account [:account/external-id "acct-002"]
    :transaction/date (java.util.Date. 1609545600000)
    :transaction/posted-date (java.util.Date. 1609632000000)
    :transaction/amount (bigdec "-50.25")
    :transaction/payee "Test Payee 2"
    :transaction/description "Test transaction 2"
    :transaction/memo "Test memo"}])


(deftest test-insert-function
  (testing "Test the insert! function with complete entity map"
    (let [entities {:institutions test-institutions
                    :accounts test-accounts
                    :transactions test-transactions}]
      (db/insert! entities @test-conn)

      ;; Verify institutions
      (let [all-insts (d/q '[:find [(pull ?e [*]) ...]
                             :where [?e :institution/id]]
                           (d/db @test-conn))]
        (is (= 2 (count all-insts))))

      ;; Verify accounts
      (let [all-accounts (d/q '[:find [(pull ?e [*]) ...]
                                :where [?e :account/external-id]]
                              (d/db @test-conn))]
        (is (= 2 (count all-accounts))))

      ;; Verify transactions
      (let [all-txs (d/q '[:find [(pull ?e [*]) ...]
                           :where [?e :transaction/external-id]]
                         (d/db @test-conn))]
        (is (= 2 (count all-txs)))))))

(deftest test-insert-creates-referenced-user
  (testing "inserting an account that references a not-yet-created user upserts
            the user so the :account/user lookup ref resolves (no entity-id/missing)"
    (db/insert! {:institutions [{:institution/id "inst-001" :institution/name "Test Bank"}]
                 :accounts [{:account/external-id "acct-001"
                             :account/external-name "Chequing"
                             :account/institution [:institution/id "inst-001"]
                             :account/user [:user/id "test-user"]}]
                 :transactions []}
                @test-conn)
    (let [db (d/db @test-conn)]
      (is (some? (d/entity db [:user/id "test-user"]))
          "the referenced user is created")
      (is (= {:user/id "test-user"}
             (:account/user
              (d/pull db '[{:account/user [:user/id]}] [:account/external-id "acct-001"])))
          "the account resolves to the created user"))))

(deftest test-insert-preserves-existing-user-created-at
  (testing "an existing user's :user/created-at is not clobbered on re-insert"
    (let [created (java.util.Date. 1000000000000)]
      (d/transact! @test-conn [{:user/id "test-user" :user/created-at created}])
      (db/insert! {:institutions []
                   :accounts [{:account/external-id "acct-001"
                               :account/external-name "Chequing"
                               :account/user [:user/id "test-user"]}]
                   :transactions []}
                  @test-conn)
      (is (= created
             (:user/created-at (d/pull (d/db @test-conn) '[:user/created-at] [:user/id "test-user"])))
          "created-at is untouched because the user already existed"))))

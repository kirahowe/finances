(ns finance-aggregator.data.schema-test
  "Tests for enhanced database schema with user scoping."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datalevin.core :as d]
   [finance-aggregator.data.schema :as schema]
   [finance-aggregator.db.core :as db]))

(def test-db-path (str "data/test-schema-" (System/currentTimeMillis) ".db"))

(use-fixtures :each
  (fn [f]
    ;; Cleanup before test
    (when (.exists (clojure.java.io/file test-db-path))
      (db/delete-database! test-db-path))
    (f)
    ;; Cleanup after test
    (db/delete-database! test-db-path)))

(deftest user-entity-test
  (testing "can create and query user entities"
    (let [conn (db/start-db! test-db-path)]
      ;; Create a user
      (d/transact! conn [{:user/id "user-1"
                         :user/email "test@example.com"
                         :user/created-at (java.util.Date.)}])

      ;; Query the user
      (let [user (d/pull @conn '[*] [:user/id "user-1"])]
        (is (= "user-1" (:user/id user)))
        (is (= "test@example.com" (:user/email user)))
        (is (some? (:user/created-at user))))

      (db/stop-db! conn))))

(deftest account-with-user-test
  (testing "can create account with user reference"
    (let [conn (db/start-db! test-db-path)]
      ;; Create user and institution
      (d/transact! conn [{:user/id "user-1"
                         :user/email "test@example.com"
                         :user/created-at (java.util.Date.)}
                        {:institution/id "inst-1"
                         :institution/name "Test Bank"}])

      (let [user-eid (d/entid @conn [:user/id "user-1"])
            inst-eid (d/entid @conn [:institution/id "inst-1"])]

        ;; Create account with user reference
        (d/transact! conn [{:account/external-id "acct-1"
                           :account/external-name "Checking"
                           :account/institution inst-eid
                           :account/user user-eid
                           :account/currency "USD"
                           :account/type :checking}])

        ;; Query account with user
        (let [account (d/pull @conn '[* {:account/user [*]}]
                             [:account/external-id "acct-1"])]
          (is (= "acct-1" (:account/external-id account)))
          (is (= "user-1" (get-in account [:account/user :user/id])))
          (is (= "Checking" (:account/external-name account)))))

      (db/stop-db! conn))))

(deftest transaction-with-user-test
  (testing "can create transaction with user reference"
    (let [conn (db/start-db! test-db-path)]
      ;; Create user, institution, and account
      (d/transact! conn [{:user/id "user-1"
                         :user/email "test@example.com"
                         :user/created-at (java.util.Date.)}
                        {:institution/id "inst-1"
                         :institution/name "Test Bank"}])

      (let [user-eid (d/entid @conn [:user/id "user-1"])
            inst-eid (d/entid @conn [:institution/id "inst-1"])]

        (d/transact! conn [{:account/external-id "acct-1"
                           :account/external-name "Checking"
                           :account/institution inst-eid
                           :account/user user-eid
                           :account/currency "USD"
                           :account/type :checking}])

        (let [acct-eid (d/entid @conn [:account/external-id "acct-1"])]

          ;; Create transaction with user reference
          (d/transact! conn [{:transaction/external-id "tx-1"
                             :transaction/account acct-eid
                             :transaction/user user-eid
                             :transaction/date (java.util.Date.)
                             :transaction/amount -25.50M
                             :transaction/payee "Coffee Shop"}])

          ;; Query transaction with user
          (let [tx (d/pull @conn '[* {:transaction/user [*]}]
                          [:transaction/external-id "tx-1"])]
            (is (= "tx-1" (:transaction/external-id tx)))
            (is (= "user-1" (get-in tx [:transaction/user :user/id])))
            (is (= -25.50M (:transaction/amount tx))))))

      (db/stop-db! conn))))

(deftest category-with-user-test
  (testing "can create user-specific and system categories"
    (let [conn (db/start-db! test-db-path)]
      ;; Create user
      (d/transact! conn [{:user/id "user-1"
                         :user/email "test@example.com"
                         :user/created-at (java.util.Date.)}])

      (let [user-eid (d/entid @conn [:user/id "user-1"])]

        ;; Create system category (no user)
        (d/transact! conn [{:category/ident :category/groceries
                           :category/name "Groceries"
                           :category/type :expense}])

        ;; Create user-specific category
        (d/transact! conn [{:category/ident :category/my-custom
                           :category/name "My Custom Category"
                           :category/type :expense
                           :category/user user-eid}])

        ;; Query system category (should have no user)
        (let [sys-cat (d/pull @conn '[*] [:category/ident :category/groceries])]
          (is (= "Groceries" (:category/name sys-cat)))
          (is (nil? (:category/user sys-cat))))

        ;; Query user category
        (let [user-cat (d/pull @conn '[* {:category/user [*]}]
                              [:category/ident :category/my-custom])]
          (is (= "My Custom Category" (:category/name user-cat)))
          (is (= "user-1" (get-in user-cat [:category/user :user/id])))))

      (db/stop-db! conn))))

(deftest credential-entity-test
  (testing "can create and query encrypted credentials"
    (let [conn (db/start-db! test-db-path)]
      ;; Create user
      (d/transact! conn [{:user/id "user-1"
                         :user/email "test@example.com"
                         :user/created-at (java.util.Date.)}])

      (let [user-eid (d/entid @conn [:user/id "user-1"])]

        ;; Create credential
        (d/transact! conn [{:credential/id "cred-1"
                           :credential/user user-eid
                           :credential/institution :simplefin
                           :credential/encrypted-data "encrypted-token-data"
                           :credential/created-at (java.util.Date.)
                           :credential/last-used (java.util.Date.)}])

        ;; Query credential
        (let [cred (d/pull @conn '[* {:credential/user [*]}]
                          [:credential/id "cred-1"])]
          (is (= "cred-1" (:credential/id cred)))
          (is (= :simplefin (:credential/institution cred)))
          (is (= "encrypted-token-data" (:credential/encrypted-data cred)))
          (is (= "user-1" (get-in cred [:credential/user :user/id])))))

      (db/stop-db! conn))))

(deftest user-data-isolation-test
  (testing "can distinguish between different users' data"
    (let [conn (db/start-db! test-db-path)]
      ;; Create two users
      (d/transact! conn [{:user/id "user-1"
                         :user/email "user1@example.com"
                         :user/created-at (java.util.Date.)}
                        {:user/id "user-2"
                         :user/email "user2@example.com"
                         :user/created-at (java.util.Date.)}])

      (let [user1-eid (d/entid @conn [:user/id "user-1"])
            user2-eid (d/entid @conn [:user/id "user-2"])]

        ;; Create institution
        (d/transact! conn [{:institution/id "inst-1"
                           :institution/name "Test Bank"}])

        (let [inst-eid (d/entid @conn [:institution/id "inst-1"])]

          ;; Create accounts for each user
          (d/transact! conn [{:account/external-id "user1-acct"
                             :account/external-name "User 1 Account"
                             :account/institution inst-eid
                             :account/user user1-eid
                             :account/currency "USD"
                             :account/type :checking}
                            {:account/external-id "user2-acct"
                             :account/external-name "User 2 Account"
                             :account/institution inst-eid
                             :account/user user2-eid
                             :account/currency "USD"
                             :account/type :checking}])

          ;; Query user 1's accounts
          (let [user1-accounts (d/q '[:find [(pull ?acct [*]) ...]
                                      :in $ ?user-eid
                                      :where
                                      [?acct :account/user ?user-eid]]
                                   @conn
                                   user1-eid)]
            (is (= 1 (count user1-accounts)))
            (is (= "User 1 Account" (:account/external-name (first user1-accounts)))))

          ;; Query user 2's accounts
          (let [user2-accounts (d/q '[:find [(pull ?acct [*]) ...]
                                      :in $ ?user-eid
                                      :where
                                      [?acct :account/user ?user-eid]]
                                   @conn
                                   user2-eid)]
            (is (= 1 (count user2-accounts)))
            (is (= "User 2 Account" (:account/external-name (first user2-accounts)))))))

      (db/stop-db! conn))))

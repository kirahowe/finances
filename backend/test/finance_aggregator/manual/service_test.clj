(ns finance-aggregator.manual.service-test
  "Integration tests for manual account service functions.
   Uses temporary database for each test."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datalevin.core :as d]
   [finance-aggregator.manual.service :as service]
   [finance-aggregator.test-utils.setup :as setup]))

(use-fixtures :each setup/with-empty-db)

(defn- create-test-user!
  "Helper to create test user entity."
  []
  (d/transact! setup/*test-conn* [{:user/id "test-user"
                                   :user/created-at (java.util.Date.)}]))

(deftest test-create-account
  (testing "Creates manual account with auto-generated institution"
    (create-test-user!)
    (let [account-data {:name "TD Chequing"
                       :currency "CAD"
                       :institution-name "TD Bank"}
          result (service/create-account! setup/*test-conn* account-data)]
      (is (:success result))
      (is (:data result))
      (let [account (:data result)]
        (is (= "TD Chequing" (:account/external-name account)))
        (is (= "CAD" (:account/currency account)))
        (is (= :manual (:account/source account)))
        (is (:account/external-id account))
        (is (.startsWith (:account/external-id account) "manual-"))
        (is (:db/id account)))))

  (testing "Creates institution if it doesn't exist"
    (create-test-user!)
    (let [account-data {:name "Cash Account"
                       :institution-name "Manual"}
          result (service/create-account! setup/*test-conn* account-data)
          db (d/db setup/*test-conn*)
          institution (d/pull db '[:institution/name] [:institution/id "manual-manual"])]
      (is (:success result))
      (is (= "Manual" (:institution/name institution)))))

  (testing "Defaults to USD when currency not provided"
    (create-test-user!)
    (let [account-data {:name "Cash"
                       :institution-name "Manual"}
          result (service/create-account! setup/*test-conn* account-data)
          account (:data result)]
      (is (= "USD" (:account/currency account))))))

(deftest test-delete-account
  (testing "Deletes manual account and cascades transactions"
    (create-test-user!)
    ;; Create account
    (let [account-result (service/create-account! setup/*test-conn*
                                                  {:name "Test Account"
                                                   :institution-name "Test"})
          account (:data account-result)
          account-id (:account/external-id account)]
      ;; Add some transactions
      (d/transact! setup/*test-conn*
                   [{:transaction/external-id "csv-test-1"
                     :transaction/account [:account/external-id account-id]
                     :transaction/user [:user/id "test-user"]
                     :transaction/date (java.util.Date.)
                     :transaction/posted-date (java.util.Date.)
                     :transaction/amount (bigdec "100.00")
                     :transaction/payee "Test Payee"}
                    {:transaction/external-id "csv-test-2"
                     :transaction/account [:account/external-id account-id]
                     :transaction/user [:user/id "test-user"]
                     :transaction/date (java.util.Date.)
                     :transaction/posted-date (java.util.Date.)
                     :transaction/amount (bigdec "50.00")
                     :transaction/payee "Another Payee"}])

      ;; Verify transactions exist
      (let [db (d/db setup/*test-conn*)
            tx-count (d/q '[:find (count ?tx) .
                           :in $ ?acct
                           :where
                           [?tx :transaction/account ?acct]]
                         db
                         (:db/id account))]
        (is (= 2 tx-count)))

      ;; Delete account
      (let [delete-result (service/delete-account! setup/*test-conn* account-id)]
        (is (:success delete-result))
        (is (= 2 (:deleted-transactions delete-result))))

      ;; Verify account and transactions are gone
      (let [db (d/db setup/*test-conn*)
            account-check (d/pull db '[:db/id] [:account/external-id account-id])
            tx-count (or (d/q '[:find (count ?tx) .
                               :where
                               [?tx :transaction/external-id ?id]
                               [(clojure.string/starts-with? ?id "csv-test")]]
                             db)
                        0)]
        (is (nil? (:db/id account-check)) "Account should be deleted")
        (is (= 0 tx-count) "Transactions should be deleted"))))

  (testing "Returns error when account not found"
    (let [result (service/delete-account! setup/*test-conn* "nonexistent-id")]
      (is (not (:success result)))
      (is (= "Account not found" (:error result)))))

  (testing "Prevents deletion of non-manual accounts"
    (create-test-user!)
    ;; Create a Plaid account (by manually inserting with :plaid source)
    (d/transact! setup/*test-conn*
                 [{:institution/id "ins_test"
                   :institution/name "Test Bank"}])
    (d/transact! setup/*test-conn*
                 [{:account/external-id "plaid-account-123"
                   :account/external-name "Plaid Account"
                   :account/source :plaid
                   :account/currency "USD"
                   :account/institution [:institution/id "ins_test"]
                   :account/user [:user/id "test-user"]}])

    (let [result (service/delete-account! setup/*test-conn* "plaid-account-123")]
      (is (not (:success result)))
      (is (= "Cannot delete non-manual account" (:error result))))))

(deftest test-update-account-settings
  (testing "Updates invert-amount setting"
    (create-test-user!)
    ;; Create account
    (let [account-result (service/create-account! setup/*test-conn*
                                                  {:name "Test Account"
                                                   :institution-name "Test"})
          account (:data account-result)
          account-db-id (:db/id account)]

      ;; Update settings
      (let [update-result (service/update-account-settings!
                           setup/*test-conn*
                           account-db-id
                           {:invert-amount true})]
        (is (:success update-result)))

      ;; Verify setting was updated
      (let [db (d/db setup/*test-conn*)
            updated-account (d/pull db '[:account/invert-amount] account-db-id)]
        (is (true? (:account/invert-amount updated-account))))))

  (testing "Returns error when account not found"
    (let [result (service/update-account-settings!
                  setup/*test-conn*
                  999999
                  {:invert-amount true})]
      (is (not (:success result)))
      (is (= "Account not found" (:error result))))))

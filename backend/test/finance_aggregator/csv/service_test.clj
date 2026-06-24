(ns finance-aggregator.csv.service-test
  "Integration tests for CSV import service functions.
   Uses temporary database for each test."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datalevin.core :as d]
   [finance-aggregator.csv.service :as csv-service]
   [finance-aggregator.manual.service :as manual-service]
   [finance-aggregator.test-utils.setup :as setup]))

(use-fixtures :each setup/with-empty-db)

(defn- create-test-user!
  "Helper to create test user entity."
  []
  (d/transact! setup/*test-conn* [{:user/id "test-user"
                                   :user/created-at (java.util.Date.)}]))

(deftest test-save-and-get-csv-mapping
  (testing "Saves and retrieves CSV mapping configuration"
    (create-test-user!)
    ;; Create account
    (let [account-result (manual-service/create-account!
                          setup/*test-conn*
                          {:name "Test Account"
                           :institution-name "Test"})
          account (:data account-result)
          account-db-id (:db/id account)
          mapping {:columns {:date "Date"
                            :amount "Amount"
                            :payee "Description"
                            :description "Memo"}
                   :date-format "yyyy-MM-dd"}]

      ;; Save mapping
      (let [save-result (csv-service/save-csv-mapping!
                         setup/*test-conn*
                         account-db-id
                         mapping)]
        (is (:success save-result)))

      ;; Get mapping
      (let [get-result (csv-service/get-csv-mapping
                        setup/*test-conn*
                        account-db-id)]
        (is (:success get-result))
        (is (= mapping (:data get-result)))))))

(deftest test-preview-csv-import
  (testing "Previews CSV with column detection"
    (create-test-user!)
    ;; Create account
    (let [account-result (manual-service/create-account!
                          setup/*test-conn*
                          {:name "Test Account"
                           :institution-name "Test"})
          account (:data account-result)
          account-db-id (:db/id account)
          csv-content "Date,Description,Amount\n2024-01-15,Coffee Shop,4.50\n2024-01-16,Grocery Store,45.67"
          ;; Preview CSV
          preview-result (csv-service/preview-csv-import
                          setup/*test-conn*
                          account-db-id
                          csv-content)]
      (is (:success preview-result))
      (let [data (:data preview-result)]
        (is (= ["Date" "Description" "Amount"] (:headers data)))
        (is (= 2 (:total-rows data)))
        (is (= 2 (count (:sample-rows data))))
        (is (some? (:detected-mapping data)))
        (is (= "Date" (get-in data [:detected-mapping :date])))
        (is (= "Amount" (get-in data [:detected-mapping :amount])))
        (is (= "Description" (get-in data [:detected-mapping :payee])))
        (is (some? (:suggested-date-format data)))))))

(deftest test-import-csv-transactions
  (testing "Imports transactions from CSV"
    (create-test-user!)
    ;; Create account
    (let [account-result (manual-service/create-account!
                          setup/*test-conn*
                          {:name "Test Account"
                           :institution-name "Test"})
          account (:data account-result)
          account-id (:account/external-id account)
          csv-content "Date,Description,Amount\n2024-01-15,Coffee Shop,4.50\n2024-01-16,Grocery Store,45.67"
          mapping {:columns {:date "Date"
                            :amount "Amount"
                            :payee "Description"}
                   :date-format "yyyy-MM-dd"}]

      ;; Import CSV
      (let [import-result (csv-service/import-csv-transactions!
                           setup/*test-conn*
                           account-id
                           csv-content
                           mapping)]
        (is (:success import-result))
        (let [data (:data import-result)]
          (is (= 2 (:imported data)))
          (is (= 0 (:skipped-duplicates data)))
          (is (empty? (:errors data)))))

      ;; Verify transactions exist
      (let [db (d/db setup/*test-conn*)
            tx-count (d/q '[:find (count ?tx) .
                           :in $ ?acct-id
                           :where
                           [?acct :account/external-id ?acct-id]
                           [?tx :transaction/account ?acct]]
                         db
                         account-id)]
        (is (= 2 tx-count)))))

  (testing "Re-importing same CSV skips duplicates"
    (create-test-user!)
    ;; Create account
    (let [account-result (manual-service/create-account!
                          setup/*test-conn*
                          {:name "Test Account"
                           :institution-name "Test"})
          account (:data account-result)
          account-id (:account/external-id account)
          csv-content "Date,Description,Amount\n2024-01-15,Coffee Shop,4.50"
          mapping {:columns {:date "Date"
                            :amount "Amount"
                            :payee "Description"}
                   :date-format "yyyy-MM-dd"}]

      ;; First import
      (csv-service/import-csv-transactions!
       setup/*test-conn*
       account-id
       csv-content
       mapping)

      ;; Second import (same CSV)
      (let [import-result (csv-service/import-csv-transactions!
                           setup/*test-conn*
                           account-id
                           csv-content
                           mapping)]
        (is (:success import-result))
        ;; Should still report 1 imported (upsert)
        (is (= 1 (get-in import-result [:data :imported]))))

      ;; Verify only 1 transaction exists (no duplicates)
      (let [db (d/db setup/*test-conn*)
            tx-count (d/q '[:find (count ?tx) .
                           :in $ ?acct-id
                           :where
                           [?acct :account/external-id ?acct-id]
                           [?tx :transaction/account ?acct]]
                         db
                         account-id)]
        (is (= 1 tx-count))))))

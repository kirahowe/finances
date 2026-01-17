(ns finance-aggregator.db.credentials-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datalevin.core :as d]
   [finance-aggregator.db.credentials :as credentials]
   [finance-aggregator.lib.encryption :as encryption]
   [finance-aggregator.test-utils.setup :as setup]))

(use-fixtures :each setup/with-empty-db)

;; Test secrets data with encryption key
(defn- make-test-secrets
  "Create test secrets data with a fresh encryption key"
  []
  {:database {:encryption-key (encryption/generate-encryption-key)}
   :plaid {:client-id "test-client-id"
           :secret "test-secret"
           :environment :sandbox}})

(deftest store-credential-test
  (testing "Stores encrypted credential in database"
    (let [secrets-data (make-test-secrets)
          access-token "access-sandbox-xyz123"
          result (credentials/store-credential! setup/*test-conn* secrets-data :plaid access-token)]

      (is (some? (:db/id result)) "Should return entity with db/id")
      (is (some? (:credential/id result)))
      (is (= :plaid (:credential/institution result)))
      (is (some? (:credential/encrypted-data result)))
      (is (some? (:credential/created-at result)))

      ;; Verify encrypted-data is a string (EDN-encoded)
      (is (string? (:credential/encrypted-data result)))

      ;; Verify it contains encrypted data structure
      (let [encrypted-data (read-string (:credential/encrypted-data result))]
        (is (map? encrypted-data))
        (is (contains? encrypted-data :ciphertext))
        (is (contains? encrypted-data :iv)))))

  (testing "Creates test user if it doesn't exist"
    (let [secrets-data (make-test-secrets)
          _ (credentials/store-credential! setup/*test-conn* secrets-data :plaid "token")
          db (d/db setup/*test-conn*)
          user (d/entity db [:user/id "test-user"])]

      (is (some? user))
      (is (= "test-user" (:user/id user)))
      (is (some? (:user/created-at user)))))

  (testing "Throws exception for non-keyword institution"
    (let [secrets-data (make-test-secrets)]
      (is (thrown? Exception
                   (credentials/store-credential! setup/*test-conn* secrets-data "plaid" "token")))))

  (testing "Throws exception when encryption key missing from secrets"
    (let [secrets-data {:plaid {:client-id "test"}}]  ; Missing :database/:encryption-key
      (is (thrown? Exception
                   (credentials/store-credential! setup/*test-conn* secrets-data :plaid "token"))))))

(deftest get-credential-retrieves-stored-test
  (testing "Retrieves and decrypts stored credential"
    (let [secrets-data (make-test-secrets)
          access-token "access-sandbox-abc123"
          _ (credentials/store-credential! setup/*test-conn* secrets-data :plaid access-token)
          retrieved (credentials/get-credential setup/*test-conn* secrets-data :plaid)]

      (is (= access-token retrieved)))))

(deftest get-credential-returns-nil-when-missing-test
  (testing "Returns nil when credential doesn't exist"
    (let [secrets-data (make-test-secrets)
          retrieved (credentials/get-credential setup/*test-conn* secrets-data :plaid)]

      (is (nil? retrieved)))))

(deftest get-credential-updates-last-used-test
  (testing "Updates last-used timestamp when credential retrieved"
    (let [secrets-data (make-test-secrets)
          _ (credentials/store-credential! setup/*test-conn* secrets-data :plaid "token")
          _ (Thread/sleep 100)  ; Ensure timestamp difference
          _ (credentials/get-credential setup/*test-conn* secrets-data :plaid)
          db (d/db setup/*test-conn*)
          cred (d/q '[:find (pull ?e [*]) .
                      :where [?e :credential/institution :plaid]]
                    db)]

      (is (some? (:credential/last-used cred))))))

(deftest get-credential-retrieves-most-recent-test
  (testing "Retrieves most recent credential when multiple exist"
    (let [secrets-data (make-test-secrets)
          _ (credentials/store-credential! setup/*test-conn* secrets-data :plaid "old-token")
          _ (Thread/sleep 100)
          _ (credentials/store-credential! setup/*test-conn* secrets-data :plaid "new-token")
          retrieved (credentials/get-credential setup/*test-conn* secrets-data :plaid)]

      (is (= "new-token" retrieved)))))

(deftest get-credential-throws-on-invalid-institution-test
  (testing "Throws exception for non-keyword institution"
    (let [secrets-data (make-test-secrets)]
      (is (thrown? Exception
                   (credentials/get-credential setup/*test-conn* secrets-data "plaid"))))))

(deftest credential-exists-test
  (testing "Returns false when credential doesn't exist"
    (is (false? (credentials/credential-exists? setup/*test-conn* :plaid))))

  (testing "Returns true when credential exists"
    (let [secrets-data (make-test-secrets)]
      (credentials/store-credential! setup/*test-conn* secrets-data :plaid "token")
      (is (true? (credentials/credential-exists? setup/*test-conn* :plaid)))))

  (testing "Throws exception for non-keyword institution"
    (is (thrown? Exception
                 (credentials/credential-exists? setup/*test-conn* "plaid")))))

(deftest delete-credential-test
  (testing "Deletes existing credential"
    (let [secrets-data (make-test-secrets)]
      (credentials/store-credential! setup/*test-conn* secrets-data :plaid "token")
      (is (true? (credentials/credential-exists? setup/*test-conn* :plaid)))

      (let [deleted? (credentials/delete-credential! setup/*test-conn* :plaid)]
        (is (true? deleted?))
        (is (false? (credentials/credential-exists? setup/*test-conn* :plaid))))))

  (testing "Returns false when credential doesn't exist"
    (let [deleted? (credentials/delete-credential! setup/*test-conn* :plaid)]
      (is (nil? deleted?))))

  (testing "Throws exception for non-keyword institution"
    (is (thrown? Exception
                 (credentials/delete-credential! setup/*test-conn* "plaid")))))

(deftest store-multiple-institutions-test
  (testing "Can store credentials for multiple institutions"
    (let [secrets-data (make-test-secrets)]
      (credentials/store-credential! setup/*test-conn* secrets-data :plaid "plaid-token")
      (credentials/store-credential! setup/*test-conn* secrets-data :simplefin "simplefin-token")

      (is (= "plaid-token" (credentials/get-credential setup/*test-conn* secrets-data :plaid)))
      (is (= "simplefin-token" (credentials/get-credential setup/*test-conn* secrets-data :simplefin))))))

(deftest encryption-roundtrip-integration-test
  (testing "Full encryption/decryption roundtrip through database"
    (let [secrets-data (make-test-secrets)
          original-tokens ["access-sandbox-abc123"
                           "access-development-xyz789"
                           "token-with-special-chars-!@#$%^&*()"
                           "very-long-token-"]]

      (doseq [token original-tokens]
        ;; Store and retrieve
        (credentials/store-credential! setup/*test-conn* secrets-data :plaid token)
        (let [retrieved (credentials/get-credential setup/*test-conn* secrets-data :plaid)]
          (is (= token retrieved) (str "Failed roundtrip for: " token)))

        ;; Clean up for next iteration
        (credentials/delete-credential! setup/*test-conn* :plaid)))))

;;; Sync Cursor Tests

(deftest get-sync-cursor-returns-nil-when-no-cursor-test
  (testing "Returns nil when item has no cursor stored"
    (let [secrets-data (make-test-secrets)
          item-id "item_test123"]
      ;; Store a credential without a cursor
      (credentials/store-plaid-item-credential!
       setup/*test-conn* secrets-data "access-token" item-id "Test Bank")

      ;; Get cursor should return nil
      (is (nil? (credentials/get-sync-cursor setup/*test-conn* item-id))))))

(deftest get-sync-cursor-returns-nil-for-nonexistent-item-test
  (testing "Returns nil when item doesn't exist"
    (is (nil? (credentials/get-sync-cursor setup/*test-conn* "nonexistent-item")))))

(deftest update-sync-cursor-stores-cursor-test
  (testing "Stores cursor for existing Plaid Item"
    (let [secrets-data (make-test-secrets)
          item-id "item_cursor123"
          cursor "cursor_abc123xyz"]
      ;; Create the credential first
      (credentials/store-plaid-item-credential!
       setup/*test-conn* secrets-data "access-token" item-id "Test Bank")

      ;; Update cursor
      (let [result (credentials/update-sync-cursor! setup/*test-conn* item-id cursor)]
        (is (true? result)))

      ;; Verify cursor was stored
      (is (= cursor (credentials/get-sync-cursor setup/*test-conn* item-id))))))

(deftest update-sync-cursor-returns-false-for-nonexistent-item-test
  (testing "Returns false when item doesn't exist"
    (let [result (credentials/update-sync-cursor! setup/*test-conn* "nonexistent-item" "cursor")]
      (is (false? result)))))

(deftest update-sync-cursor-overwrites-existing-test
  (testing "Overwrites existing cursor"
    (let [secrets-data (make-test-secrets)
          item-id "item_overwrite"
          cursor1 "cursor_first"
          cursor2 "cursor_second"]
      ;; Create credential and set initial cursor
      (credentials/store-plaid-item-credential!
       setup/*test-conn* secrets-data "access-token" item-id "Test Bank")
      (credentials/update-sync-cursor! setup/*test-conn* item-id cursor1)

      (is (= cursor1 (credentials/get-sync-cursor setup/*test-conn* item-id)))

      ;; Update to new cursor
      (credentials/update-sync-cursor! setup/*test-conn* item-id cursor2)

      (is (= cursor2 (credentials/get-sync-cursor setup/*test-conn* item-id))))))

(deftest multiple-items-have-independent-cursors-test
  (testing "Each Plaid Item has its own cursor"
    (let [secrets-data (make-test-secrets)
          item1-id "item_independent1"
          item2-id "item_independent2"
          cursor1 "cursor_for_item1"
          cursor2 "cursor_for_item2"]
      ;; Create two credentials
      (credentials/store-plaid-item-credential!
       setup/*test-conn* secrets-data "access-token-1" item1-id "Bank One")
      (credentials/store-plaid-item-credential!
       setup/*test-conn* secrets-data "access-token-2" item2-id "Bank Two")

      ;; Set different cursors for each
      (credentials/update-sync-cursor! setup/*test-conn* item1-id cursor1)
      (credentials/update-sync-cursor! setup/*test-conn* item2-id cursor2)

      ;; Verify each has its own cursor
      (is (= cursor1 (credentials/get-sync-cursor setup/*test-conn* item1-id)))
      (is (= cursor2 (credentials/get-sync-cursor setup/*test-conn* item2-id))))))

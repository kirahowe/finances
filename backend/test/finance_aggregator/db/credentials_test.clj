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

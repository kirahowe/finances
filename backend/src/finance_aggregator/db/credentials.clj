(ns finance-aggregator.db.credentials
  "Database operations for encrypted credential storage.

   Provides functions to securely store and retrieve API access tokens
   (e.g., Plaid access_token) with AES-256-GCM encryption.

   For Phase 2, all operations use hardcoded user-id 'test-user'.
   Multi-user support will be added in later phase."
  (:require
   [datalevin.core :as d]
   [finance-aggregator.lib.encryption :as encryption]
   [finance-aggregator.lib.secrets :as secrets]))

(def ^:private hardcoded-user-id
  "Hardcoded user ID for Phase 2/3 testing. Will be removed in Phase 7."
  "test-user")

(defn- get-encryption-key
  "Get the encryption key from secrets.
   Secrets should contain {:database {:encryption-key \"base64-encoded-key\"}}"
  [secrets-data]
  (if-let [key (secrets/get-secret secrets-data [:database :encryption-key])]
    key
    (throw (ex-info "Database encryption key not found in secrets"
                    {:available-keys (keys secrets-data)
                     :hint "Run 'bb secrets edit' and add {:database {:encryption-key \"...\"}}"}))))

(defn- ensure-user-exists!
  "Ensure the hardcoded test user exists in the database.
   Creates the user if it doesn't exist."
  [conn]
  (let [db (d/db conn)
        user-exists? (d/entity db [:user/id hardcoded-user-id])]
    (when-not user-exists?
      (println (str "Creating hardcoded test user: " hardcoded-user-id))
      (d/transact! conn [{:user/id hardcoded-user-id
                          :user/created-at (java.util.Date.)}])
      (println "Test user created"))))

(defn store-credential!
  "Store an encrypted credential in the database.

   Parameters:
   - conn: Datalevin connection
   - secrets-data: Loaded secrets map (contains encryption key)
   - institution: Keyword identifying the institution (e.g., :plaid, :simplefin)
   - access-token: Plaintext access token to encrypt and store

   Returns:
   The created credential entity with :db/id

   Example:
   (store-credential! conn secrets-data :plaid \"access-sandbox-xyz\")"
  [conn secrets-data institution access-token]
  (when-not (keyword? institution)
    (throw (ex-info "Institution must be a keyword"
                    {:institution institution
                     :type (type institution)})))

  ;; Ensure test user exists
  (ensure-user-exists! conn)

  ;; Get encryption key and encrypt the token
  (let [encryption-key (get-encryption-key secrets-data)
        encrypted-data (encryption/encrypt-credential access-token encryption-key)
        ;; Store encrypted data as EDN string (contains :ciphertext and :iv)
        encrypted-data-str (pr-str encrypted-data)
        credential-id (str "cred-" (name institution) "-" (java.util.UUID/randomUUID))
        credential {:credential/id credential-id
                    :credential/user [:user/id hardcoded-user-id]
                    :credential/institution institution
                    :credential/encrypted-data encrypted-data-str
                    :credential/created-at (java.util.Date.)}]

    ;; Insert credential
    (d/transact! conn [credential])

    ;; Return the entity with :db/id
    (let [db (d/db conn)]
      (d/pull db '[*] [:credential/id credential-id]))))

(defn get-credential
  "Retrieve and decrypt a credential from the database.

   Parameters:
   - conn: Datalevin connection
   - secrets-data: Loaded secrets map (contains encryption key)
   - institution: Keyword identifying the institution (e.g., :plaid)

   Returns:
   Decrypted access token as string, or nil if not found

   Example:
   (get-credential conn secrets-data :plaid)"
  [conn secrets-data institution]
  (when-not (keyword? institution)
    (throw (ex-info "Institution must be a keyword"
                    {:institution institution
                     :type (type institution)})))

  ;; Query for credential
  (let [db (d/db conn)
        result (d/q '[:find [(pull ?e [*]) ...]
                      :in $ ?user-id ?institution
                      :where
                      [?u :user/id ?user-id]
                      [?e :credential/user ?u]
                      [?e :credential/institution ?institution]]
                    db
                    hardcoded-user-id
                    institution)]

    (when (seq result)
      ;; Get the most recent credential (by created-at)
      (let [credential (first (sort-by :credential/created-at #(compare %2 %1) result))
            encrypted-data-str (:credential/encrypted-data credential)
            encrypted-data (read-string encrypted-data-str)
            encryption-key (get-encryption-key secrets-data)]

        ;; Update last-used timestamp
        (d/transact! conn [{:db/id (:db/id credential)
                            :credential/last-used (java.util.Date.)}])

        ;; Decrypt and return
        (encryption/decrypt-credential encrypted-data encryption-key)))))

(defn credential-exists?
  "Check if a credential exists for the given institution.

   Parameters:
   - conn: Datalevin connection
   - institution: Keyword identifying the institution (e.g., :plaid)

   Returns:
   Boolean indicating whether credential exists"
  [conn institution]
  (when-not (keyword? institution)
    (throw (ex-info "Institution must be a keyword"
                    {:institution institution
                     :type (type institution)})))

  (let [db (d/db conn)
        result (d/q '[:find ?e .
                      :in $ ?user-id ?institution
                      :where
                      [?u :user/id ?user-id]
                      [?e :credential/user ?u]
                      [?e :credential/institution ?institution]]
                    db
                    hardcoded-user-id
                    institution)]
    (some? result)))

(defn delete-credential!
  "Delete a credential from the database.

   Parameters:
   - conn: Datalevin connection
   - institution: Keyword identifying the institution (e.g., :plaid)

   Returns:
   True if credential was deleted, false if not found"
  [conn institution]
  (when-not (keyword? institution)
    (throw (ex-info "Institution must be a keyword"
                    {:institution institution
                     :type (type institution)})))

  (let [db (d/db conn)
        credentials (d/q '[:find [?e ...]
                           :in $ ?user-id ?institution
                           :where
                           [?u :user/id ?user-id]
                           [?e :credential/user ?u]
                           [?e :credential/institution ?institution]]
                         db
                         hardcoded-user-id
                         institution)]

    (when (seq credentials)
      ;; Delete all matching credentials (should only be one, but just in case)
      (d/transact! conn (mapv (fn [eid] [:db/retractEntity eid]) credentials))
      true)))

(comment
  ;; Example usage in REPL

  (require '[finance-aggregator.db :as db])
  (require '[finance-aggregator.lib.secrets :as secrets])
  (require '[finance-aggregator.db.credentials :as creds])

  ;; Load secrets
  (def secrets-data (secrets/load-secrets))

  ;; Store a Plaid credential
  (creds/store-credential! db/conn secrets-data :plaid "access-sandbox-xyz123")
  ;; => {:db/id 123, :credential/id "cred-plaid-...", ...}

  ;; Retrieve the credential
  (creds/get-credential db/conn secrets-data :plaid)
  ;; => "access-sandbox-xyz123"

  ;; Check if credential exists
  (creds/credential-exists? db/conn :plaid)
  ;; => true

  ;; Delete credential
  (creds/delete-credential! db/conn :plaid)
  ;; => true
  )

(ns finance-aggregator.db.credentials
  "Database operations for encrypted credential storage.

   Provides functions to securely store and retrieve API access tokens
   (e.g., Plaid access_token) with AES-256-GCM encryption.

   Supports multiple Plaid Items (bank connections) per user via:
   - store-plaid-item-credential! - Store credential with item-id and institution-name
   - get-plaid-item-credential - Get credential by item-id
   - get-all-plaid-credentials - Get all Plaid credentials for user
   - delete-plaid-item-credential! - Delete credential by item-id

   For Phase 2/3, all operations use hardcoded user-id 'test-user'.
   Multi-user support will be added in Phase 7."
  (:require
   [clojure.data.json :as json]
   [datalevin.core :as d]
   [finance-aggregator.auth :as auth]
   [finance-aggregator.lib.encryption :as encryption]
   [finance-aggregator.lib.log :as log]
   [finance-aggregator.lib.secrets :as secrets]))

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
        user-exists? (d/entity db [:user/id auth/user-id])]
    (when-not user-exists?
      (log/debug "Creating hardcoded test user" {:user-id auth/user-id})
      (d/transact! conn [{:user/id auth/user-id
                          :user/created-at (java.util.Date.)}])
      (log/debug "Test user created" {:user-id auth/user-id}))))

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
                    :credential/user [:user/id auth/user-id]
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
                    auth/user-id
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
                    auth/user-id
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
                         auth/user-id
                         institution)]

    (when (seq credentials)
      ;; Delete all matching credentials (should only be one, but just in case)
      (d/transact! conn (mapv (fn [eid] [:db/retractEntity eid]) credentials))
      true)))

;;; Multi-Item Credential Functions (for multiple Plaid Items per user)

(defn store-plaid-item-credential!
  "Store an encrypted Plaid Item credential in the database.

   Each Plaid Item represents a connection to one bank and has its own
   access_token and item_id. This function stores credentials keyed by
   item_id to support multiple bank connections.

   Parameters:
   - conn: Datalevin connection
   - secrets-data: Loaded secrets map (contains encryption key)
   - access-token: Plaid access_token to encrypt and store
   - item-id: Plaid item_id (unique identifier for the bank connection)
   - institution-name: Human-readable institution name (e.g., 'Chase Bank')
   - selected-account-ids: Vector of Plaid account IDs user selected (optional)

   Returns:
   The created credential entity with :db/id

   Example:
   (store-plaid-item-credential! conn secrets-data
     \"access-sandbox-xyz\" \"item_abc123\" \"Chase Bank\" [\"acc_1\" \"acc_2\"])"
  ([conn secrets-data access-token item-id institution-name]
   (store-plaid-item-credential! conn secrets-data access-token item-id institution-name nil))
  ([conn secrets-data access-token item-id institution-name selected-account-ids]
   ;; Ensure test user exists
   (ensure-user-exists! conn)

   ;; Get encryption key and encrypt the token
   (let [encryption-key (get-encryption-key secrets-data)
         encrypted-data (encryption/encrypt-credential access-token encryption-key)
         encrypted-data-str (pr-str encrypted-data)
         ;; Use item-id as the credential ID for easy lookup
         credential-id (str "plaid-item-" item-id)
         credential (cond-> {:credential/id credential-id
                             :credential/user [:user/id auth/user-id]
                             :credential/institution :plaid
                             :credential/item-id item-id
                             :credential/institution-name institution-name
                             :credential/encrypted-data encrypted-data-str
                             :credential/created-at (java.util.Date.)
                             :credential/sync-status :pending}
                      ;; Add selected account IDs if provided
                      (seq selected-account-ids)
                      (assoc :credential/selected-account-ids (json/write-str selected-account-ids)))]

     (log/info "Storing Plaid Item credential"
               {:item-id item-id
                :institution-name institution-name
                :selected-accounts (count (or selected-account-ids []))})

     ;; Upsert credential (replace if item-id already exists)
     (d/transact! conn [credential])

     ;; Return the entity with :db/id
     (let [db (d/db conn)]
       (d/pull db '[*] [:credential/id credential-id])))))

(defn get-plaid-item-credential
  "Retrieve and decrypt a Plaid Item credential by item-id.

   Parameters:
   - conn: Datalevin connection
   - secrets-data: Loaded secrets map (contains encryption key)
   - item-id: Plaid item_id

   Returns:
   Decrypted access token as string, or nil if not found

   Example:
   (get-plaid-item-credential conn secrets-data \"item_abc123\")"
  [conn secrets-data item-id]
  (let [db (d/db conn)
        credential-id (str "plaid-item-" item-id)
        credential (d/pull db '[*] [:credential/id credential-id])]

    (when (:credential/encrypted-data credential)
      (let [encrypted-data-str (:credential/encrypted-data credential)
            encrypted-data (read-string encrypted-data-str)
            encryption-key (get-encryption-key secrets-data)]

        ;; Update last-used timestamp
        (d/transact! conn [{:db/id (:db/id credential)
                            :credential/last-used (java.util.Date.)}])

        ;; Decrypt and return
        (encryption/decrypt-credential encrypted-data encryption-key)))))

(defn get-all-plaid-credentials
  "Get all Plaid Item credentials for the user.

   Returns credentials as a vector of maps with decrypted access tokens.
   Each map contains:
   - :item-id - Plaid item_id
   - :institution-name - Human-readable institution name
   - :access-token - Decrypted access token
   - :created-at - When the credential was created
   - :last-used - When the credential was last used

   Parameters:
   - conn: Datalevin connection
   - secrets-data: Loaded secrets map (contains encryption key)

   Returns:
   Vector of credential maps, empty vector if none found

   Example:
   (get-all-plaid-credentials conn secrets-data)
   ;; => [{:item-id \"item_abc\" :institution-name \"Chase\" :access-token \"access-xyz\" ...}]"
  [conn secrets-data]
  (let [db (d/db conn)
        credentials (d/q '[:find [(pull ?e [*]) ...]
                           :in $ ?user-id
                           :where
                           [?u :user/id ?user-id]
                           [?e :credential/user ?u]
                           [?e :credential/institution :plaid]
                           [?e :credential/item-id _]]  ; Only items with item-id
                         db
                         auth/user-id)
        encryption-key (get-encryption-key secrets-data)]

    (mapv (fn [cred]
            (let [encrypted-data (read-string (:credential/encrypted-data cred))]
              {:item-id (:credential/item-id cred)
               :institution-name (:credential/institution-name cred)
               :access-token (encryption/decrypt-credential encrypted-data encryption-key)
               :created-at (:credential/created-at cred)
               :last-used (:credential/last-used cred)}))
          credentials)))

(defn delete-plaid-item-credential!
  "Delete a Plaid Item credential by item-id.

   Parameters:
   - conn: Datalevin connection
   - item-id: Plaid item_id

   Returns:
   True if credential was deleted, false if not found

   Example:
   (delete-plaid-item-credential! conn \"item_abc123\")"
  [conn item-id]
  (let [db (d/db conn)
        credential-id (str "plaid-item-" item-id)
        credential (d/pull db '[:db/id] [:credential/id credential-id])]

    (if (:db/id credential)
      (do
        (log/info "Deleting Plaid Item credential" {:item-id item-id})
        (d/transact! conn [[:db/retractEntity (:db/id credential)]])
        true)
      false)))

(defn list-plaid-items
  "List all Plaid Items for the user (without decrypting tokens).

   Returns a vector of maps with item metadata (safe to return to frontend):
   - :item-id - Plaid item_id
   - :institution-name - Human-readable institution name
   - :created-at - When the item was linked

   Parameters:
   - conn: Datalevin connection

   Returns:
   Vector of item metadata maps

   Example:
   (list-plaid-items conn)
   ;; => [{:item-id \"item_abc\" :institution-name \"Chase\" :created-at #inst \"...\"}]"
  [conn]
  (let [db (d/db conn)
        credentials (d/q '[:find [(pull ?e [:credential/item-id
                                            :credential/institution-name
                                            :credential/created-at]) ...]
                           :in $ ?user-id
                           :where
                           [?u :user/id ?user-id]
                           [?e :credential/user ?u]
                           [?e :credential/institution :plaid]
                           [?e :credential/item-id _]]
                         db
                         auth/user-id)]
    (mapv (fn [cred]
            {:item-id (:credential/item-id cred)
             :institution-name (:credential/institution-name cred)
             :created-at (:credential/created-at cred)})
          credentials)))

;;; Sync Cursor Functions (for /transactions/sync incremental updates)

(defn get-sync-cursor
  "Get the current sync cursor for a Plaid Item.

   The cursor tracks the position in the /transactions/sync update stream.
   Returns nil if:
   - The item doesn't exist
   - No sync has occurred yet (cursor not set)

   Parameters:
   - conn: Datalevin connection
   - item-id: Plaid item_id

   Returns:
   Cursor string, or nil if not found or never synced

   Example:
   (get-sync-cursor conn \"item_abc123\")
   ;; => \"cursor_xyz789\" or nil"
  [conn item-id]
  (let [db (d/db conn)
        credential-id (str "plaid-item-" item-id)
        credential (d/pull db '[:credential/sync-cursor] [:credential/id credential-id])]
    (:credential/sync-cursor credential)))

(defn update-sync-cursor!
  "Update the sync cursor for a Plaid Item after successful sync.

   Call this after successfully persisting transactions from /transactions/sync
   to record the cursor position for the next incremental sync.

   Parameters:
   - conn: Datalevin connection
   - item-id: Plaid item_id
   - cursor: New cursor string from /transactions/sync response

   Returns:
   True if updated, false if item not found

   Example:
   (update-sync-cursor! conn \"item_abc123\" \"cursor_xyz789\")"
  [conn item-id cursor]
  (let [db (d/db conn)
        credential-id (str "plaid-item-" item-id)
        credential (d/pull db '[:db/id] [:credential/id credential-id])]
    (if (:db/id credential)
      (do
        (d/transact! conn [{:db/id (:db/id credential)
                            :credential/sync-cursor cursor}])
        true)
      false)))

;;; Sync Status Functions (for frontend polling during initial sync)

(defn get-sync-status
  "Get the sync status for a Plaid Item.

   Returns a map with sync status fields for frontend polling.

   Parameters:
   - conn: Datalevin connection
   - item-id: Plaid item_id

   Returns:
   {:sync-status keyword (:pending, :syncing, :synced, :failed)
    :transaction-count int
    :last-sync-at instant or nil
    :institution-name string}
   Or nil if item not found

   Example:
   (get-sync-status conn \"item_abc123\")
   ;; => {:sync-status :synced :transaction-count 42 :last-sync-at #inst \"...\"}"
  [conn item-id]
  (let [db (d/db conn)
        credential-id (str "plaid-item-" item-id)
        credential (d/pull db '[:credential/sync-status
                                :credential/transaction-count
                                :credential/last-sync-at
                                :credential/institution-name]
                           [:credential/id credential-id])]
    (when (:credential/sync-status credential)
      {:sync-status (:credential/sync-status credential)
       :transaction-count (or (:credential/transaction-count credential) 0)
       :last-sync-at (:credential/last-sync-at credential)
       :institution-name (:credential/institution-name credential)})))

(defn update-sync-status!
  "Update the sync status for a Plaid Item.

   Parameters:
   - conn: Datalevin connection
   - item-id: Plaid item_id
   - status: Keyword (:pending, :syncing, :synced, :failed)
   - opts: Optional map with:
     - :transaction-count - Number of transactions synced
     - :error - Error message (for :failed status)

   Returns:
   True if updated, false if item not found

   Example:
   (update-sync-status! conn \"item_abc123\" :synced {:transaction-count 42})"
  ([conn item-id status]
   (update-sync-status! conn item-id status {}))
  ([conn item-id status {:keys [transaction-count error]}]
   (let [db (d/db conn)
         credential-id (str "plaid-item-" item-id)
         credential (d/pull db '[:db/id] [:credential/id credential-id])]
     (if (:db/id credential)
       (do
         (d/transact! conn [(cond-> {:db/id (:db/id credential)
                                     :credential/sync-status status}
                              ;; Add last-sync-at when synced
                              (= status :synced)
                              (assoc :credential/last-sync-at (java.util.Date.))
                              ;; Add transaction count if provided
                              transaction-count
                              (assoc :credential/transaction-count transaction-count))])
         true)
       false))))

(defn reset-sync-cursor!
  "Reset the sync cursor for a Plaid Item to enable full re-sync.

   Clears the cursor and resets sync-status to :pending.
   The next sync will fetch full history based on :days-requested in plaid-config.

   Parameters:
   - conn: Datalevin connection
   - item-id: Plaid item_id

   Returns:
   True if reset, false if item not found

   Example:
   (reset-sync-cursor! conn \"item_abc123\")"
  [conn item-id]
  (let [db (d/db conn)
        credential-id (str "plaid-item-" item-id)
        credential (d/pull db '[:db/id] [:credential/id credential-id])]
    (if (:db/id credential)
      (do
        (log/info "Resetting sync cursor for Plaid Item" {:item-id item-id})
        ;; Retract the cursor (set to nil) and reset status
        (d/transact! conn [[:db/retract (:db/id credential) :credential/sync-cursor]
                           {:db/id (:db/id credential)
                            :credential/sync-status :pending
                            :credential/transaction-count 0}])
        true)
      false)))

(defn get-institution-name
  "Get the institution name for a Plaid Item.

   Parameters:
   - conn: Datalevin connection
   - item-id: Plaid item_id

   Returns:
   Institution name string, or nil if not found

   Example:
   (get-institution-name conn \"item_abc123\")
   ;; => \"Chase Bank\""
  [conn item-id]
  (let [db (d/db conn)
        credential-id (str "plaid-item-" item-id)
        credential (d/pull db '[:credential/institution-name]
                           [:credential/id credential-id])]
    (:credential/institution-name credential)))

(comment
  ;; Example usage in REPL
  ;; Get connection from integrant system (see dev.clj for helpers)

  (require '[finance-aggregator.lib.secrets :as secrets])
  (require '[finance-aggregator.db.credentials :as creds])

  ;; Load secrets
  (def secrets-data (secrets/load-secrets))

  ;; Get database connection from integrant system
  ;; In dev REPL: (dev/db-conn)
  ;; Or manually: (get-in state/system [:finance-aggregator.db/connection :conn])
  (def conn (dev/db-conn))

  ;; Store a Plaid credential
  (creds/store-credential! conn secrets-data :plaid "access-sandbox-xyz123")
  ;; => {:db/id 123, :credential/id "cred-plaid-...", ...}

  ;; Retrieve the credential
  (creds/get-credential conn secrets-data :plaid)
  ;; => "access-sandbox-xyz123"

  ;; Check if credential exists
  (creds/credential-exists? conn :plaid)
  ;; => true

  ;; Delete credential
  (creds/delete-credential! conn :plaid)
  ;; => true
  )

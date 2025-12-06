(ns finance-aggregator.lib.encryption
  "AES-256-GCM encryption utilities for credential storage.

   Pure functions for encrypting/decrypting sensitive data (like access tokens)
   before storing in the database. Uses AES-256-GCM for authenticated encryption.

   The encryption key should be stored in the encrypted secrets file and loaded
   at runtime via the secrets system.

   Usage:
   (def encryption-key (get-encryption-key-from-secrets))
   (def encrypted (encrypt-credential \"access-token-123\" encryption-key))
   (def decrypted (decrypt-credential encrypted encryption-key))"
  (:import
   [javax.crypto Cipher KeyGenerator SecretKey]
   [javax.crypto.spec SecretKeySpec GCMParameterSpec]
   [java.security SecureRandom]
   [java.util Base64]))

(def ^:private gcm-tag-length
  "GCM authentication tag length in bits (128 bits = 16 bytes)"
  128)

(def ^:private iv-length
  "Initialization vector length in bytes (12 bytes recommended for GCM)"
  12)

(defn- generate-iv
  "Generate a random initialization vector (IV) for GCM mode.
   Returns byte array of length iv-length."
  []
  (let [iv (byte-array iv-length)
        random (SecureRandom.)]
    (.nextBytes random iv)
    iv))

(defn- base64-encode
  "Encode byte array to base64 string."
  [^bytes data]
  (.encodeToString (Base64/getEncoder) data))

(defn- base64-decode
  "Decode base64 string to byte array."
  [^String data]
  (.decode (Base64/getDecoder) data))

(defn- key-bytes->secret-key
  "Convert byte array to SecretKey for AES."
  [^bytes key-bytes]
  (SecretKeySpec. key-bytes "AES"))

(defn encrypt-credential
  "Encrypt plaintext using AES-256-GCM with the given encryption key.

   Parameters:
   - plaintext: String to encrypt (e.g., access token)
   - encryption-key: Base64-encoded 256-bit key (32 bytes) as string

   Returns:
   Map with :ciphertext (base64) and :iv (base64)

   Example:
   (encrypt-credential \"access-token-123\" \"base64-encoded-key\")"
  [plaintext encryption-key]
  (when (or (nil? plaintext) (nil? encryption-key))
    (throw (ex-info "Plaintext and encryption-key are required"
                    {:plaintext (some? plaintext)
                     :encryption-key (some? encryption-key)})))

  (try
    (let [key-bytes (base64-decode encryption-key)
          secret-key (key-bytes->secret-key key-bytes)
          iv (generate-iv)
          cipher (Cipher/getInstance "AES/GCM/NoPadding")
          gcm-spec (GCMParameterSpec. gcm-tag-length iv)]

      (.init cipher Cipher/ENCRYPT_MODE secret-key gcm-spec)

      (let [plaintext-bytes (.getBytes plaintext "UTF-8")
            ciphertext-bytes (.doFinal cipher plaintext-bytes)]

        {:ciphertext (base64-encode ciphertext-bytes)
         :iv (base64-encode iv)}))

    (catch Exception e
      (throw (ex-info "Failed to encrypt credential"
                      {:error (.getMessage e)}
                      e)))))

(defn decrypt-credential
  "Decrypt ciphertext using AES-256-GCM with the given encryption key.

   Parameters:
   - encrypted-data: Map with :ciphertext and :iv (both base64 strings)
   - encryption-key: Base64-encoded 256-bit key (32 bytes) as string

   Returns:
   Decrypted plaintext as string

   Example:
   (decrypt-credential {:ciphertext \"...\" :iv \"...\"} \"base64-encoded-key\")"
  [{:keys [ciphertext iv]} encryption-key]
  (when (or (nil? ciphertext) (nil? iv) (nil? encryption-key))
    (throw (ex-info "Ciphertext, IV, and encryption-key are required"
                    {:ciphertext (some? ciphertext)
                     :iv (some? iv)
                     :encryption-key (some? encryption-key)})))

  (try
    (let [key-bytes (base64-decode encryption-key)
          secret-key (key-bytes->secret-key key-bytes)
          iv-bytes (base64-decode iv)
          ciphertext-bytes (base64-decode ciphertext)
          cipher (Cipher/getInstance "AES/GCM/NoPadding")
          gcm-spec (GCMParameterSpec. gcm-tag-length iv-bytes)]

      (.init cipher Cipher/DECRYPT_MODE secret-key gcm-spec)

      (let [plaintext-bytes (.doFinal cipher ciphertext-bytes)]
        (String. plaintext-bytes "UTF-8")))

    (catch Exception e
      (throw (ex-info "Failed to decrypt credential"
                      {:error (.getMessage e)
                       :hint "This may indicate data corruption or wrong encryption key"}
                      e)))))

(defn generate-encryption-key
  "Generate a new random 256-bit (32-byte) encryption key for AES-256.
   Returns base64-encoded key string.

   This function is useful for initial setup. The generated key should be
   stored securely in the encrypted secrets file.

   Example:
   (def new-key (generate-encryption-key))
   ;; Add to secrets.edn: {:database {:encryption-key \"new-key\"}}"
  []
  (let [key-generator (KeyGenerator/getInstance "AES")
        _ (.init key-generator 256)  ; 256-bit key
        secret-key (.generateKey key-generator)
        key-bytes (.getEncoded secret-key)]
    (base64-encode key-bytes)))

(comment
  ;; Example usage in REPL

  ;; Generate a new encryption key (do this once, store in secrets)
  (def encryption-key (generate-encryption-key))
  ;; => "base64-encoded-256-bit-key"

  ;; Encrypt a credential
  (def encrypted (encrypt-credential "access-token-secret-123" encryption-key))
  ;; => {:ciphertext "...", :iv "..."}

  ;; Decrypt it back
  (decrypt-credential encrypted encryption-key)
  ;; => "access-token-secret-123"

  ;; Load encryption key from secrets
  (require '[finance-aggregator.lib.secrets :as secrets])
  (def secrets-data (secrets/load-secrets))
  (def db-key (secrets/get-secret secrets-data [:database :encryption-key]))

  ;; Use it
  (def encrypted (encrypt-credential "token" db-key))
  (decrypt-credential encrypted db-key))

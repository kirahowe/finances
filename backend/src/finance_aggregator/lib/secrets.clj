(ns finance-aggregator.lib.secrets
  "Secure secrets management using age encryption.

   This is a generic, reusable library for managing encrypted secrets.
   It can be extracted to a separate library in the future.

   Secrets are stored in an encrypted file (*.edn.age) and decrypted at runtime
   using your age private key. The plaintext secrets file should NEVER be committed to git.

   Usage:
   1. Generate age key: bb secrets keygen
   2. Create secrets: bb secrets new
   3. Edit secrets: bb secrets edit
   4. Load at runtime: (load-secrets)

   The load-secrets function will automatically look for secrets in the default location
   and use the default key file, or you can override with environment variables or
   explicit parameters."
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure.java.shell :as shell]
   [clojure.string :as str]))

(defn age-installed?
  "Check if age encryption tool is installed on the system.
   Works cross-platform by checking if 'age' command is available."
  []
  (try
    (let [{:keys [exit]} (shell/sh "age" "--version")]
      (zero? exit))
    (catch Exception _
      false)))

(defn expand-home
  "Expand ~ to user's home directory in file paths."
  [path]
  (if (str/starts-with? path "~")
    (str/replace-first path "~" (System/getProperty "user.home"))
    path))

(defn- validate-prerequisites
  "Validate that age is installed and required files exist.
   Returns nil if valid, throws ex-info otherwise."
  [identity-path encrypted-path]
  (cond
    (not (age-installed?))
    (throw (ex-info "age encryption tool not found"
                    {:tool "age"
                     :install "brew install age (macOS) or apt install age (Linux)"
                     :docs "https://github.com/FiloSottile/age"}))

    (not (.exists (io/file identity-path)))
    (throw (ex-info "Age identity file (private key) not found"
                    {:identity-file identity-path
                     :hint "Run: bb secrets keygen"
                     :manual-setup "age-keygen -o ~/.config/finance-aggregator/key.txt"}))

    (not (.exists (io/file encrypted-path)))
    (throw (ex-info "Encrypted secrets file not found"
                    {:encrypted-file encrypted-path
                     :hint "Run: bb secrets new"
                     :docs "See backend/SECRETS.md for setup instructions"}))))

(defn decrypt-file
  "Decrypt a file using age with the specified identity (private key) file.

   Parameters:
   - identity-file: Path to age private key file (e.g., ~/.config/finance-aggregator/key.txt)
   - encrypted-file: Path to encrypted file (e.g., secrets.edn.age)

   Returns:
   Decrypted content as string, or throws exception on failure."
  [identity-file encrypted-file]
  (let [identity-path (expand-home identity-file)
        encrypted-path (expand-home encrypted-file)]
    (validate-prerequisites identity-path encrypted-path)
    (let [{:keys [exit out err]} (shell/sh "age" "-d" "-i" identity-path encrypted-path)]
      (if (zero? exit)
        out
        (throw (ex-info "Failed to decrypt secrets file"
                        {:exit-code exit
                         :error err
                         :identity-file identity-path
                         :encrypted-file encrypted-path
                         :hint "You may not be a recipient of this file. Use 'bb secrets show-key' to see your public key."}))))))

(defn load-secrets
  "Load and decrypt secrets from encrypted EDN file.

   Parameters:
   - identity-file: Path to age private key (default: ~/.config/finance-aggregator/key.txt)
   - encrypted-file: Path to encrypted secrets (default: resources/secrets.edn.age)

   Returns:
   Map of decrypted secrets

   Setup:
   Run 'bb secrets new' for guided setup

   Example:
   (load-secrets) ;; Uses default paths
   (load-secrets \"~/.config/finance-aggregator/key.txt\" \"resources/secrets.edn.age\")"
  ([]
   ;; Default: use standard location
   (load-secrets
    "~/.config/finance-aggregator/key.txt"
    "resources/secrets.edn.age"))
  ([identity-file encrypted-file]
   (try
     (-> (decrypt-file identity-file encrypted-file)
         (edn/read-string))
     (catch Exception e
       (throw (ex-info "Failed to load secrets"
                       {:cause (.getMessage e)
                        :hint "See finance-aggregator.lib.secrets namespace documentation for setup instructions"}
                       e))))))

(defn get-secret
  "Get a specific secret value from the secrets map using a key path.

   Parameters:
   - secrets: Map of secrets (from load-secrets)
   - key-path: Vector of keys to navigate nested map, or single keyword

   Returns:
   Secret value, or nil if not found

   Example:
   (get-secret secrets :plaid)
   (get-secret secrets [:plaid :client-id])
   (get-secret secrets [:database :encryption-key])"
  [secrets key-path]
  (if (vector? key-path)
    (get-in secrets key-path)
    (get secrets key-path)))

(comment
  ;; Example usage in REPL

  ;; Require the namespace
  (require '[finance-aggregator.lib.secrets :as secrets])

  ;; Load all secrets (uses default paths)
  (def secrets-data (secrets/load-secrets))

  ;; Get Plaid configuration
  (secrets/get-secret secrets-data :plaid)
  ;; => {:client-id "...", :secret "...", :environment :sandbox}

  ;; Get specific nested value
  (secrets/get-secret secrets-data [:plaid :client-id])
  ;; => "your_client_id_here"

  ;; Get database encryption key
  (secrets/get-secret secrets-data [:database :encryption-key])
  ;; => "base64-encoded-key"

  ;; Load with custom paths
  (secrets/load-secrets "~/my-keys/finance.txt" "secrets/prod.edn.age"))

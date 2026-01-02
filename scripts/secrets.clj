;;
;; secrets - Manage encrypted secrets for finance-aggregator using age
;;
;; Usage:
;;   bb secrets keygen <env>        Generate new age encryption key for environment
;;   bb secrets new <env>           Create new secrets file from template
;;   bb secrets edit <env>          Edit encrypted secrets file
;;   bb secrets show-key            Display public keys for all environments
;;   bb secrets encrypt FILE        Encrypt a plaintext file (uses dev key)
;;   bb secrets decrypt FILE        Decrypt an encrypted file (uses dev key)
;;   bb secrets help                Show this help message
;;
;; Environments: dev, test, prod
;;
;; Configuration is read from:
;;   - backend/env/dev/resources/config.edn
;;   - backend/env/test/resources/config.edn
;;   - backend/env/prod/resources/config.edn
;;

(require '[clojure.java.shell :as shell]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[clojure.edn :as edn])

(def ^:const colors
  {:red "\033[0;31m"
   :green "\033[0;32m"
   :yellow "\033[1;33m"
   :blue "\033[0;34m"
   :reset "\033[0m"})

(defn colorize [color text]
  (str (get colors color "") text (:reset colors)))

(defn print-error [& args]
  (binding [*out* *err*]
    (println (colorize :red (str "Error: " (str/join " " args))))))

(defn print-success [& args]
  (println (colorize :green (str/join " " args))))

(defn print-warning [& args]
  (println (colorize :yellow (str/join " " args))))

(defn print-info [& args]
  (println (colorize :blue (str/join " " args))))

(defn expand-home
  "Expand ~ to user's home directory in file paths."
  [path]
  (when (string? path)
    (if (str/starts-with? path "~")
      (str/replace-first path "~" (System/getProperty "user.home"))
      path)))

;; =============================================================================
;; Configuration - Read from env-specific config files
;; =============================================================================

(defn read-system-config
  "Read a system config EDN file and extract secrets paths.
   Uses a permissive reader that ignores unknown tags."
  [file-path]
  (when (.exists (io/file file-path))
    (let [config (edn/read-string {:default (fn [_tag value] value)}
                                  (slurp file-path))]
      {:key-file (:finance-aggregator.system/secrets-key-file config)
       :secrets-file (:finance-aggregator.system/secrets-file config)})))

(defn merge-configs
  "Merge base config with env-specific config (env overrides base).
   Only non-nil values from env-config override base."
  [base env-config]
  (merge base (into {} (remove (fn [[_ v]] (nil? v)) env-config))))

(def config
  (let [home (System/getProperty "user.home")
        project-root (System/getProperty "user.dir")
        backend-root (str project-root "/backend")
        backend-env (str backend-root "/env")
        ;; Read base config that all envs inherit from
        base-config (read-system-config (str backend-root "/resources/system/base-system.edn"))
        ;; Read and merge env-specific configs with base
        dev-config (merge-configs base-config (read-system-config (str backend-env "/dev/resources/config.edn")))
        test-config (merge-configs base-config (read-system-config (str backend-env "/test/resources/config.edn")))
        prod-config (merge-configs base-config (read-system-config (str backend-env "/prod/resources/config.edn")))
        ;; Resolve secrets file path relative to backend/
        resolve-secrets-file (fn [path]
                               (let [expanded (expand-home path)]
                                 (when expanded
                                   (if (str/starts-with? expanded "/")
                                     expanded
                                     (str backend-root "/" expanded)))))]
    {:key-files {:dev  (expand-home (:key-file dev-config))
                 :test (expand-home (:key-file test-config))
                 :prod (expand-home (:key-file prod-config))}
     :project-root project-root
     :backend-root backend-root
     :secrets-files {:dev (resolve-secrets-file (:secrets-file dev-config))
                     :test (resolve-secrets-file (:secrets-file test-config))
                     :prod (resolve-secrets-file (:secrets-file prod-config))}
     :template-files {:dev (str backend-env "/dev/resources/secrets.edn.example")
                      :test (str backend-env "/test/resources/secrets.edn.example")
                      :prod (str backend-env "/prod/resources/secrets.edn.example")}
     :editor (or (System/getenv "VISUAL")
                 (System/getenv "EDITOR")
                 "vim")}))

;; =============================================================================
;; Environment Parsing
;; =============================================================================

(def ^:private env-aliases
  "Map of environment argument strings to canonical keywords."
  {"dev"         :dev
   "development" :dev
   "test"        :test
   "prod"        :prod
   "production"  :prod})

(defn parse-env
  "Parse environment argument to keyword."
  [arg]
  (cond
    (nil? arg)
    (do
      (print-error "Missing environment argument")
      (println "Valid environments: dev, test, prod")
      (System/exit 1))

    (env-aliases arg)
    (env-aliases arg)

    :else
    (do
      (print-error "Unknown environment:" arg)
      (println "Valid environments: dev, test, prod")
      (System/exit 1))))

(defn get-key-file
  "Get the key file path for an environment."
  [env-key]
  (get-in config [:key-files env-key]))

(defn get-secrets-file
  "Get the secrets file path for an environment."
  [env-key]
  (get-in config [:secrets-files env-key]))

(defn get-template-file
  "Get the template file path for an environment."
  [env-key]
  (get-in config [:template-files env-key]))

;; =============================================================================
;; Prerequisites
;; =============================================================================

(defn age-installed?
  "Check if age encryption tool is installed."
  []
  (try
    (let [{:keys [exit]} (shell/sh "which" "age")]
      (zero? exit))
    (catch Exception _
      false)))

(defn ensure-age-installed!
  "Check that age is installed, print helpful message if not."
  []
  (when-not (age-installed?)
    (print-error "age encryption tool is not installed")
    (println)
    (println "Install with:")
    (println "  macOS:  brew install age")
    (println "  Linux:  apt install age")
    (println "  Other:  https://github.com/FiloSottile/age")
    (System/exit 1)))

(defn ensure-key-file-exists!
  "Check if key file exists, provide helpful message if not."
  [env-key]
  (let [key-file (get-key-file env-key)]
    (when-not (and key-file (.exists (io/file key-file)))
      (print-error "Age identity file (private key) not found:" (or key-file "(not configured)"))
      (println)
      (println "To set up secrets for" (name env-key) "environment:")
      (println (str "  1. Generate a key:  bb secrets keygen " (name env-key)))
      (println (str "  2. Create secrets:  bb secrets new " (name env-key)))
      (System/exit 1))))

;; =============================================================================
;; Utilities
;; =============================================================================

(defn secure-delete!
  "Securely delete a file (best effort)."
  [file-path]
  (let [file (io/file file-path)]
    (when (.exists file)
      (if (= "Mac OS X" (System/getProperty "os.name"))
        (shell/sh "rm" "-P" file-path)
        (if (zero? (:exit (shell/sh "which" "shred")))
          (shell/sh "shred" "-u" file-path)
          (.delete file))))))

(defn read-public-key
  "Extract public key from age identity file."
  [env-key]
  (let [key-file (get-key-file env-key)]
    (when (and key-file (.exists (io/file key-file)))
      (some->> (slurp key-file)
               str/split-lines
               (filter #(str/includes? % "public key:"))
               first
               (re-find #"public key:\s*(.+)")
               second))))

(defn create-temp-file
  "Create a temporary file with secure permissions."
  []
  (let [temp (java.io.File/createTempFile "secrets-" ".edn")]
    (.setReadable temp false false)
    (.setReadable temp true true)
    (.setWritable temp false false)
    (.setWritable temp true true)
    (.getAbsolutePath temp)))

(defn encrypt-file!
  "Encrypt a file using age."
  [env-key input-file output-file]
  (let [key-file (get-key-file env-key)
        {:keys [exit err]} (shell/sh "age" "-e" "-i" key-file "-o" output-file input-file)]
    (if (zero? exit)
      (do
        (println "Key file:" key-file)
        (when-let [pub-key (read-public-key env-key)]
          (println "Public key:" pub-key))
        true)
      (do
        (print-error "Failed to encrypt file:" err)
        false))))

(defn decrypt-file!
  "Decrypt a file using age."
  [env-key input-file output-file]
  (let [key-file (get-key-file env-key)
        {:keys [exit err out]} (shell/sh "age" "-d" "-i" key-file input-file)]
    (if (zero? exit)
      (do
        (spit output-file out)
        true)
      (do
        (print-error "Failed to decrypt file:" err)
        (println)
        (println "Possible causes:")
        (println "  - You're not a recipient of this file")
        (println "  - The file is corrupted")
        (when-let [pub-key (read-public-key env-key)]
          (println)
          (println "Your public key:")
          (println "  " pub-key))
        false))))

(defn file-checksum
  "Calculate SHA-256 checksum of a file."
  [file-path]
  (let [{:keys [out]} (shell/sh "shasum" "-a" "256" file-path)]
    (first (str/split out #"\s+"))))

(defn confirm!
  "Prompt user for confirmation. Returns true if confirmed, exits if not."
  [message]
  (print (str message " (y/N) "))
  (flush)
  (if (= "y" (str/lower-case (or (read-line) "")))
    true
    (do (println "Aborted.")
        (System/exit 0))))

;; =============================================================================
;; Commands
;; =============================================================================

(defn get-template-content
  "Get template content for an environment. Falls back to dev template if env-specific doesn't exist."
  [env-key]
  (let [template-file (get-template-file env-key)
        dev-template (get-template-file :dev)]
    (cond
      (and template-file (.exists (io/file template-file)))
      (slurp template-file)

      (and dev-template (.exists (io/file dev-template)))
      (slurp dev-template)

      :else
      (str/join "\n"
                [";; Finance Aggregator Secrets Configuration"
                 ";;"
                 ";; This file contains sensitive credentials."
                 ""
                 "{:plaid {:client-id \"your_client_id_here\""
                 "         :secret \"your_secret_here\""
                 "         :environment #plaid/environment :sandbox}"
                 ""
                 " :database {:encryption-key nil}}"
                 ""]))))

(defn keygen!
  "Generate a new age encryption key for the specified environment."
  [env]
  (let [env-key (parse-env env)]
    (ensure-age-installed!)
    (let [key-file (get-key-file env-key)]
      (when-not key-file
        (print-error "No key file configured for" (name env-key) "environment")
        (println "Check backend/env/" (name env-key) "/resources/config.edn")
        (System/exit 1))

      (let [expanded-path (expand-home key-file)
            file (io/file expanded-path)
            parent-dir (.getParentFile file)]

        (when (.exists file)
          (print-warning "Key file already exists:" expanded-path)
          (confirm! "Overwrite? This will make existing secrets unreadable!"))

        (when parent-dir (.mkdirs parent-dir))

        (print-info "Generating age encryption key for" (name env-key) "...")
        (let [{:keys [exit err]} (shell/sh "age-keygen" "-o" expanded-path)]
          (if (zero? exit)
            (do
              (println)
              (print-success "Age key generated successfully!")
              (println)
              (println "Key location:" expanded-path)
              (println)
              (println "IMPORTANT:")
              (println "  - Back up this key securely")
              (println "  - Never commit this key to git")
              (println)
              (when-let [pub-key (read-public-key env-key)]
                (println "Your public key (share with team):")
                (println "  " pub-key))
              (println)
              (println "Next steps:")
              (println (str "  bb secrets new " (name env-key))))
            (do
              (print-error "Failed to generate key:" err)
              (System/exit 1))))))))

(defn show-key!
  "Display public keys for all environments."
  []
  (ensure-age-installed!)
  (println)
  (print-success "Public keys by environment:")
  (println)
  (doseq [[env-key key-file] (sort-by (comp str key) (:key-files config))]
    (if (and key-file (.exists (io/file (expand-home key-file))))
      (if-let [public-key (read-public-key env-key)]
        (println (format "  %s: %s" (name env-key) public-key))
        (println (format "  %s: (could not read key from %s)" (name env-key) key-file)))
      (println (format "  %s: (key file not found: %s)" (name env-key) (or key-file "not configured"))))))

(defn new-secrets!
  "Create a new secrets file from template."
  [env]
  (let [env-key (parse-env env)
        secrets-file (get-secrets-file env-key)
        {:keys [editor]} config]
    (ensure-age-installed!)
    (ensure-key-file-exists! env-key)

    (when-not secrets-file
      (print-error "No secrets file configured for" (name env-key) "environment")
      (System/exit 1))

    (when (.exists (io/file secrets-file))
      (print-warning "Encrypted secrets file already exists:" secrets-file)
      (confirm! "Overwrite?"))

    (let [temp-file (create-temp-file)]
      (try
        (spit temp-file (get-template-content env-key))

        (println)
        (print-info "Opening editor for" (name env-key) "secrets...")
        (let [pb (ProcessBuilder. (into-array String [editor temp-file]))
              _ (.inheritIO pb)
              process (.start pb)
              exit-code (.waitFor process)]
          (when-not (zero? exit-code)
            (print-error "Editor exited with error")
            (System/exit 1)))

        (let [parent-dir (.getParentFile (io/file secrets-file))]
          (when parent-dir (.mkdirs parent-dir)))

        (println)
        (print-info "Encrypting...")
        (if (encrypt-file! env-key temp-file secrets-file)
          (do
            (println)
            (print-success "Secrets created successfully!")
            (println)
            (println "Encrypted file:" secrets-file))
          (System/exit 1))

        (finally
          (secure-delete! temp-file))))))

(defn edit-secrets!
  "Edit encrypted secrets file."
  [env]
  (let [env-key (parse-env env)
        secrets-file (get-secrets-file env-key)
        {:keys [editor]} config]
    (ensure-age-installed!)
    (ensure-key-file-exists! env-key)

    (when-not secrets-file
      (print-error "No secrets file configured for" (name env-key) "environment")
      (System/exit 1))

    (when-not (.exists (io/file secrets-file))
      (print-error "Encrypted secrets file not found:" secrets-file)
      (println)
      (println "To create a new secrets file, run:")
      (println (str "  bb secrets new " (name env-key)))
      (System/exit 1))

    (let [temp-file (create-temp-file)]
      (try
        (print-info "Decrypting" secrets-file "...")
        (when-not (decrypt-file! env-key secrets-file temp-file)
          (System/exit 1))

        (let [checksum-before (file-checksum temp-file)]
          (println)
          (print-info "Opening editor for" (name env-key) "secrets...")
          (let [pb (ProcessBuilder. (into-array String [editor temp-file]))
                _ (.inheritIO pb)
                process (.start pb)
                exit-code (.waitFor process)]
            (when-not (zero? exit-code)
              (print-error "Editor exited with error")
              (System/exit 1)))

          (let [checksum-after (file-checksum temp-file)]
            (if (= checksum-before checksum-after)
              (do
                (println)
                (print-warning "No changes detected, skipping re-encryption"))
              (do
                (println)
                (print-info "Re-encrypting...")
                (if (encrypt-file! env-key temp-file secrets-file)
                  (do
                    (println)
                    (print-success "Secrets updated successfully!"))
                  (System/exit 1))))))

        (finally
          (secure-delete! temp-file))))

    (println)
    (print-success "Plaintext securely deleted")))

(defn encrypt-command!
  "Encrypt a plaintext file (uses dev key)."
  [file-path]
  (ensure-age-installed!)
  (ensure-key-file-exists! :dev)
  (when-not (.exists (io/file file-path))
    (print-error "File not found:" file-path)
    (System/exit 1))
  (let [output-file (str file-path ".age")]
    (when (.exists (io/file output-file))
      (print-warning "Output file already exists:" output-file)
      (confirm! "Overwrite?"))
    (print-info "Encrypting" file-path "...")
    (if (encrypt-file! :dev file-path output-file)
      (do
        (println)
        (print-success "Encrypted to:" output-file))
      (System/exit 1))))

(defn decrypt-command!
  "Decrypt an encrypted file (uses dev key)."
  [file-path]
  (ensure-age-installed!)
  (ensure-key-file-exists! :dev)
  (when-not (.exists (io/file file-path))
    (print-error "File not found:" file-path)
    (System/exit 1))
  (let [output-file (if (str/ends-with? file-path ".age")
                      (subs file-path 0 (- (count file-path) 4))
                      (str file-path ".decrypted"))]
    (when (.exists (io/file output-file))
      (print-warning "Output file already exists:" output-file)
      (confirm! "Overwrite?"))
    (print-info "Decrypting" file-path "...")
    (if (decrypt-file! :dev file-path output-file)
      (do
        (println)
        (print-success "Decrypted to:" output-file)
        (println)
        (print-warning "Remember to securely delete this file when done!"))
      (System/exit 1))))

(defn print-help
  "Display help message."
  []
  (println)
  (println "secrets - Manage encrypted secrets for finance-aggregator")
  (println)
  (println "Usage:")
  (println "  bb secrets COMMAND [ARGS]")
  (println)
  (println "Commands:")
  (println "  keygen <env>      Generate new age encryption key for environment")
  (println "  new <env>         Create new secrets file from template")
  (println "  edit <env>        Edit encrypted secrets file")
  (println "  show-key          Display public keys for all environments")
  (println "  encrypt FILE      Encrypt a plaintext file (uses dev key)")
  (println "  decrypt FILE      Decrypt an encrypted file (uses dev key)")
  (println "  help              Show this help message")
  (println)
  (println "Environments: dev, test, prod")
  (println)
  (println "Configuration is read from env-specific config files:")
  (println "  backend/env/dev/resources/config.edn")
  (println "  backend/env/test/resources/config.edn")
  (println "  backend/env/prod/resources/config.edn")
  (println)
  (println "Each environment specifies its own:")
  (println "  :finance-aggregator.system/secrets-key-file")
  (println "  :finance-aggregator.system/secrets-file")
  (println)
  (println "Examples:")
  (println "  bb secrets keygen dev        # Generate dev key")
  (println "  bb secrets new dev           # Create dev secrets")
  (println "  bb secrets edit dev          # Edit dev secrets")
  (println "  bb secrets keygen test       # Generate test key")
  (println "  bb secrets new test          # Create test secrets")
  (println "  bb secrets show-key          # Show all public keys")
  (println))

(defn -main [& args]
  (let [command (first args)
        command-args (rest args)]
    (case command
      "keygen"   (keygen! (first command-args))
      "new"      (new-secrets! (first command-args))
      "edit"     (edit-secrets! (first command-args))
      "show-key" (show-key!)
      "encrypt"  (if-let [file (first command-args)]
                   (encrypt-command! file)
                   (do
                     (print-error "Missing file argument")
                     (println "Usage: bb secrets encrypt FILE")
                     (System/exit 1)))
      "decrypt"  (if-let [file (first command-args)]
                   (decrypt-command! file)
                   (do
                     (print-error "Missing file argument")
                     (println "Usage: bb secrets decrypt FILE")
                     (System/exit 1)))
      "help"     (print-help)
      (nil)      (print-help)
      (do
        (print-error "Unknown command:" command)
        (println)
        (print-help)
        (System/exit 1)))))

;; Run main
(apply -main *command-line-args*)

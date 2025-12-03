;;
;; secrets - Manage encrypted secrets for finance-aggregator using age
;;
;; Usage:
;;   bb secrets edit              Edit encrypted secrets file
;;   bb secrets new               Create new secrets file from template
;;   bb secrets keygen [FILE]     Generate new age encryption key
;;   bb secrets show-key          Display your public key
;;   bb secrets encrypt FILE      Encrypt a plaintext file
;;   bb secrets decrypt FILE      Decrypt an encrypted file
;;   bb secrets help              Show this help message
;;
;; Configuration:
;;   Key file:     ~/.config/finance-aggregator/key.txt (convention)
;;   Secrets file: backend/resources/secrets.edn.age
;;   Editor:       $VISUAL, $EDITOR, or vim (in that order)
;;

(require '[clojure.java.shell :as shell]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

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

(defn expand-home [path]
  "Expand ~ to user's home directory in file paths."
  (if (str/starts-with? path "~")
    (str/replace-first path "~" (System/getProperty "user.home"))
    path))

(def config
  (let [home (System/getProperty "user.home")
        project-root (System/getProperty "user.dir")]
    {:key-file (str home "/.config/finance-aggregator/key.txt")
     :secrets-file (str project-root "/backend/resources/secrets.edn.age")
     :template-file (str project-root "/backend/resources/secrets.edn.example")
     :editor (or (System/getenv "VISUAL")
                 (System/getenv "EDITOR")
                 "vim")
     :project-root project-root}))

(defn age-installed? []
  "Check if age encryption tool is installed."
  (try
    (let [{:keys [exit]} (shell/sh "which" "age")]
      (zero? exit))
    (catch Exception _
      false)))

(defn ensure-age-installed
  "Check that age is installed, print helpful message if not."
  []
  (when-not (age-installed?)
    (print-error "age encryption tool is not installed")
    (println)
    (println "Install with:")
    (println "  macOS:  brew install age")
    (println "  Linux:  apt install age")
    (println "  Other:  https://github.com/FiloSottile/age")
    (println)
    (println "After installing age, try your command again.")
    (System/exit 1)))

(defn ensure-key-file-exists
  "Check if key file exists, provide helpful message if not."
  []
  (let [{:keys [key-file]} config]
    (when-not (.exists (io/file key-file))
      (print-error "Age identity file (private key) not found:" key-file)
      (println)
      (println "To set up secrets:")
      (println (str "  1. Generate a key:           bb secrets keygen"))
      (println "  2. Create secrets:           bb secrets new")
      (println)
      (println "For a custom key location, use:")
      (println "  bb secrets keygen /path/to/your/key.txt")
      (System/exit 1))))

(defn secure-delete [file-path]
  "Securely delete a file (best effort)."
  (let [file (io/file file-path)]
    (when (.exists file)
      (if (= "Mac OS X" (System/getProperty "os.name"))
        ;; macOS - use rm -P
        (shell/sh "rm" "-P" file-path)
        ;; Try shred on Linux, fall back to rm
        (if (zero? (:exit (shell/sh "which" "shred")))
          (shell/sh "shred" "-u" file-path)
          (.delete file))))))

(defn read-public-key []
  "Extract public key from age identity file."
  (let [{:keys [key-file]} config
        content (slurp key-file)
        lines (str/split-lines content)]
    (->> lines
         (filter #(str/includes? % "public key:"))
         first
         (#(when % (second (str/split % #"public key:\s*")))))))

(defn generate-age-key
  "Generate a new age encryption key."
  ([]
   (generate-age-key (:key-file config)))
  ([output-file]
   (ensure-age-installed)
   (let [expanded-path (expand-home output-file)
         file (io/file expanded-path)
         parent-dir (.getParentFile file)]
     ;; Check if file already exists
     (when (.exists file)
       (print-warning "Key file already exists:" expanded-path)
       (print "Overwrite? This will make existing secrets unreadable! (y/N) ")
       (flush)
       (let [response (str/lower-case (or (read-line) ""))]
         (when-not (= response "y")
           (println "Aborted.")
           (System/exit 0))))

     ;; Create parent directory if needed
     (when parent-dir
       (.mkdirs parent-dir))

     ;; Generate key
     (print-info "Generating age encryption key...")
     (let [{:keys [exit out err]} (shell/sh "age-keygen" "-o" expanded-path)]
       (if (zero? exit)
         (do
           (println)
           (print-success "Age key generated successfully!")
           (println)
           (println "Key location:" expanded-path)
           (println)
           (println "IMPORTANT:")
           (println "  - Back up this key securely (password manager, encrypted backup)")
           (println "  - Without it, you cannot decrypt your secrets")
           (println "  - Never commit this key to git")
           (println)
           (when-let [pub-key (read-public-key)]
             (println "Your public key (share with team):")
             (println "  " pub-key))
           (println)
           (println "Next steps:")
           (println "  1. Create secrets:  bb secrets new")
           (println "  2. Edit secrets:    bb secrets edit"))
         (do
           (print-error "Failed to generate key:" err)
           (System/exit 1)))))))

(defn show-public-key []
  "Display the user's public key."
  (ensure-age-installed)
  (ensure-key-file-exists)
  (if-let [public-key (read-public-key)]
    (do
      (println)
      (print-success "Your public key (share this with team members):")
      (println)
      (println "  " public-key)
      (println)
      (println "Team members can add you as a recipient when re-encrypting secrets."))
    (do
      (print-error "Could not extract public key from" (:key-file config))
      (System/exit 1))))

(defn create-temp-file []
  "Create a temporary file with secure permissions."
  (let [temp (java.io.File/createTempFile "secrets-" ".edn")]
    (.setReadable temp false false)  ; Remove read for others
    (.setReadable temp true true)    ; Add read for owner
    (.setWritable temp false false)  ; Remove write for others
    (.setWritable temp true true)    ; Add write for owner
    (.getAbsolutePath temp)))

(defn encrypt-file [input-file output-file]
  "Encrypt a file using age."
  (let [{:keys [key-file]} config
        {:keys [exit err]} (shell/sh "age" "-e" "-i" key-file
                                     "-o" output-file
                                     input-file)]
    (if (zero? exit)
      true
      (do
        (print-error "Failed to encrypt file:" err)
        false))))

(defn decrypt-file [input-file output-file]
  "Decrypt a file using age."
  (let [{:keys [key-file]} config
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
        (println)
        (if-let [pub-key (read-public-key)]
          (do
            (println "Your public key:")
            (println "  " pub-key)
            (println)
            (println "Ask a team member to add you as a recipient and re-encrypt."))
          (println "Use 'bb secrets show-key' to see your public key."))
        false))))

(defn file-checksum [file-path]
  "Calculate SHA-256 checksum of a file."
  (let [{:keys [out]} (shell/sh "shasum" "-a" "256" file-path)]
    (first (str/split out #"\s+"))))

(defn create-new-secrets []
  "Create a new secrets file from template."
  (ensure-age-installed)
  (ensure-key-file-exists)
  (let [{:keys [secrets-file template-file editor]} config]
    ;; Check if encrypted file already exists
    (when (.exists (io/file secrets-file))
      (print-warning "Encrypted secrets file already exists:" secrets-file)
      (print "Overwrite? (y/N) ")
      (flush)
      (let [response (str/lower-case (or (read-line) ""))]
        (when-not (= response "y")
          (println "Aborted.")
          (System/exit 0))))

    ;; Create temp file
    (let [temp-file (create-temp-file)]
      (try
        ;; Copy template or create minimal file
        (if (.exists (io/file template-file))
          (do
            (io/copy (io/file template-file) (io/file temp-file))
            (print-success "Created from template:" template-file))
          (do
            (spit temp-file
                  (str/join "\n"
                            [";; Finance Aggregator Secrets Configuration"
                             ";;"
                             ";; This file contains sensitive credentials. After editing:"
                             ";; - The plaintext will be securely deleted"
                             ";; - Only the encrypted version (*.age) should be committed"
                             ""
                             "{;; Plaid API credentials (from https://dashboard.plaid.com/)"
                             " :plaid {:client-id \"your_client_id_here\""
                             "         :secret \"your_secret_here\""
                             "         :environment :sandbox} ; or :production"
                             ""
                             " ;; Database encryption key (will be generated if not provided)"
                             " :database {:encryption-key nil}"
                             ""
                             " ;; Add other secrets as needed"
                             " ;; :other-service {:api-key \"...\"}"
                             " }"
                             ""]))
            (print-warning "No template found, created minimal secrets file")))

        ;; Open editor
        (println)
        (print-info "Opening editor...")
        (let [{:keys [exit]} (shell/sh editor temp-file)]
          (when-not (zero? exit)
            (print-error "Editor exited with error")
            (System/exit 1)))

        ;; Encrypt
        (println)
        (print-info "Encrypting...")
        (if (encrypt-file temp-file secrets-file)
          (do
            (println)
            (print-success "Secrets created and encrypted:" secrets-file)
            (println)
            (println "Next steps:")
            (println (str "  1. Commit the encrypted file:  git add " secrets-file))
            (println "  2. To add team members, re-encrypt with their public keys")
            (println "  3. Update your backend config to load secrets"))
          (System/exit 1))

        (finally
          (secure-delete temp-file))))))

(defn edit-secrets []
  "Edit encrypted secrets file."
  (ensure-age-installed)
  (ensure-key-file-exists)
  (let [{:keys [secrets-file editor]} config]
    ;; Check if encrypted file exists
    (when-not (.exists (io/file secrets-file))
      (print-error "Encrypted secrets file not found:" secrets-file)
      (println)
      (println "To create a new secrets file, run:")
      (println "  bb secrets new")
      (System/exit 1))

    ;; Create temp file
    (let [temp-file (create-temp-file)]
      (try
        ;; Decrypt
        (print-info "Decrypting" secrets-file "...")
        (when-not (decrypt-file secrets-file temp-file)
          (System/exit 1))

        ;; Get checksum before editing
        (let [checksum-before (file-checksum temp-file)]
          ;; Open editor
          (println)
          (print-info "Opening editor...")
          (let [{:keys [exit]} (shell/sh editor temp-file)]
            (when-not (zero? exit)
              (print-error "Editor exited with error")
              (System/exit 1)))

          ;; Check if file was modified
          (let [checksum-after (file-checksum temp-file)]
            (if (= checksum-before checksum-after)
              (do
                (println)
                (print-warning "No changes detected, skipping re-encryption"))
              (do
                (println)
                (print-info "Re-encrypting...")
                (if (encrypt-file temp-file secrets-file)
                  (do
                    (println)
                    (print-success "Secrets updated:" secrets-file))
                  (System/exit 1))))))

        (finally
          (secure-delete temp-file))))

    (println)
    (print-success "Plaintext securely deleted")))

(defn encrypt-command [file-path]
  "Encrypt a plaintext file."
  (ensure-age-installed)
  (ensure-key-file-exists)
  (when-not (.exists (io/file file-path))
    (print-error "File not found:" file-path)
    (System/exit 1))
  (let [output-file (str file-path ".age")]
    (when (.exists (io/file output-file))
      (print-warning "Output file already exists:" output-file)
      (print "Overwrite? (y/N) ")
      (flush)
      (let [response (str/lower-case (or (read-line) ""))]
        (when-not (= response "y")
          (println "Aborted.")
          (System/exit 0))))
    (print-info "Encrypting" file-path "...")
    (if (encrypt-file file-path output-file)
      (do
        (println)
        (print-success "Encrypted to:" output-file)
        (println)
        (println "To securely delete the plaintext:")
        (println (str "  rm -P " file-path "  # macOS"))
        (println (str "  shred -u " file-path "  # Linux")))
      (System/exit 1))))

(defn decrypt-command [file-path]
  "Decrypt an encrypted file."
  (ensure-age-installed)
  (ensure-key-file-exists)
  (when-not (.exists (io/file file-path))
    (print-error "File not found:" file-path)
    (System/exit 1))
  (let [output-file (if (str/ends-with? file-path ".age")
                      (subs file-path 0 (- (count file-path) 4))
                      (str file-path ".decrypted"))]
    (when (.exists (io/file output-file))
      (print-warning "Output file already exists:" output-file)
      (print "Overwrite? (y/N) ")
      (flush)
      (let [response (str/lower-case (or (read-line) ""))]
        (when-not (= response "y")
          (println "Aborted.")
          (System/exit 0))))
    (print-info "Decrypting" file-path "...")
    (if (decrypt-file file-path output-file)
      (do
        (println)
        (print-success "Decrypted to:" output-file)
        (println)
        (print-warning "Remember to securely delete this file when done!"))
      (System/exit 1))))

(defn print-help []
  "Display help message."
  (println)
  (println "secrets - Manage encrypted secrets for finance-aggregator")
  (println)
  (println "Usage:")
  (println "  bb secrets COMMAND [ARGS]")
  (println)
  (println "Commands:")
  (println "  keygen [FILE]     Generate new age encryption key")
  (println "                    (default: ~/.config/finance-aggregator/key.txt)")
  (println "  edit              Edit encrypted secrets file")
  (println "  new               Create new secrets file from template")
  (println "  show-key          Display your public key")
  (println "  encrypt FILE      Encrypt a plaintext file")
  (println "  decrypt FILE      Decrypt an encrypted file")
  (println "  help              Show this help message")
  (println)
  (println "Configuration:")
  (println "  Key location:     ~/.config/finance-aggregator/key.txt (convention)")
  (println "  Secrets file:     backend/resources/secrets.edn.age")
  (println "  Editor:           $VISUAL, $EDITOR, or vim (in that order)")
  (println)
  (println "Setup:")
  (println "  1. Install age:           brew install age")
  (println "  2. Generate key:          bb secrets keygen")
  (println "  3. Create secrets:        bb secrets new")
  (println "  4. Edit as needed:        bb secrets edit")
  (println)
  (println "Examples:")
  (println "  bb secrets keygen                        # Generate key in default location")
  (println "  bb secrets keygen ~/my-project-key.txt   # Generate key in custom location")
  (println "  bb secrets new                           # Create new secrets")
  (println "  bb secrets edit                          # Edit existing secrets")
  (println "  bb secrets show-key                      # Show your public key")
  (println "  bb secrets encrypt secret.edn            # Encrypt a file")
  (println))

(defn -main [& args]
  (let [command (first args)
        command-args (rest args)]
    (case command
      "keygen" (if-let [file (first command-args)]
                 (generate-age-key file)
                 (generate-age-key))
      "edit" (edit-secrets)
      "new" (create-new-secrets)
      "show-key" (show-public-key)
      "encrypt" (if-let [file (first command-args)]
                  (encrypt-command file)
                  (do
                    (print-error "Missing file argument")
                    (println "Usage: bb secrets encrypt FILE")
                    (System/exit 1)))
      "decrypt" (if-let [file (first command-args)]
                  (decrypt-command file)
                  (do
                    (print-error "Missing file argument")
                    (println "Usage: bb secrets decrypt FILE")
                    (System/exit 1)))
      "help" (print-help)
      (nil) (print-help)
      (do
        (print-error "Unknown command:" command)
        (println)
        (print-help)
        (System/exit 1)))))

;; Run main
(apply -main *command-line-args*)

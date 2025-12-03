(ns finance-aggregator.lib.secrets-test
  "Comprehensive tests for secrets management library.

   These tests verify encryption, decryption, and key management functionality.
   They require age to be installed on the system."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [finance-aggregator.lib.secrets :as secrets]))

;; Test fixtures and helpers

(def test-key-file (str (System/getProperty "java.io.tmpdir") "/test-age-key.txt"))
(def test-secrets-file (str (System/getProperty "java.io.tmpdir") "/test-secrets.edn.age"))
(def test-plaintext-file (str (System/getProperty "java.io.tmpdir") "/test-secrets.edn"))

(defn cleanup-test-files []
  "Remove all test files created during testing."
  (doseq [file [test-key-file test-secrets-file test-plaintext-file]]
    (when (.exists (io/file file))
      (.delete (io/file file)))))

(defn setup-test-key []
  "Generate a test age key for testing."
  (cleanup-test-files)
  (when (secrets/age-installed?)
    (let [{:keys [exit]} (shell/sh "age-keygen" "-o" test-key-file)]
      (when-not (zero? exit)
        (throw (ex-info "Failed to generate test key" {}))))))

(use-fixtures :each
  (fn [f]
    (setup-test-key)
    (try
      (f)
      (finally
        (cleanup-test-files)))))

;; Unit tests

(deftest age-installed-test
  (testing "age-installed? returns boolean"
    (is (boolean? (secrets/age-installed?))))

  (testing "age-installed? returns true when age is available"
    ;; This test assumes age is installed in the dev environment
    ;; If it fails, install age: brew install age
    (is (true? (secrets/age-installed?)))))

(deftest expand-home-test
  (testing "expand-home expands ~ to home directory"
    (let [home (System/getProperty "user.home")
          expanded (secrets/expand-home "~/test/path")]
      (is (= (str home "/test/path") expanded))))

  (testing "expand-home leaves absolute paths unchanged"
    (is (= "/absolute/path" (secrets/expand-home "/absolute/path"))))

  (testing "expand-home leaves relative paths unchanged"
    (is (= "relative/path" (secrets/expand-home "relative/path")))))

(deftest decrypt-file-test
  (testing "decrypt-file successfully decrypts a valid encrypted file"
    (when (secrets/age-installed?)
      ;; Create test plaintext
      (spit test-plaintext-file "{:test-key \"test-value\"}")

      ;; Encrypt it
      (let [{:keys [exit]} (shell/sh "age" "-e" "-i" test-key-file
                                     "-o" test-secrets-file
                                     test-plaintext-file)]
        (is (zero? exit) "Encryption should succeed"))

      ;; Delete plaintext
      (.delete (io/file test-plaintext-file))

      ;; Decrypt using our function
      (let [decrypted (secrets/decrypt-file test-key-file test-secrets-file)]
        (is (string? decrypted))
        (is (= "{:test-key \"test-value\"}" (clojure.string/trim decrypted))))))

  (testing "decrypt-file throws when identity file doesn't exist"
    (when (secrets/age-installed?)
      ;; Create a dummy encrypted file
      (spit test-secrets-file "dummy content")

      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Age identity file.*not found"
           (secrets/decrypt-file "/nonexistent/key.txt" test-secrets-file)))))

  (testing "decrypt-file throws when encrypted file doesn't exist"
    (when (secrets/age-installed?)
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Encrypted secrets file not found"
           (secrets/decrypt-file test-key-file "/nonexistent/secrets.edn.age"))))))

(deftest load-secrets-test
  (testing "load-secrets decrypts and parses EDN secrets"
    (when (secrets/age-installed?)
      ;; Create test secrets
      (let [test-secrets {:plaid {:client-id "test-id"
                                   :secret "test-secret"
                                   :environment :sandbox}
                          :database {:encryption-key "test-key"}}]
        ;; Write to plaintext
        (spit test-plaintext-file (pr-str test-secrets))

        ;; Encrypt
        (shell/sh "age" "-e" "-i" test-key-file
                  "-o" test-secrets-file
                  test-plaintext-file)

        ;; Delete plaintext
        (.delete (io/file test-plaintext-file))

        ;; Load secrets
        (let [loaded (secrets/load-secrets test-key-file test-secrets-file)]
          (is (map? loaded))
          (is (= "test-id" (get-in loaded [:plaid :client-id])))
          (is (= "test-secret" (get-in loaded [:plaid :secret])))
          (is (= :sandbox (get-in loaded [:plaid :environment])))
          (is (= "test-key" (get-in loaded [:database :encryption-key])))))))

  (testing "load-secrets throws on invalid EDN"
    (when (secrets/age-installed?)
      ;; Create invalid EDN - unmatched bracket
      (spit test-plaintext-file "{:key \"value\"")

      ;; Encrypt
      (shell/sh "age" "-e" "-i" test-key-file
                "-o" test-secrets-file
                test-plaintext-file)

      ;; Delete plaintext
      (.delete (io/file test-plaintext-file))

      ;; Should throw when trying to parse
      (is (thrown? clojure.lang.ExceptionInfo
                   (secrets/load-secrets test-key-file test-secrets-file))))))

(deftest get-secret-test
  (testing "get-secret retrieves top-level key"
    (let [secrets {:key1 "value1" :key2 "value2"}]
      (is (= "value1" (secrets/get-secret secrets :key1)))
      (is (= "value2" (secrets/get-secret secrets :key2)))))

  (testing "get-secret retrieves nested key with vector path"
    (let [secrets {:plaid {:client-id "id123"
                           :secret "secret456"}}]
      (is (= "id123" (secrets/get-secret secrets [:plaid :client-id])))
      (is (= "secret456" (secrets/get-secret secrets [:plaid :secret])))))

  (testing "get-secret returns nil for non-existent key"
    (let [secrets {:key1 "value1"}]
      (is (nil? (secrets/get-secret secrets :nonexistent)))
      (is (nil? (secrets/get-secret secrets [:nested :nonexistent])))))

  (testing "get-secret handles deeply nested paths"
    (let [secrets {:level1 {:level2 {:level3 {:level4 "deep-value"}}}}]
      (is (= "deep-value"
             (secrets/get-secret secrets [:level1 :level2 :level3 :level4])))))

  (testing "get-secret with single keyword vs vector"
    (let [secrets {:top "top-value"
                   :nested {:key "nested-value"}}]
      ;; Single keyword gets top-level
      (is (= "top-value" (secrets/get-secret secrets :top)))
      ;; Vector with one element also works
      (is (= {:key "nested-value"} (secrets/get-secret secrets [:nested]))))))

;; Integration tests

(deftest full-encryption-cycle-test
  (testing "Full encryption/decryption cycle with real secrets"
    (when (secrets/age-installed?)
      ;; Create realistic test secrets
      (let [test-secrets {:plaid {:client-id "test_12345abcde"
                                   :secret "test_secret_67890fghij"
                                   :environment :sandbox}
                          :database {:encryption-key "YmFzZTY0LWVuY29kZWQta2V5LWhlcmU="}}
            plaintext (pr-str test-secrets)]

        ;; Write plaintext
        (spit test-plaintext-file plaintext)

        ;; Encrypt
        (let [{:keys [exit]} (shell/sh "age" "-e" "-i" test-key-file
                                       "-o" test-secrets-file
                                       test-plaintext-file)]
          (is (zero? exit) "Encryption should succeed"))

        ;; Verify encrypted file exists and is different from plaintext
        (is (.exists (io/file test-secrets-file)))
        (let [encrypted-content (slurp test-secrets-file)]
          (is (not= plaintext encrypted-content)
              "Encrypted content should differ from plaintext"))

        ;; Delete plaintext
        (.delete (io/file test-plaintext-file))

        ;; Load secrets through our library
        (let [loaded (secrets/load-secrets test-key-file test-secrets-file)]
          ;; Verify all secrets loaded correctly
          (is (= test-secrets loaded)
              "Loaded secrets should match original")

          ;; Test get-secret with various paths
          (is (= "test_12345abcde"
                 (secrets/get-secret loaded [:plaid :client-id])))
          (is (= :sandbox
                 (secrets/get-secret loaded [:plaid :environment])))
          (is (= "YmFzZTY0LWVuY29kZWQta2V5LWhlcmU="
                 (secrets/get-secret loaded [:database :encryption-key])))))))

  (testing "Encryption preserves complex EDN data structures"
    (when (secrets/age-installed?)
      ;; Test with various EDN types
      (let [test-secrets {:string-val "test"
                          :number-val 42
                          :float-val 3.14
                          :boolean-val true
                          :nil-val nil
                          :keyword-val :test-keyword
                          :vector-val [1 2 3]
                          :set-val #{:a :b :c}
                          :map-val {:nested {:deeply {:value "here"}}}}]

        ;; Write, encrypt, decrypt cycle
        (spit test-plaintext-file (pr-str test-secrets))
        (shell/sh "age" "-e" "-i" test-key-file
                  "-o" test-secrets-file
                  test-plaintext-file)
        (.delete (io/file test-plaintext-file))

        ;; Load and verify all types preserved
        (let [loaded (secrets/load-secrets test-key-file test-secrets-file)]
          (is (= test-secrets loaded)
              "All EDN data types should be preserved"))))))

;; Error handling tests

(deftest error-handling-test
  (testing "Helpful error when age not installed"
    (with-redefs [secrets/age-installed? (constantly false)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"age encryption tool not found"
           (secrets/decrypt-file test-key-file test-secrets-file)))))

  (testing "Helpful error when key file missing"
    (when (secrets/age-installed?)
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Failed to load secrets"
           (secrets/load-secrets "/nonexistent/key.txt" test-secrets-file)))))

  (testing "Helpful error when secrets file missing"
    (when (secrets/age-installed?)
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Failed to load secrets"
           (secrets/load-secrets test-key-file "/nonexistent/secrets.edn.age")))))

  (testing "Error message includes helpful hints"
    (when (secrets/age-installed?)
      (try
        (secrets/load-secrets test-key-file "/nonexistent/secrets.edn.age")
        (is false "Should have thrown exception")
        (catch Exception e
          (let [data (ex-data e)
                cause (.getCause e)
                cause-data (when cause (ex-data cause))]
            ;; Either the top-level exception or the cause should have a hint
            (is (or (contains? data :hint)
                    (contains? cause-data :hint))
                "Exception should contain a helpful hint")
            ;; The cause should have the more specific hint
            (when cause-data
              (is (contains? cause-data :hint)))))))))

;; Edge cases

(deftest edge-cases-test
  (testing "Empty secrets map"
    (when (secrets/age-installed?)
      (let [test-secrets {}]
        (spit test-plaintext-file (pr-str test-secrets))
        (shell/sh "age" "-e" "-i" test-key-file
                  "-o" test-secrets-file
                  test-plaintext-file)
        (.delete (io/file test-plaintext-file))

        (let [loaded (secrets/load-secrets test-key-file test-secrets-file)]
          (is (= {} loaded))))))

  (testing "Secrets with special characters"
    (when (secrets/age-installed?)
      (let [test-secrets {:password "p@$$w0rd!#%&*"
                          :api-key "key-with-dashes-and_underscores_123"
                          :url "https://example.com/path?query=value&other=123"}]
        (spit test-plaintext-file (pr-str test-secrets))
        (shell/sh "age" "-e" "-i" test-key-file
                  "-o" test-secrets-file
                  test-plaintext-file)
        (.delete (io/file test-plaintext-file))

        (let [loaded (secrets/load-secrets test-key-file test-secrets-file)]
          (is (= test-secrets loaded))))))

  (testing "Very large secrets map"
    (when (secrets/age-installed?)
      (let [test-secrets (into {} (for [i (range 100)]
                                     [(keyword (str "key-" i))
                                      (str "value-" i)]))]
        (spit test-plaintext-file (pr-str test-secrets))
        (shell/sh "age" "-e" "-i" test-key-file
                  "-o" test-secrets-file
                  test-plaintext-file)
        (.delete (io/file test-plaintext-file))

        (let [loaded (secrets/load-secrets test-key-file test-secrets-file)]
          (is (= test-secrets loaded))
          (is (= 100 (count loaded))))))))

;; Path expansion tests

(deftest path-expansion-test
  (testing "Tilde expansion in key file path"
    (let [expanded (secrets/expand-home "~/.config/finance-aggregator/key.txt")
          home (System/getProperty "user.home")]
      (is (clojure.string/starts-with? expanded home))
      (is (clojure.string/ends-with? expanded ".config/finance-aggregator/key.txt"))))

  (testing "Tilde expansion in secrets file path"
    (let [expanded (secrets/expand-home "~/secrets/app.edn.age")
          home (System/getProperty "user.home")]
      (is (clojure.string/starts-with? expanded home))
      (is (clojure.string/ends-with? expanded "secrets/app.edn.age")))))

;; Comment form with REPL examples

(comment
  ;; Run all tests
  (clojure.test/run-tests 'finance-aggregator.lib.secrets-test)

  ;; Run specific test
  (clojure.test/test-var #'load-secrets-test)

  ;; Manual testing with real age encryption
  (require '[finance-aggregator.lib.secrets :as secrets])

  ;; Check if age is installed
  (secrets/age-installed?)

  ;; Test with actual key and secrets
  (def test-secrets (secrets/load-secrets
                     "~/.config/finance-aggregator/key.txt"
                     "backend/resources/secrets.edn.age"))

  ;; Get specific secrets
  (secrets/get-secret test-secrets :plaid)
  (secrets/get-secret test-secrets [:plaid :client-id]))

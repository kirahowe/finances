(ns finance-aggregator.lib.encryption-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.lib.encryption :as encryption]))

(deftest generate-encryption-key-test
  (testing "Generates a valid base64-encoded 256-bit key"
    (let [key (encryption/generate-encryption-key)]
      (is (string? key))
      (is (> (count key) 0))
      ;; Base64-encoded 32 bytes should be 44 characters (with padding)
      (is (= 44 (count key))))))

(deftest encrypt-decrypt-roundtrip-test
  (testing "Encrypt and decrypt roundtrip returns original plaintext"
    (let [encryption-key (encryption/generate-encryption-key)
          plaintext "access-token-secret-123"
          encrypted (encryption/encrypt-credential plaintext encryption-key)
          decrypted (encryption/decrypt-credential encrypted encryption-key)]
      (is (map? encrypted))
      (is (contains? encrypted :ciphertext))
      (is (contains? encrypted :iv))
      (is (string? (:ciphertext encrypted)))
      (is (string? (:iv encrypted)))
      (is (= plaintext decrypted)))))

(deftest encrypt-produces-different-ciphertext-test
  (testing "Encrypting same plaintext twice produces different ciphertext (due to random IV)"
    (let [encryption-key (encryption/generate-encryption-key)
          plaintext "same-token-every-time"
          encrypted1 (encryption/encrypt-credential plaintext encryption-key)
          encrypted2 (encryption/encrypt-credential plaintext encryption-key)]
      ;; Different IVs
      (is (not= (:iv encrypted1) (:iv encrypted2)))
      ;; Different ciphertexts
      (is (not= (:ciphertext encrypted1) (:ciphertext encrypted2)))
      ;; But both decrypt to same plaintext
      (is (= plaintext (encryption/decrypt-credential encrypted1 encryption-key)))
      (is (= plaintext (encryption/decrypt-credential encrypted2 encryption-key))))))

(deftest decrypt-with-wrong-key-fails-test
  (testing "Decrypting with wrong key throws exception"
    (let [key1 (encryption/generate-encryption-key)
          key2 (encryption/generate-encryption-key)
          plaintext "secret-token"
          encrypted (encryption/encrypt-credential plaintext key1)]
      (is (thrown? Exception
                   (encryption/decrypt-credential encrypted key2))))))

(deftest encrypt-various-token-formats-test
  (testing "Can encrypt various token formats"
    (let [encryption-key (encryption/generate-encryption-key)
          test-cases ["access-sandbox-abc123"
                      "access-development-xyz789"
                      "very-long-token-with-lots-of-characters-"
                      "token-with-special-chars-!@#$%^&*()_+"
                      "unicode-token-with-émojis-🔐🔑"]]
      (doseq [token test-cases]
        (let [encrypted (encryption/encrypt-credential token encryption-key)
              decrypted (encryption/decrypt-credential encrypted encryption-key)]
          (is (= token decrypted)
              (str "Failed roundtrip for: " token)))))))

(deftest encrypt-nil-plaintext-throws-test
  (testing "Encrypting nil plaintext throws exception"
    (let [encryption-key (encryption/generate-encryption-key)]
      (is (thrown? Exception
                   (encryption/encrypt-credential nil encryption-key))))))

(deftest encrypt-nil-key-throws-test
  (testing "Encrypting with nil key throws exception"
    (is (thrown? Exception
                 (encryption/encrypt-credential "token" nil)))))

(deftest decrypt-nil-encrypted-data-throws-test
  (testing "Decrypting nil encrypted data throws exception"
    (let [encryption-key (encryption/generate-encryption-key)]
      (is (thrown? Exception
                   (encryption/decrypt-credential nil encryption-key))))))

(deftest decrypt-missing-iv-throws-test
  (testing "Decrypting with missing IV throws exception"
    (let [encryption-key (encryption/generate-encryption-key)]
      (is (thrown? Exception
                   (encryption/decrypt-credential {:ciphertext "abc"} encryption-key))))))

(deftest decrypt-missing-ciphertext-throws-test
  (testing "Decrypting with missing ciphertext throws exception"
    (let [encryption-key (encryption/generate-encryption-key)]
      (is (thrown? Exception
                   (encryption/decrypt-credential {:iv "abc"} encryption-key))))))

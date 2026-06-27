(ns finance-aggregator.plaid.errors-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.plaid.errors :as errors]))

(deftest classify-transient-terminal-unknown
  (is (= :retry (errors/classify "INSTITUTION_DOWN")))
  (is (= :retry (errors/classify "RATE_LIMIT_EXCEEDED")))
  (is (= :reconnect (errors/classify "ITEM_LOGIN_REQUIRED")))
  (is (= :reconnect (errors/classify "PENDING_EXPIRATION")))
  (is (= :reset (errors/classify "TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION")))
  (is (= :fail (errors/classify "SOMETHING_NEW")))
  (is (= :fail (errors/classify nil))))

(deftest sync-error-prefers-the-failing-calls-code
  (testing "The error_code the call itself surfaced wins; /item/get isn't needed"
    (is (= {:action :retry :error-code "RATE_LIMIT_EXCEEDED" :error-message "slow down"}
           (errors/sync-error {:error-code "RATE_LIMIT_EXCEEDED" :error-message "slow down"}
                              nil "fallback")))))

(deftest sync-error-falls-back-to-item-error
  (testing "When the call carried no code, the /item/get supplement classifies"
    (is (= {:action :reconnect :error-code "ITEM_LOGIN_REQUIRED" :error-message "re-auth"}
           (errors/sync-error {} {:error-code "ITEM_LOGIN_REQUIRED" :error-message "re-auth"}
                              "fallback")))))

(deftest sync-error-login-repaired-is-resolved
  (is (= :resolved (:action (errors/sync-error {:error-code "LOGIN_REPAIRED"} nil "x")))))

(deftest sync-error-unknown-with-no-signal-fails-with-fallback-message
  (testing "No code anywhere -> :fail, surfacing the exception message"
    (is (= {:action :fail :error-code nil :error-message "boom"}
           (errors/sync-error {} nil "boom")))))

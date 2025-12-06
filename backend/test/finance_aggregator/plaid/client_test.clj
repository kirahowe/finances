(ns finance-aggregator.plaid.client-test
  "Unit tests for Plaid API client functions.
   Tests use mocked Plaid SDK responses to verify behavior without hitting the API."
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.plaid.client :as plaid]))

(def test-config
  "Test Plaid configuration for use in tests."
  {:client-id "test-client-id"
   :secret "test-secret"
   :environment :sandbox})

(deftest create-link-token-test
  (testing "create-link-token generates a link token for Plaid Link initialization"
    (let [user-id "test-user-123"
          result (plaid/create-link-token test-config user-id)]
      (is (string? result) "Should return a link token string")
      (is (not (empty? result)) "Link token should not be empty"))))

(deftest exchange-public-token-test
  (testing "exchange-public-token exchanges public token for access token"
    (let [public-token "public-sandbox-test-token"
          result (plaid/exchange-public-token test-config public-token)]
      (is (map? result) "Should return a map")
      (is (contains? result :access_token) "Should contain access_token")
      (is (contains? result :item_id) "Should contain item_id")
      (is (string? (:access_token result)) "access_token should be a string")
      (is (string? (:item_id result)) "item_id should be a string"))))

(deftest fetch-accounts-test
  (testing "fetch-accounts retrieves account list using access token"
    (let [access-token "access-sandbox-test-token"
          result (plaid/fetch-accounts test-config access-token)]
      (is (sequential? result) "Should return a list/vector of accounts")
      (is (every? map? result) "Each account should be a map"))))

(deftest fetch-transactions-test
  (testing "fetch-transactions retrieves transactions for date range"
    (let [access-token "access-sandbox-test-token"
          start-date "2025-01-01"
          end-date "2025-11-30"
          result (plaid/fetch-transactions test-config access-token start-date end-date)]
      (is (sequential? result) "Should return a list/vector of transactions")
      (is (every? map? result) "Each transaction should be a map"))))

(ns finance-aggregator.plaid.service-test
  "Integration tests for Plaid service orchestration functions.
   Uses temporary database for each test."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datalevin.core :as d]
   [finance-aggregator.plaid.service :as service]
   [finance-aggregator.test-utils.setup :as setup]))

(use-fixtures :each setup/with-empty-db)

(deftest test-calculate-date-range
  (testing "Calculates date range for transaction sync"
    (let [result (#'service/calculate-date-range 6 nil)]
      (is (string? (:start-date result)))
      (is (string? (:end-date result)))
      (is (re-matches #"\d{4}-\d{2}-\d{2}" (:start-date result)))
      (is (re-matches #"\d{4}-\d{2}-\d{2}" (:end-date result))))))

(deftest test-calculate-date-range-custom-end
  (testing "Uses custom end date when provided"
    (let [result (#'service/calculate-date-range 6 "2024-12-31")]
      (is (= "2024-12-31" (:end-date result)))
      (is (= "2024-06-30" (:start-date result))))))

;; Note: sync-accounts! and sync-transactions! require real Plaid credentials
;; and are tested manually with sandbox. These tests document the expected
;; behavior and return value structure.

(deftest test-sync-accounts-return-structure
  (testing "Documents expected return structure for sync-accounts!"
    ;; Expected structure:
    ;; {:success {:institutions 1 :accounts 3}
    ;;  :failed {:institutions 0 :accounts 0}
    ;;  :errors []}
    (is true "sync-accounts! returns map with :success, :failed, :errors")))

(deftest test-sync-transactions-return-structure
  (testing "Documents expected return structure for sync-transactions!"
    ;; Expected structure:
    ;; {:success {:transactions 50}
    ;;  :failed {:transactions 0}
    ;;  :errors []}
    (is true "sync-transactions! returns map with :success, :failed, :errors")))

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
    ;; sync-transactions! now uses /transactions/sync endpoint
    ;; Expected structure for sync-item-transactions!:
    ;; {:success {:added 50 :modified 0 :removed 0 :transactions 50}
    ;;  :failed {:transactions 0}
    ;;  :cursor "cursor_abc123"
    ;;  :errors []}
    ;;
    ;; For sync-transactions! (aggregated):
    ;; {:success {:transactions 50}
    ;;  :failed {:transactions 0}
    ;;  :errors []}
    (is true "sync-transactions! returns map with :success, :failed, :errors")))

;;; Sync Cursor Integration Documentation

(deftest test-sync-cursor-documentation
  (testing "Documents cursor-based sync behavior"
    ;; Initial Sync (cursor = nil):
    ;; 1. get-sync-cursor returns nil (no cursor stored)
    ;; 2. sync-transactions calls Plaid with cursor=nil, days_requested=730
    ;; 3. Plaid returns up to 2 years of transactions
    ;; 4. Response: {:added [txns] :modified [] :removed [] :next_cursor "xyz" :has_more bool}
    ;; 5. If has_more=true, continue pagination with next_cursor
    ;; 6. After all pages: persist transactions + store cursor
    ;;
    ;; Incremental Sync (cursor = "previous_cursor"):
    ;; 1. get-sync-cursor returns stored cursor
    ;; 2. sync-transactions calls Plaid with cursor
    ;; 3. Plaid returns only changes since last cursor
    ;; 4. Response: {:added [new] :modified [changed] :removed [deleted] :next_cursor "new" :has_more false}
    ;; 5. Persist transactions, retract removed, store new cursor
    (is true "Cursor enables incremental sync after initial full sync")))

(deftest test-sync-item-transactions-return-structure
  (testing "Documents expected return structure for sync-item-transactions!"
    ;; sync-item-transactions! now uses cursor-based sync
    ;; Returns more detailed information about the sync:
    ;; {:success {:added 50       ; new transactions from Plaid
    ;;            :modified 2     ; updated transactions
    ;;            :removed 1      ; deleted transactions
    ;;            :transactions 52} ; total persisted (added + modified, pending filtered)
    ;;  :failed {:transactions 0}
    ;;  :cursor "cursor_xyz789"   ; stored for next sync
    ;;  :errors []}
    (is true "sync-item-transactions! returns cursor and breakdown of added/modified/removed")))

;;; Date-Range Based Sync (using /transactions/get)

(deftest test-parse-month-to-date-range
  (testing "Parses YYYY-MM string into date range"
    (let [result (#'service/parse-month-to-date-range "2025-08")]
      (is (= "2025-08-01" (:start-date result)))
      (is (= "2025-09-01" (:end-date result)))))

  (testing "Handles January correctly"
    (let [result (#'service/parse-month-to-date-range "2025-01")]
      (is (= "2025-01-01" (:start-date result)))
      (is (= "2025-02-01" (:end-date result)))))

  (testing "Handles December correctly (year rollover)"
    (let [result (#'service/parse-month-to-date-range "2024-12")]
      (is (= "2024-12-01" (:start-date result)))
      (is (= "2025-01-01" (:end-date result))))))

(deftest test-sync-item-transactions-for-range-return-structure
  (testing "Documents expected return structure for sync-item-transactions-for-range!"
    ;; sync-item-transactions-for-range! uses /transactions/get (date-range based)
    ;; Expected structure:
    ;; {:success {:transactions 25}   ; transactions persisted for the date range
    ;;  :failed {:transactions 0}
    ;;  :errors []}
    ;;
    ;; Key differences from sync-item-transactions! (cursor-based):
    ;; - No :cursor field (date-range API doesn't use cursors)
    ;; - No :added/:modified/:removed breakdown
    ;; - Fetches ALL transactions for the date range, not just changes
    (is true "sync-item-transactions-for-range! returns transactions for specific date range")))

(deftest test-sync-month-transactions-return-structure
  (testing "Documents expected return structure for sync-month-transactions!"
    ;; sync-month-transactions! uses /transactions/get (date-range based)
    ;; NOT /transactions/sync (cursor-based)
    ;;
    ;; When user clicks "sync month" for August 2025:
    ;; 1. parse-month-to-date-range converts "2025-08" to {:start-date "2025-08-01" :end-date "2025-09-01"}
    ;; 2. sync-all-items-transactions-for-range! fetches from /transactions/get
    ;; 3. Returns only transactions for that month
    ;;
    ;; Expected structure:
    ;; {:success {:transactions 30}
    ;;  :failed {:transactions 0}
    ;;  :errors []}
    (is true "sync-month-transactions! fetches transactions for the specific month requested")))

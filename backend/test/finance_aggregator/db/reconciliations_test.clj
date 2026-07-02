(ns finance-aggregator.db.reconciliations-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [finance-aggregator.db.reconciliations :as recon]
   [finance-aggregator.test-utils.setup :as setup])
  (:import
   [java.util Date]))

(use-fixtures :each setup/with-empty-db)

(def ^:private totals {:income (bigdec "4000") :expenses (bigdec "-2135")
                       :transfers (bigdec "0") :net (bigdec "1865")})

(deftest close-freezes-totals-and-marks-closed
  (testing "closing a month stores the frozen totals and reads back as closed"
    (is (false? (recon/closed? setup/*test-conn* "2026-03")))
    (let [closed-at (Date. 1770000000000)
          e (recon/close-month! setup/*test-conn* "2026-03" totals closed-at)]
      (is (true? (recon/closed? setup/*test-conn* "2026-03")))
      (is (= "2026-03" (:reconciliation/month e)))
      (is (= closed-at (:reconciliation/closed-at e)))
      (is (= (bigdec "4000") (:reconciliation/income e)))
      (is (= (bigdec "1865") (:reconciliation/net e))))))

(deftest get-close-nil-when-open
  (is (nil? (recon/get-close setup/*test-conn* "2026-03"))))

(deftest close-is-idempotent-and-overwrites
  (testing "re-closing overwrites the frozen totals rather than duplicating"
    (recon/close-month! setup/*test-conn* "2026-03" totals (Date. 1770000000000))
    (recon/close-month! setup/*test-conn* "2026-03"
                        (assoc totals :net (bigdec "2000")) (Date. 1770100000000))
    (is (= (bigdec "2000") (:reconciliation/net (recon/get-close setup/*test-conn* "2026-03"))))
    (is (= 1 (count (recon/list-closes setup/*test-conn*))))))

(deftest reopen-unlocks-the-month
  (recon/close-month! setup/*test-conn* "2026-03" totals (Date. 1770000000000))
  (recon/reopen-month! setup/*test-conn* "2026-03")
  (is (false? (recon/closed? setup/*test-conn* "2026-03")))
  (testing "reopening an open month is a no-op"
    (is (some? (recon/reopen-month! setup/*test-conn* "2026-03")))))

(deftest list-closes-is-most-recent-first
  (recon/close-month! setup/*test-conn* "2026-01" totals (Date. 1770000000000))
  (recon/close-month! setup/*test-conn* "2026-03" totals (Date. 1770000000000))
  (recon/close-month! setup/*test-conn* "2026-02" totals (Date. 1770000000000))
  (is (= ["2026-03" "2026-02" "2026-01"]
         (map :reconciliation/month (recon/list-closes setup/*test-conn*)))))

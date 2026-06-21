(ns finance-aggregator.web.month-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.web.month :as month]))

(deftest parse-month
  (testing "valid YYYY-MM"
    (is (= {:year 2025 :month 1} (month/parse "2025-01")))
    (is (= {:year 2025 :month 12} (month/parse "2025-12"))))
  (testing "invalid / blank / out-of-range falls back to the current month"
    (is (= (month/current) (month/parse nil)))
    (is (= (month/current) (month/parse "")))
    (is (= (month/current) (month/parse "garbage")))
    (is (= (month/current) (month/parse "2025-13")) "month 13 is out of range")
    (is (= (month/current) (month/parse "2025-00")) "month 0 is out of range")
    (is (= (month/current) (month/parse "25-1")) "wrong shape")))

(deftest serialize-month
  (is (= "2025-01" (month/serialize {:year 2025 :month 1})) "zero-padded month")
  (is (= "2025-12" (month/serialize {:year 2025 :month 12})))
  (is (= "0099-03" (month/serialize {:year 99 :month 3})) "zero-padded year"))

(deftest display-month
  (is (= "January 2025" (month/display {:year 2025 :month 1})))
  (is (= "December 2025" (month/display {:year 2025 :month 12}))))

(deftest prev-and-next-month
  (testing "mid-year"
    (is (= {:year 2025 :month 5} (month/prev-month {:year 2025 :month 6})))
    (is (= {:year 2025 :month 7} (month/next-month {:year 2025 :month 6}))))
  (testing "year boundaries"
    (is (= {:year 2024 :month 12} (month/prev-month {:year 2025 :month 1})) "Jan → prev Dec")
    (is (= {:year 2026 :month 1} (month/next-month {:year 2025 :month 12})) "Dec → next Jan")))

(deftest round-trip
  (is (= {:year 2025 :month 7} (month/parse (month/serialize {:year 2025 :month 7})))))

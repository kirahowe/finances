(ns finance-aggregator.utils.month-parse-test
  "Tests for parsing YYYY-MM strings into date ranges for queries."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [finance-aggregator.utils :as utils]
   [finance-aggregator.test-utils.setup :as setup])
  (:import [java.util Date]))

;; Generators
(def year-gen (gen/choose 1970 2100))
(def month-gen (gen/choose 1 12))
(def year-month-gen (gen/tuple year-gen month-gen))

;; Unit tests for parse-month-string
(deftest parse-month-string-test
  (testing "parses valid YYYY-MM string into year and month"
    (is (= {:year 2025 :month 1} (utils/parse-month-string "2025-01")))
    (is (= {:year 2024 :month 12} (utils/parse-month-string "2024-12")))
    (is (= {:year 1970 :month 6} (utils/parse-month-string "1970-06"))))

  (testing "throws on invalid format"
    (is (thrown? Exception (utils/parse-month-string "2025-1")))
    (is (thrown? Exception (utils/parse-month-string "2025")))
    (is (thrown? Exception (utils/parse-month-string "invalid")))
    (is (thrown? Exception (utils/parse-month-string "")))
    (is (thrown? Exception (utils/parse-month-string nil))))

  (testing "throws on invalid month values"
    (is (thrown? Exception (utils/parse-month-string "2025-00")))
    (is (thrown? Exception (utils/parse-month-string "2025-13")))))

;; Unit tests for month-date-range
(deftest month-date-range-test
  (testing "returns java.util.Date instances"
    (let [{:keys [start-date end-date]} (utils/month-date-range "2025-01")]
      (is (instance? Date start-date))
      (is (instance? Date end-date))))

  (testing "start is before end"
    (let [{:keys [start-date end-date]} (utils/month-date-range "2025-01")]
      (is (.before start-date end-date))))

  (testing "January 2025 has correct boundaries"
    (let [{:keys [start-date end-date]} (utils/month-date-range "2025-01")]
      ;; January 1, 2025 00:00:00 UTC = 1735689600000 ms
      ;; February 1, 2025 00:00:00 UTC = 1738368000000 ms
      (is (= 1735689600000 (.getTime start-date)))
      (is (= 1738368000000 (.getTime end-date))))))

;; Property tests
(defspec month-date-range-start-before-end setup/test-times
  (prop/for-all [[year month] year-month-gen]
    (let [month-str (format "%04d-%02d" year month)
          {:keys [start-date end-date]} (utils/month-date-range month-str)]
      (.before start-date end-date))))

(defspec month-date-range-matches-epoch-bounds setup/test-times
  (prop/for-all [[year month] year-month-gen]
    (let [month-str (format "%04d-%02d" year month)
          {:keys [start-date end-date]} (utils/month-date-range month-str)
          {:keys [start end]} (utils/month-epoch-bounds year month)]
      ;; epoch bounds are in seconds, Date.getTime() returns milliseconds
      (and (= (* start 1000) (.getTime start-date))
           (= (* end 1000) (.getTime end-date))))))

(defspec roundtrip-format-parse setup/test-times
  (prop/for-all [[year month] year-month-gen]
    (let [month-str (format "%04d-%02d" year month)
          {:keys [year month]} (utils/parse-month-string month-str)]
      (= month-str (format "%04d-%02d" year month)))))

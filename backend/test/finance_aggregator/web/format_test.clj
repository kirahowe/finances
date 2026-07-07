(ns finance-aggregator.web.format-test
  (:require
   [clojure.test :refer [deftest is]]
   [finance-aggregator.web.format :as fmt]))

(deftest amount-cad
  (is (= "$4,000.00" (fmt/amount 4000.00M)) "positive CAD")
  (is (= "-$85.00" (fmt/amount -85.00M)) "negative")
  (is (= "$0.00" (fmt/amount 0M))))

(deftest date-utc
  (is (= "Jan 5, 2025" (fmt/date #inst "2025-01-05T00:00:00.000-00:00")))
  (is (= "Dec 31, 2024" (fmt/date #inst "2024-12-31T00:00:00.000-00:00"))))

(deftest date-short-drops-year
  (is (= "Jan 5" (fmt/date-short #inst "2025-01-05T00:00:00.000-00:00")))
  (is (= "Dec 31" (fmt/date-short #inst "2024-12-31T00:00:00.000-00:00"))))

(deftest date-span-carries-year
  (is (= "Dec 28 – Jan 27, 2025"
         (fmt/date-span #inst "2025-12-28T00:00:00.000-00:00"
                        #inst "2025-01-27T00:00:00.000-00:00"))
      "same-year span collapses to one trailing year")
  (is (= "Dec 28, 2024 – Jan 27, 2025"
         (fmt/date-span #inst "2024-12-28T00:00:00.000-00:00"
                        #inst "2025-01-27T00:00:00.000-00:00"))
      "cross-year span spells out both years"))

(deftest integer-grouped
  (is (= "1,234" (fmt/integer 1234)))
  (is (= "0" (fmt/integer 0)))
  (is (= "1,000,000" (fmt/integer 1000000))))

(deftest relative-time-humanizes
  (let [now (java.util.Date. 1700000000000)
        ago (fn [ms] (java.util.Date. (- 1700000000000 ms)))]
    (is (= "never" (fmt/relative-time nil now)))
    (is (= "just now" (fmt/relative-time (ago 5000) now)))
    (is (= "3 min ago" (fmt/relative-time (ago (* 3 60 1000)) now)))
    (is (= "5 hr ago" (fmt/relative-time (ago (* 5 60 60 1000)) now)))
    (is (= "1 day ago" (fmt/relative-time (ago (* 24 60 60 1000)) now)))
    (is (= "2 days ago" (fmt/relative-time (ago (* 2 24 60 60 1000)) now)))
    ;; A week or older falls back to the absolute date.
    (is (= (fmt/date (ago (* 30 24 60 60 1000)))
           (fmt/relative-time (ago (* 30 24 60 60 1000)) now)))))

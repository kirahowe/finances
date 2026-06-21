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

(deftest integer-grouped
  (is (= "1,234" (fmt/integer 1234)))
  (is (= "0" (fmt/integer 0)))
  (is (= "1,000,000" (fmt/integer 1000000))))

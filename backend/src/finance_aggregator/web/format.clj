(ns finance-aggregator.web.format
  "Presentation formatters for server-rendered views."
  (:require
   [finance-aggregator.utils :as utils])
  (:import
   [java.text NumberFormat]
   [java.util Date Locale]
   [java.util.function Supplier]))

;; NumberFormat is not thread-safe and http-kit serves requests concurrently, so
;; hand each thread its own instance rather than sharing one.
(def ^:private ^ThreadLocal currency-tl
  (ThreadLocal/withInitial
   (reify Supplier
     (get [_] (NumberFormat/getCurrencyInstance Locale/CANADA)))))

(defn amount
  "Format a signed amount as CAD currency (e.g. \"$4,000.00\", \"-$85.00\";
   Intl en-CA / CAD). Accepts a BigDecimal (the stored amount type) and formats
   it without precision loss."
  [amt]
  (.format ^NumberFormat (.get currency-tl) ^Object amt))

(def ^:private month-abbrevs
  ["Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"])

(defn date
  "Format a java.util.Date as \"Mon D, YYYY\" in UTC."
  [^Date d]
  (let [^java.time.LocalDate ld (utils/date->local-date d)]
    (str (nth month-abbrevs (dec (.getMonthValue ld))) " "
         (.getDayOfMonth ld) ", " (.getYear ld))))

(defn integer
  "Format an integer with grouping separators (e.g. 1,234). Shared by the masthead stats
   and the /setup stat cards."
  [n]
  (format "%,d" (long n)))

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

(defn relative-time
  "Humanize a past instant `d` relative to `now` (both java.util.Date): \"just now\",
   \"3 min ago\", \"5 hr ago\", \"2 days ago\", else the absolute date for anything a
   week or older. nil -> \"never\". `now` is passed in so callers stay testable."
  [^Date d ^Date now]
  (if (nil? d)
    "never"
    (let [secs (quot (- (.getTime now) (.getTime d)) 1000)
          mins (quot secs 60)
          hrs  (quot mins 60)
          days (quot hrs 24)]
      (cond
        (< secs 45) "just now"
        (< mins 60) (str mins " min ago")
        (< hrs 24)  (str hrs " hr ago")
        (< days 7)  (str days (if (= 1 days) " day ago" " days ago"))
        :else       (date d)))))

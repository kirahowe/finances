(ns finance-aggregator.utils
  (:require
   [tick.core :as t])
  (:import [java.security MessageDigest]
           [java.time LocalDate ZoneId ZoneOffset]
           [java.time.format DateTimeFormatter]
           [java.util Date]
           [java.util.regex Pattern]))

(defn epoch->date
  "Converts Unix epoch timestamp to a date in UTC."
  [timestamp]
  (-> timestamp (* 1000) t/instant Date/from))

(defn- local-date->utc-midnight
  "java.util.Date at UTC midnight for a LocalDate."
  ^Date [^LocalDate ld]
  (-> ld (.atStartOfDay (ZoneId/of "UTC")) .toInstant Date/from))

(defn string->date
  "Parse a date string to a java.util.Date at UTC midnight.
   1-arity parses ISO yyyy-MM-dd; 2-arity uses an explicit
   DateTimeFormatter pattern (e.g. \"yyyy-MM-dd\")."
  ([date-string]
   (local-date->utc-midnight (LocalDate/parse date-string)))
  ([date-string date-format]
   (local-date->utc-midnight
    (LocalDate/parse date-string (DateTimeFormatter/ofPattern date-format)))))

(defn sha256-hex
  "Hex-encoded SHA-256 digest of (str s). Used for deterministic external-ids."
  [s]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %)
                    (.digest digest (.getBytes (str s) "UTF-8"))))))

(defn date->epoch-day
  "Calendar day (as an epoch-day long) of a java.util.Date interpreted in UTC, or
   nil when the date is nil. Use to bucket transactions by day independent of
   time-of-day."
  [^Date d]
  (when d
    (.toEpochDay (.toLocalDate (.atZone (.toInstant d) ZoneOffset/UTC)))))

(defn- ->epoch
  "Convert an Instant to epoch."
  [calendar-aware-timestamp]
  (-> calendar-aware-timestamp t/instant t/long))

(defn month-epoch-bounds
  "Given year & month (1–12), return {:start epoch-timestamp :end epoch-timestamp}
  (aligned to UTC month starts) with semantics matching SimpleFIN protocol
  (see https://www.simplefin.org/protocol.html), i.e. start timestamp is
  inclusive, end timestamp is exclusive."
  [year month]
  (let [start (-> (t/new-date year month 1)
                  (t/at (t/midnight))
                  (t/in "UTC"))
        end (t/>> start (t/of-months 1))]
    {:start (->epoch start)
     :end (->epoch end)}))

(def ^:private month-pattern (Pattern/compile "^(\\d{4})-(\\d{2})$"))

(defn parse-month-string
  "Parse a YYYY-MM string into {:year int :month int}.
   Throws an exception if the format is invalid or month is out of range."
  [month-str]
  (when (or (nil? month-str) (empty? month-str))
    (throw (ex-info "Month string cannot be nil or empty"
                    {:type :invalid-month :input month-str})))
  (let [matcher (re-matcher month-pattern month-str)]
    (if (.matches matcher)
      (let [year (parse-long (.group matcher 1))
            month (parse-long (.group matcher 2))]
        (when (or (< month 1) (> month 12))
          (throw (ex-info "Month must be between 01 and 12"
                          {:type :invalid-month :input month-str :month month})))
        {:year year :month month})
      (throw (ex-info "Invalid month format. Expected YYYY-MM"
                      {:type :invalid-month :input month-str})))))

(defn month-date-range
  "Convert a YYYY-MM string into a date range for database queries.
   Returns {:start-date Date :end-date Date} where:
   - start-date is inclusive (first millisecond of the month)
   - end-date is exclusive (first millisecond of the next month)"
  [month-str]
  (let [{:keys [year month]} (parse-month-string month-str)
        {:keys [start end]} (month-epoch-bounds year month)]
    {:start-date (Date. (* start 1000))
     :end-date (Date. (* end 1000))}))

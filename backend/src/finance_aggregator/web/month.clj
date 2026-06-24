(ns finance-aggregator.web.month
  "Month view-state helpers for the transactions page: a month is
   {:year int :month 1-12}, serialized as YYYY-MM."
  (:import [java.time LocalDate ZoneOffset]))

(def ^:private names
  ["January" "February" "March" "April" "May" "June"
   "July" "August" "September" "October" "November" "December"])

(defn current
  "The current calendar month (UTC)."
  []
  (let [d (LocalDate/now ZoneOffset/UTC)]
    {:year (.getYear d) :month (.getMonthValue d)}))

(defn parse
  "Parse a YYYY-MM param into {:year :month}, falling back to the current month for
   nil/blank/invalid input (matching parseMonthParam)."
  [param]
  (or (when param
        (when-let [[_ y m] (re-matches #"(\d{4})-(\d{2})" param)]
          (let [mm (parse-long m)]
            (when (<= 1 mm 12) {:year (parse-long y) :month mm}))))
      (current)))

(defn serialize
  "Serialize {:year :month} to YYYY-MM."
  [{:keys [year month]}]
  (format "%04d-%02d" year month))

(defn display
  "Human label, e.g. \"January 2025\"."
  [{:keys [year month]}]
  (str (nth names (dec month)) " " year))

(defn next-month [{:keys [year month]}]
  (if (= month 12) {:year (inc year) :month 1} {:year year :month (inc month)}))

(defn prev-month [{:keys [year month]}]
  (if (= month 1) {:year (dec year) :month 12} {:year year :month (dec month)}))

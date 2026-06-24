(ns finance-aggregator.csv.data
  "CSV parsing and column detection using Tablecloth.

   High-performance CSV parsing with automatic column detection for:
   - Date columns (various formats)
   - Amount columns (with currency symbols, commas, negatives)
   - Payee/merchant columns
   - Description/memo columns"
  (:require
   [clojure.string :as str]
   [tablecloth.api :as tc])
  (:import
   [java.io StringReader]
   [java.time LocalDate]
   [java.time.format DateTimeFormatter]))

;;; CSV Parsing with Tablecloth

(defn parse-csv
  "Parse CSV string into Tablecloth dataset.

   csv-str: CSV content as string

   Returns: Tablecloth dataset"
  [csv-str]
  (with-open [reader (StringReader. csv-str)]
    ;; parser-fn :string keeps all columns as strings
    (tc/dataset reader {:file-type :csv :parser-fn :string})))

(defn row-count
  "Get number of rows in dataset."
  [ds]
  (tc/row-count ds))

(defn column-names
  "Get column names from dataset."
  [ds]
  (tc/column-names ds))

(defn get-column-samples
  "Get sample values from a column in the dataset.

   ds: Tablecloth dataset
   column-name: Name of column
   n: Number of samples to return

   Returns: Sequence of sample values as strings"
  [ds column-name n]
  (->> (get ds column-name)
       (take n)
       (remove nil?)))

;;; Column Detection Heuristics

(def ^:private date-keywords
  "Keywords that indicate a date column"
  #{"date" "posted" "transaction" "posting"})

(def ^:private amount-keywords
  "Keywords that indicate an amount column"
  #{"amount" "debit" "credit" "value" "total"})

(def ^:private payee-keywords
  "Keywords that indicate a payee/merchant column"
  #{"description" "payee" "merchant" "name" "vendor"})

(def ^:private memo-keywords
  "Keywords that indicate a memo/details column"
  #{"memo" "note" "details" "comment"})

(defn- column-matches-keywords?
  "Check if a column name matches any of the given keywords."
  [column-name keywords]
  (let [normalized (-> column-name str/lower-case (str/replace #"[_\s]+" ""))]
    (some #(str/includes? normalized %) keywords)))

(defn detect-date-column
  "Detect which column is the date column from headers.

   headers: Sequence of column name strings

   Returns: Column name or nil"
  [headers]
  (first (filter #(column-matches-keywords? % date-keywords) headers)))

(defn detect-amount-column
  "Detect which column is the amount column from headers.

   headers: Sequence of column name strings

   Returns: Column name or nil"
  [headers]
  (first (filter #(column-matches-keywords? % amount-keywords) headers)))

(defn detect-payee-column
  "Detect which column is the payee/merchant column from headers.

   headers: Sequence of column name strings

   Returns: Column name or nil"
  [headers]
  (first (filter #(column-matches-keywords? % payee-keywords) headers)))

(defn detect-description-column
  "Detect which column is the description/memo column from headers.

   headers: Sequence of column name strings
   excluded: Column names already assigned to other roles

   Returns: Column name or nil"
  [headers excluded]
  (let [excluded-set (set excluded)]
    (first (filter #(and (column-matches-keywords? % memo-keywords)
                        (not (excluded-set %)))
                  headers))))

(defn detect-columns
  "Detect all column mappings from headers.

   headers: Sequence of column name strings

   Returns: Map with keys :date :amount :payee :description"
  [headers]
  (let [date-col (detect-date-column headers)
        amount-col (detect-amount-column headers)
        payee-col (detect-payee-column headers)
        description-col (detect-description-column headers [date-col amount-col payee-col])]
    (cond-> {}
      date-col (assoc :date date-col)
      amount-col (assoc :amount amount-col)
      payee-col (assoc :payee payee-col)
      description-col (assoc :description description-col))))

;;; Date Format Detection

(def ^:private common-date-formats
  "Common date formats to try, in order of specificity"
  ["yyyy-MM-dd"
   "MM/dd/yyyy"
   "dd/MM/yyyy"
   "yyyy/MM/dd"
   "M/d/yyyy"
   "d/M/yyyy"
   "yyyy-M-d"
   "MMM dd, yyyy"
   "dd-MMM-yyyy"])

(defn- try-parse-date
  "Try to parse a date string with the given format.
   Returns true if successful, false otherwise."
  [date-str format-str]
  (try
    (let [formatter (DateTimeFormatter/ofPattern format-str)
          ;; Ensure date-str is a string
          str-value (str date-str)]
      (LocalDate/parse str-value formatter)
      true)
    (catch Exception _
      false)))

(defn detect-date-format
  "Detect the date format from sample date strings.

   samples: Sequence of date strings

   Returns: Format string (e.g., 'yyyy-MM-dd') or nil"
  [samples]
  (when (seq samples)
    ;; Try each format and see if it works for all samples
    (some (fn [format-str]
            (when (every? #(try-parse-date % format-str) samples)
              format-str))
          common-date-formats)))

;;; CSV Preview

(defn preview-csv
  "Preview CSV and detect column mappings.

   csv-str: CSV content as string
   sample-size: Number of rows to include in preview

   Returns: Map with:
            :headers - Sequence of column names
            :sample-rows - Sequence of row maps
            :total-rows - Total number of rows
            :detected-mapping - Map of detected column roles
            :suggested-date-format - Detected date format"
  [csv-str sample-size]
  (let [ds (parse-csv csv-str)
        headers (column-names ds)
        total-rows (row-count ds)
        sample-ds (tc/head ds (min sample-size total-rows))
        sample-rows (tc/rows sample-ds :as-maps)
        detected-mapping (detect-columns headers)
        date-col (:date detected-mapping)
        date-samples (when date-col
                       (get-column-samples ds date-col 10))
        suggested-format (when date-samples
                           (detect-date-format date-samples))]
    {:headers headers
     :sample-rows sample-rows
     :total-rows total-rows
     :detected-mapping detected-mapping
     :suggested-date-format suggested-format}))

(ns finance-aggregator.csv.data-test
  "Unit tests for CSV parsing and column detection functions."
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.csv.data :as csv-data]))

(deftest test-parse-csv-string
  (testing "Parses CSV string into dataset"
    (let [csv-str "Date,Description,Amount\n2024-01-15,Coffee Shop,4.50\n2024-01-16,Grocery Store,45.67"
          result (csv-data/parse-csv csv-str)]
      (is (some? result))
      (is (= 2 (csv-data/row-count result)))
      (is (= ["Date" "Description" "Amount"] (csv-data/column-names result)))))

  (testing "Handles CSV with quoted fields"
    (let [csv-str "Date,Description,Amount\n2024-01-15,\"Coffee Shop, Downtown\",4.50"
          result (csv-data/parse-csv csv-str)]
      (is (= 1 (csv-data/row-count result)))))

  (testing "Handles empty CSV"
    (let [csv-str "Date,Description,Amount"
          result (csv-data/parse-csv csv-str)]
      (is (= 0 (csv-data/row-count result))))))

(deftest test-detect-date-column
  (testing "Detects date column from headers"
    (let [headers ["Date" "Description" "Amount"]]
      (is (= "Date" (csv-data/detect-date-column headers))))

    (let [headers ["Transaction Date" "Payee" "Amount"]]
      (is (= "Transaction Date" (csv-data/detect-date-column headers))))

    (let [headers ["Posting Date" "Description" "Debit"]]
      (is (= "Posting Date" (csv-data/detect-date-column headers)))))

  (testing "Returns nil when no date column found"
    (let [headers ["Description" "Amount"]]
      (is (nil? (csv-data/detect-date-column headers))))))

(deftest test-detect-amount-column
  (testing "Detects amount column from headers"
    (let [headers ["Date" "Description" "Amount"]]
      (is (= "Amount" (csv-data/detect-amount-column headers))))

    (let [headers ["Date" "Payee" "Debit"]]
      (is (= "Debit" (csv-data/detect-amount-column headers))))

    (let [headers ["Date" "Description" "Credit"]]
      (is (= "Credit" (csv-data/detect-amount-column headers)))))

  (testing "Returns nil when no amount column found"
    (let [headers ["Date" "Description"]]
      (is (nil? (csv-data/detect-amount-column headers))))))

(deftest test-detect-payee-column
  (testing "Detects payee/description column from headers"
    (let [headers ["Date" "Description" "Amount"]]
      (is (= "Description" (csv-data/detect-payee-column headers))))

    (let [headers ["Date" "Payee" "Amount"]]
      (is (= "Payee" (csv-data/detect-payee-column headers))))

    (let [headers ["Date" "Merchant" "Amount"]]
      (is (= "Merchant" (csv-data/detect-payee-column headers))))

    (let [headers ["Date" "Name" "Amount"]]
      (is (= "Name" (csv-data/detect-payee-column headers)))))

  (testing "Returns nil when no payee column found"
    (let [headers ["Date" "Amount"]]
      (is (nil? (csv-data/detect-payee-column headers))))))

(deftest test-detect-columns
  (testing "Detects all columns from headers"
    (let [headers ["Date" "Description" "Amount" "Memo"]
          result (csv-data/detect-columns headers)]
      (is (= "Date" (:date result)))
      (is (= "Amount" (:amount result)))
      (is (= "Description" (:payee result)))
      (is (= "Memo" (:description result)))))

  (testing "Returns partial mapping when some columns not found"
    (let [headers ["Date" "Description"]
          result (csv-data/detect-columns headers)]
      (is (= "Date" (:date result)))
      (is (= "Description" (:payee result)))
      (is (nil? (:amount result))))))

(deftest test-detect-date-format
  (testing "Detects yyyy-MM-dd format"
    (let [samples ["2024-01-15" "2024-01-16" "2024-01-17"]]
      (is (= "yyyy-MM-dd" (csv-data/detect-date-format samples)))))

  (testing "Detects MM/dd/yyyy format"
    (let [samples ["01/15/2024" "01/16/2024" "01/17/2024"]]
      (is (= "MM/dd/yyyy" (csv-data/detect-date-format samples)))))

  (testing "Detects dd/MM/yyyy format"
    (let [samples ["15/01/2024" "16/01/2024" "17/01/2024"]]
      (is (= "dd/MM/yyyy" (csv-data/detect-date-format samples)))))

  (testing "Detects yyyy/MM/dd format"
    (let [samples ["2024/01/15" "2024/01/16" "2024/01/17"]]
      (is (= "yyyy/MM/dd" (csv-data/detect-date-format samples)))))

  (testing "Returns nil when no format matches"
    (let [samples ["invalid" "not-a-date"]]
      (is (nil? (csv-data/detect-date-format samples))))))

(deftest test-get-column-samples
  (testing "Extracts sample values from dataset column"
    (let [csv-str "Date,Amount\n2024-01-15,4.50\n2024-01-16,45.67\n2024-01-17,100.00"
          ds (csv-data/parse-csv csv-str)
          samples (csv-data/get-column-samples ds "Date" 5)]
      (is (= 3 (count samples)))
      (is (every? string? samples)))))

(deftest test-preview-csv
  (testing "Returns CSV preview with headers and sample rows"
    (let [csv-str "Date,Description,Amount\n2024-01-15,Coffee,4.50\n2024-01-16,Lunch,12.00\n2024-01-17,Groceries,45.67"
          result (csv-data/preview-csv csv-str 2)]
      (is (= ["Date" "Description" "Amount"] (:headers result)))
      (is (= 2 (count (:sample-rows result))))
      (is (= 3 (:total-rows result)))
      (is (some? (:detected-mapping result)))
      (is (= "Date" (get-in result [:detected-mapping :date])))
      (is (= "Amount" (get-in result [:detected-mapping :amount])))
      (is (= "Description" (get-in result [:detected-mapping :payee])))
      (is (some? (:suggested-date-format result)))))

  (testing "Handles CSV with more rows than sample size"
    (let [csv-str (str "Date,Amount\n"
                      (clojure.string/join "\n" (repeat 20 "2024-01-15,10.00")))
          result (csv-data/preview-csv csv-str 5)]
      (is (= 5 (count (:sample-rows result))))
      (is (= 20 (:total-rows result))))))

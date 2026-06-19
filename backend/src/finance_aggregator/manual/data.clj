(ns finance-aggregator.manual.data
  "Pure data transformation functions for manual accounts and CSV transactions.
   Transforms user input to Datalevin schema format.

   Follows the Plaid pattern for consistency:
   - Pure functions with no side effects
   - Lookup refs for relationships
   - Type conversions (string→date, string→bigdec)
   - Returns database-ready entity maps"
  (:require
   [clojure.string :as str]
   [finance-aggregator.utils :as u])
  (:import
   [java.util UUID]))

;;; Helper Functions

(defn- clean-amount-string
  "Remove currency symbols, parentheses, and commas from amount string.
   Handles negative amounts in parentheses (accounting convention).

   Examples:
   '$45.67' -> '45.67'
   '(100.00)' -> '-100.00'
   '1,234.56' -> '1234.56'
   '-45.67' -> '-45.67'"
  [amount-str]
  (let [cleaned (-> amount-str
                    str/trim
                    (str/replace #"[$,]" ""))]
    (if (and (str/starts-with? cleaned "(")
             (str/ends-with? cleaned ")"))
      ;; Parentheses indicate negative (accounting convention)
      (str "-" (-> cleaned
                   (str/replace #"[()]" "")))
      cleaned)))

(defn- normalize-institution-name
  "Normalize institution name to create a valid institution ID.

   Examples:
   'TD Bank' -> 'manual-td-bank'
   'Cash' -> 'manual-cash'"
  [institution-name]
  (str "manual-" (-> institution-name
                     str/lower-case
                     (str/replace #"\s+" "-")
                     (str/replace #"[^a-z0-9-]" ""))))

;;; Parse Functions

(defn parse-manual-account
  "Transform manual account creation request to database schema.

   account-data: {:name string
                  :type string ('checking', 'savings', 'credit', 'cash', etc.)
                  :currency string (optional, defaults to 'USD')
                  :institution-name string}
   user-id: User ID string (e.g., 'test-user')

   Returns: {:account/external-id string (generated UUID with 'manual-' prefix)
            :account/external-name string
            :account/currency string
            :account/provider keyword (:manual)
            :account/institution lookup-ref
            :account/user lookup-ref}"
  [account-data user-id]
  (let [external-id (str "manual-" (UUID/randomUUID))
        institution-id (normalize-institution-name (:institution-name account-data))]
    {:account/external-id external-id
     :account/external-name (:name account-data)
     :account/currency (or (:currency account-data) "USD")
     :account/provider :manual
     :account/institution [:institution/id institution-id]
     :account/user [:user/id user-id]}))

(defn generate-transaction-external-id
  "Generate unique external ID for CSV transaction using hash.

   Uses hash of: account-id|date|amount|payee|row-index
   This ensures:
   - Same CSV re-uploaded produces same IDs (upsert semantics)
   - Row index differentiates genuine duplicate transactions

   Returns: string with 'csv-' prefix"
  [account-external-id date amount payee row-index]
  (let [hash-input (str account-external-id
                       "|" (.getTime date)
                       "|" amount
                       "|" (or payee "")
                       "|" row-index)
        hash (u/sha256-hex hash-input)]
    (str "csv-" (subs hash 0 32))))

(defn parse-csv-transaction
  "Transform CSV row to database transaction schema.

   row: {:date string
         :amount string
         :payee string
         :description string (optional)}
   account-external-id: Account's external-id (e.g., 'manual-abc123')
   user-id: User ID string
   mapping: {:date-format string} - Java DateTimeFormatter pattern
   row-index: integer - row number in CSV (for duplicate detection)

   Returns: {:transaction/external-id string (hash-based)
            :transaction/account lookup-ref
            :transaction/date instant
            :transaction/posted-date instant (same as date)
            :transaction/amount bigdec
            :transaction/payee string
            :transaction/description string (optional)
            :transaction/user lookup-ref}"
  [row account-external-id user-id mapping row-index]
  (let [date-format (:date-format mapping)
        parsed-date (u/string->date (:date row) date-format)
        cleaned-amount (clean-amount-string (:amount row))
        amount-bigdec (bigdec cleaned-amount)
        external-id (generate-transaction-external-id
                     account-external-id
                     parsed-date
                     amount-bigdec
                     (:payee row)
                     row-index)]
    (cond-> {:transaction/external-id external-id
             :transaction/account [:account/external-id account-external-id]
             :transaction/user [:user/id user-id]
             :transaction/date parsed-date
             :transaction/posted-date parsed-date
             :transaction/amount amount-bigdec
             :transaction/payee (:payee row)}
      (:description row)
      (assoc :transaction/description (:description row)))))

(defn create-manual-institution
  "Create institution entity for manual account.

   institution-name: Human-readable name like 'TD Bank' or 'Cash'

   Returns: {:institution/id string
            :institution/name string}"
  [institution-name]
  {:institution/id (normalize-institution-name institution-name)
   :institution/name institution-name})

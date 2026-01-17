(ns finance-aggregator.csv.service
  "Service orchestration for CSV import operations.

   Handles:
   - CSV mapping configuration storage/retrieval
   - CSV preview with automatic column detection
   - Transaction import from CSV files"
  (:require
   [clojure.edn :as edn]
   [datalevin.core :as d]
   [finance-aggregator.csv.data :as csv-data]
   [finance-aggregator.lib.log :as log]
   [finance-aggregator.manual.data :as manual-data]
   [tablecloth.api :as tc]))

;;; Constants

(def ^:private hardcoded-user-id "test-user")

;;; CSV Mapping Configuration

(defn get-csv-mapping
  "Get CSV mapping configuration for an account.

   db-conn: Datalevin connection
   account-db-id: db/id of the account

   Returns: {:success boolean
            :data mapping-map (if found)
            :error string (if failure)}"
  [db-conn account-db-id]
  (try
    (let [db (d/db db-conn)
          account (d/pull db '[:account/csv-mapping] account-db-id)]
      (if-let [mapping-str (:account/csv-mapping account)]
        {:success true
         :data (edn/read-string mapping-str)}
        {:success true
         :data nil}))
    (catch Exception e
      (log/error "Failed to get CSV mapping"
                 {:account-db-id account-db-id
                  :error (.getMessage e)})
      {:success false
       :error (.getMessage e)})))

(defn save-csv-mapping!
  "Save CSV mapping configuration for an account.

   db-conn: Datalevin connection
   account-db-id: db/id of the account
   mapping: {:columns {:date string :amount string :payee string :description string}
            :date-format string}

   Returns: {:success boolean
            :error string (if failure)}"
  [db-conn account-db-id mapping]
  (try
    (let [mapping-str (pr-str mapping)
          tx-data {:db/id account-db-id
                   :account/csv-mapping mapping-str}]
      (d/transact! db-conn [tx-data])
      (log/info "Saved CSV mapping"
                {:account-db-id account-db-id})
      {:success true})
    (catch Exception e
      (log/error "Failed to save CSV mapping"
                 {:account-db-id account-db-id
                  :error (.getMessage e)})
      {:success false
       :error (.getMessage e)})))

;;; CSV Preview

(defn preview-csv-import
  "Preview CSV file with automatic column detection.

   csv-content: CSV file content as string
   account-db-id: db/id of account (to check for existing mapping)
   db-conn: Datalevin connection

   Returns: {:success boolean
            :data {:headers vector
                   :sample-rows vector
                   :total-rows int
                   :detected-mapping map
                   :suggested-date-format string
                   :stored-mapping map (if exists)}
            :error string (if failure)}"
  [db-conn account-db-id csv-content]
  (try
    (let [preview (csv-data/preview-csv csv-content 10)
          stored-mapping-result (get-csv-mapping db-conn account-db-id)
          stored-mapping (when (:success stored-mapping-result)
                          (:data stored-mapping-result))]
      {:success true
       :data (cond-> preview
               stored-mapping (assoc :stored-mapping stored-mapping))})
    (catch Exception e
      (log/error "Failed to preview CSV"
                 {:account-db-id account-db-id
                  :error (.getMessage e)})
      {:success false
       :error (.getMessage e)})))

;;; CSV Import

(defn import-csv-transactions!
  "Import transactions from CSV file.

   db-conn: Datalevin connection
   account-external-id: Account's external-id string
   csv-content: CSV file content as string
   mapping: {:columns {:date string :amount string :payee string :description string}
            :date-format string}

   Returns: {:success boolean
            :data {:imported int :skipped-duplicates int :errors vector}
            :error string (if failure)}"
  [db-conn account-external-id csv-content mapping]
  (try
    (let [user-id hardcoded-user-id
          ;; Parse CSV
          ds (csv-data/parse-csv csv-content)
          total-rows (csv-data/row-count ds)

          ;; Get column mappings
          date-col (get-in mapping [:columns :date])
          amount-col (get-in mapping [:columns :amount])
          payee-col (get-in mapping [:columns :payee])
          description-col (get-in mapping [:columns :description])
          date-format (:date-format mapping)

          ;; Convert dataset to row maps
          rows (tc/rows ds :as-maps)

          ;; Transform each row to transaction
          transactions (keep-indexed
                        (fn [idx row]
                          (try
                            (let [csv-row {:date (str (get row date-col))
                                          :amount (str (get row amount-col))
                                          :payee (str (get row payee-col))}
                                  csv-row (if description-col
                                           (assoc csv-row :description (str (get row description-col)))
                                           csv-row)
                                  mapping-with-format {:date-format date-format}]
                              (manual-data/parse-csv-transaction
                               csv-row
                               account-external-id
                               user-id
                               mapping-with-format
                               idx))
                            (catch Exception e
                              (log/warn "Failed to parse CSV row"
                                       {:row-index idx
                                        :error (.getMessage e)})
                              nil)))
                        rows)

          ;; Count results
          parsed-count (count transactions)
          errors []

          ;; Insert transactions (upsert semantics via external-id)
          _ (when (seq transactions)
              (d/transact! db-conn transactions))

          ;; Calculate skipped (total - parsed)
          skipped (- total-rows parsed-count)]

      (log/info "Imported CSV transactions"
                {:account-id account-external-id
                 :imported parsed-count
                 :skipped skipped})

      {:success true
       :data {:imported parsed-count
              :skipped-duplicates skipped
              :errors errors}})
    (catch Exception e
      (log/error "Failed to import CSV transactions"
                 {:account-id account-external-id
                  :error (.getMessage e)})
      {:success false
       :error (.getMessage e)})))

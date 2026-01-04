(ns finance-aggregator.plaid.data
  "Pure data transformation functions for Plaid API responses.
   Transforms Plaid JSON to Datalevin schema format.

   Follows the SimpleFIN pattern for consistency:
   - Pure functions with no side effects
   - Lookup refs for relationships
   - Type conversions (string→date, number→bigdec)
   - Returns database-ready entity maps"
  (:require
   [clojure.set :as set])
  (:import
   [java.time LocalDate ZoneId]
   [java.util Date]))

;;; Helper Functions

(defn- string->date
  "Convert YYYY-MM-DD string to java.util.Date at UTC midnight."
  [date-string]
  (-> (LocalDate/parse date-string)
      (.atStartOfDay (ZoneId/of "UTC"))
      .toInstant
      Date/from))

;;; Parse Functions

(defn parse-institution
  "Transform Plaid institution details to database schema.

   institution: map from fetch-institution

   Returns: {:institution/id string
            :institution/name string
            :institution/url string (optional, omitted if nil)}

   Note: Filters out nil values to avoid 'Cannot store nil as a value' errors."
  [institution]
  (-> institution
      (select-keys [:institution_id :name :url])
      (set/rename-keys {:institution_id :institution/id
                        :name :institution/name
                        :url :institution/url})
      ;; Remove nil values to avoid database errors
      (->> (remove (fn [[_ v]] (nil? v)))
           (into {}))))

(defn parse-account
  "Transform Plaid account to database schema.

   account: map from fetch-accounts
   institution-id: Plaid institution_id string
   user-id: User ID string (e.g., 'test-user')
   item-id: Plaid item_id string (links to credential)

   Returns: {:account/external-id string (Plaid account_id)
            :account/external-name string
            :account/plaid-type string
            :account/plaid-subtype string
            :account/mask string (optional, omitted if nil)
            :account/currency string
            :account/item-id string (links to Plaid Item/credential)
            :account/institution lookup-ref
            :account/user lookup-ref}

   Note: Filters out nil values to avoid 'Cannot store nil as a value' errors."
  [account institution-id user-id item-id]
  (-> account
      (select-keys [:account_id :name :official_name :type :subtype :mask :balance])
      (set/rename-keys {:account_id :account/external-id
                        :name :account/external-name
                        :type :account/plaid-type
                        :subtype :account/plaid-subtype
                        :mask :account/mask})
      (assoc :account/institution [:institution/id institution-id]
             :account/user [:user/id user-id]
             :account/item-id item-id)
      ;; Extract currency from balance, default to USD
      (assoc :account/currency (or (get-in account [:balance :iso_currency_code]) "USD"))
      ;; Remove balance (we don't store it in schema yet)
      (dissoc :balance :official_name)
      ;; Remove nil values to avoid database errors
      (->> (remove (fn [[_ v]] (nil? v)))
           (into {}))))

(defn parse-transaction
  "Transform Plaid transaction to database schema.
   FILTERS OUT pending transactions (returns nil if pending).

   txn: map from fetch-transactions
   user-id: User ID string (e.g., 'test-user')

   Returns: {:transaction/external-id string
            :transaction/account lookup-ref
            :transaction/date instant
            :transaction/posted-date instant (same as date for Plaid)
            :transaction/amount bigdec
            :transaction/payee string
            :transaction/description string
            :transaction/user lookup-ref}

   Returns nil if transaction is pending.

   Note: Plaid's 'date' field is the posted date, so we set both
   :transaction/date and :transaction/posted-date to the same value."
  [txn user-id]
  (when-not (:pending txn)
    (let [payee (or (:merchant_name txn) (:name txn))
          posted-date (string->date (:date txn))]
      (-> txn
          (select-keys [:transaction_id :account_id :amount :name])
          (set/rename-keys {:transaction_id :transaction/external-id
                            :name :transaction/description
                            :amount :transaction/amount})
          ;; Convert account_id to lookup ref
          (assoc :transaction/account [:account/external-id (:account_id txn)])
          (dissoc :account_id)
          ;; Add user ref and payee
          (assoc :transaction/user [:user/id user-id]
                 :transaction/payee payee)
          ;; Set both date and posted-date (Plaid only provides one date)
          (assoc :transaction/date posted-date
                 :transaction/posted-date posted-date)
          ;; Type conversions
          (update :transaction/amount bigdec)))))

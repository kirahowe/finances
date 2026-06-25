(ns finance-aggregator.plaid.data
  "Pure data transformation functions for Plaid API responses.
   Transforms Plaid JSON to Datalevin schema format.

   Follows the SimpleFIN pattern for consistency:
   - Pure functions with no side effects
   - Lookup refs for relationships
   - Type conversions (string→date, number→bigdec)
   - Returns database-ready entity maps"
  (:require
   [clojure.set :as set]
   [finance-aggregator.utils :as u]))

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

   Returns: {:account/external-id string (Plaid account_id)
            :account/external-name string
            :account/provider keyword (:plaid)
            :account/provider-type string
            :account/provider-subtype string
            :account/mask string (optional, omitted if nil)
            :account/currency string
            :account/reported-balance bigdec (optional, from balance.current)
            :account/available-balance bigdec (optional, from balance.available)
            :account/institution lookup-ref
            :account/user lookup-ref}

   Pure: extracts the reported/available balance numbers but does NOT stamp
   when they were captured - balance-as-of and the snapshot history are stamped
   at persist time (db.snapshots), which is where the clock lives.

   Note: Filters out nil values to avoid 'Cannot store nil as a value' errors."
  [account institution-id user-id]
  (let [{:keys [current available iso_currency_code]} (:balance account)]
    (cond-> (-> account
                (select-keys [:account_id :name :type :subtype :mask])
                (set/rename-keys {:account_id :account/external-id
                                  :name :account/external-name
                                  :type :account/provider-type
                                  :subtype :account/provider-subtype
                                  :mask :account/mask})
                (assoc :account/institution [:institution/id institution-id]
                       :account/user [:user/id user-id]
                       :account/provider :plaid
                       :account/currency (or iso_currency_code "USD")))
      (some? current)   (assoc :account/reported-balance (bigdec current))
      (some? available) (assoc :account/available-balance (bigdec available))
      ;; Remove any nil values to avoid database errors
      :always (->> (remove (fn [[_ v]] (nil? v)))
                   (into {})))))

(defn parse-transaction
  "Transform Plaid transaction to database schema.
   FILTERS OUT pending transactions (returns nil if pending).

   txn: map from fetch-transactions
   user-id: User ID string (e.g., 'test-user')

   Returns: {:transaction/external-id string
            :transaction/account lookup-ref
            :transaction/date instant
            :transaction/posted-date instant (same as date for Plaid)
            :transaction/amount bigdec (NEGATED: canonical inflows+/outflows-)
            :transaction/payee string
            :transaction/description string
            :transaction/user lookup-ref}

   Returns nil if transaction is pending.

   Note: Plaid's 'date' field is the posted date, so we set both
   :transaction/date and :transaction/posted-date to the same value."
  [txn user-id]
  (when-not (:pending txn)
    (let [payee (or (:merchant_name txn) (:name txn))
          posted-date (u/string->date (:date txn))]
      (-> txn
          (select-keys [:transaction_id :account_id :amount :name])
          (set/rename-keys {:transaction_id :transaction/external-id
                            :name :transaction/description
                            :amount :transaction/amount})
          ;; Convert account_id to lookup ref
          (assoc :transaction/account [:account/external-id (:account_id txn)])
          (dissoc :account_id)
          ;; Add user ref, payee, and provenance
          (assoc :transaction/user [:user/id user-id]
                 :transaction/payee payee
                 :transaction/provider :plaid)
          ;; Set both date and posted-date (Plaid only provides one date)
          (assoc :transaction/date posted-date
                 :transaction/posted-date posted-date)
          ;; Type conversion + sign flip to the canonical convention.
          ;; Plaid is positive=money-out; we standardize on inflows-positive,
          ;; outflows-negative, so negate.
          (update :transaction/amount (fn [a] (- (bigdec a))))))))

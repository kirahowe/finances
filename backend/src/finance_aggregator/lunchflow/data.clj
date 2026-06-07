(ns finance-aggregator.lunchflow.data
  "Pure data transforms: parsed Lunchflow API JSON -> canonical Datalevin maps.

   Lunchflow already follows the app's canonical sign convention (inflows
   positive, outflows negative), so amounts pass through unflipped.

   Pure functions only - no I/O. Mirrors the plaid/manual data namespaces:
   lookup refs for relationships, string->date / ->bigdec coercions, and a
   deterministic hash fallback for transactions that arrive without an id."
  (:require
   [clojure.string :as str])
  (:import
   [java.security MessageDigest]
   [java.time LocalDate ZoneId]
   [java.util Date]))

;;; Helpers

(defn- string->date
  "Convert a YYYY-MM-DD string to java.util.Date at UTC midnight."
  [date-string]
  (-> (LocalDate/parse date-string)
      (.atStartOfDay (ZoneId/of "UTC"))
      .toInstant
      Date/from))

(defn- sha256-hex [s]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest digest (.getBytes (str s) "UTF-8"))))))

(defn- institution-id
  "Synthesize a stable institution id from a Lunchflow institution name."
  [institution-name]
  (str "lunchflow-" (-> (str institution-name)
                        str/lower-case
                        (str/replace #"\s+" "-")
                        (str/replace #"[^a-z0-9-]" ""))))

(defn account-external-id
  "Canonical external-id for a Lunchflow account map: \"lunchflow-<id>\"."
  [account]
  (str "lunchflow-" (:id account)))

(defn- transaction-hash
  "Deterministic external-id suffix for transactions that arrive without an id
   (the API can return id: null). Hashes the stable identifying fields."
  [account-external-id {:keys [date amount description]}]
  (subs (sha256-hex (str account-external-id "|" date "|" amount "|" (or description "")))
        0 32))

;;; Parse functions

(defn parse-institution
  "Synthesize an institution entity from a Lunchflow account map.

   Returns: {:institution/id string :institution/name string} plus
   :institution/logo when the account carries an institution_logo URL."
  [account]
  (cond-> {:institution/id (institution-id (:institution_name account))
           :institution/name (:institution_name account)}
    (:institution_logo account) (assoc :institution/logo (:institution_logo account))))

(defn parse-account
  "Transform a Lunchflow account to the canonical account schema.

   account: {:id :name :institution_name :provider :currency ...}
   user-id: user id string

   Returns canonical :account/* map. The Lunchflow `provider` field (the
   upstream connector, e.g. \"gocardless\") maps to :account/provider-type."
  [account user-id]
  (cond-> {:account/external-id (account-external-id account)
           :account/external-name (:name account)
           :account/currency (or (:currency account) "CAD")
           :account/provider :lunchflow
           :account/institution [:institution/id (institution-id (:institution_name account))]
           :account/user [:user/id user-id]}
    (:provider account) (assoc :account/provider-type (:provider account))))

(defn parse-transaction
  "Transform a Lunchflow transaction to the canonical transaction schema.
   Returns nil for pending transactions (isPending true).

   txn: {:id :accountId :amount :date :merchant :description :isPending ...}
   user-id: user id string

   Amount passes through unflipped (Lunchflow is already inflows-positive/
   outflows-negative). External-id is \"lunchflow-<id>\", falling back to a
   deterministic hash when id is absent."
  [txn user-id]
  (when-not (:isPending txn)
    (let [account-external-id (str "lunchflow-" (:accountId txn))
          external-id (str "lunchflow-"
                           (if-some [id (:id txn)]
                             id
                             (transaction-hash account-external-id txn)))
          date (string->date (:date txn))
          payee (or (:merchant txn) (:description txn))]
      (cond-> {:transaction/external-id external-id
               :transaction/account [:account/external-id account-external-id]
               :transaction/user [:user/id user-id]
               :transaction/date date
               :transaction/posted-date date
               :transaction/amount (bigdec (:amount txn))
               :transaction/provider :lunchflow}
        payee (assoc :transaction/payee payee)
        (:description txn) (assoc :transaction/description (:description txn))))))

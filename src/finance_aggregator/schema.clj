(ns finance-aggregator.schema
  "Data schemas and validation for financial transactions."
  (:require [malli.core :as m]
            [malli.error :as me]))

;; Transaction schema - the normalized format for all transactions
;; This will be the column spec for our Tablecloth datasets
(def Transaction
  [:map
   [:id :uuid]                                    ; Unique transaction ID
   [:date :string]                                ; ISO date string (YYYY-MM-DD)
   [:amount :double]                              ; Transaction amount (negative for debits, positive for credits)
   [:description :string]                         ; Transaction description
   [:institution [:enum :scotiabank :canadian-tire :amazon-mbna :wealthsimple
                  :manulife :canada-life :merix :company-shares :house]]
   [:account-name {:optional true} :string]       ; Account name/number
   [:account-type [:enum :checking :savings :credit :investment :mortgage :asset]]
   [:category {:optional true} [:enum :groceries :dining :transportation :insurance
                                 :housing :utilities :entertainment :healthcare
                                 :income :transfer :investment :uncategorized]]
   [:balance {:optional true} :double]            ; Account balance after transaction
   [:source [:enum :simplefin :csv :pdf :manual]] ; Data source
   [:raw-data {:optional true} :map]])            ; Original data for reference

;; Account schema
(def Account
  [:map
   [:id :uuid]
   [:institution [:enum :scotiabank :canadian-tire :amazon-mbna :wealthsimple
                  :manulife :canada-life :merix :company-shares :house]]
   [:name :string]
   [:account-type [:enum :checking :savings :credit :investment :mortgage :asset]]
   [:balance :double]
   [:currency {:optional true} :string]
   [:last-updated :string]])                      ; ISO date string

;; SimpleFin API response schemas
(def SimplefinAccount
  [:map
   [:id :string]
   [:name :string]
   [:currency :string]
   [:balance :string]                             ; SimpleFin returns balance as string
   [:available-balance {:optional true} :string]
   [:balance-date {:optional true} :int]          ; Unix timestamp
   [:transactions {:optional true} [:sequential :map]]])

;; Validation functions
(defn valid-transaction?
  "Validates a transaction against the Transaction schema."
  [transaction]
  (m/validate Transaction transaction))

(defn explain-transaction-error
  "Returns human-readable error message for invalid transaction."
  [transaction]
  (-> Transaction
      (m/explain transaction)
      (me/humanize)))

(defn valid-account?
  "Validates an account against the Account schema."
  [account]
  (m/validate Account account))

;; Helper functions
(defn normalize-amount
  "Normalizes amount to double. Negative = debit, Positive = credit.
   For credit cards, purchases are negative."
  [amount-str]
  (try
    (Double/parseDouble (clojure.string/replace amount-str #"[$,]" ""))
    (catch Exception _ 0.0)))

(defn institution-keyword
  "Converts institution string to keyword."
  [inst-str]
  (keyword (clojure.string/lower-case (clojure.string/replace inst-str #"\s+" "-"))))

(def account-type-mapping
  "Maps common account name patterns to account types."
  {:checking #"(?i)chequing|checking|current"
   :savings #"(?i)savings|save"
   :credit #"(?i)credit|visa|mastercard|card"
   :investment #"(?i)invest|tfsa|rrsp|401k|mutual"
   :mortgage #"(?i)mortgage|loan"
   :asset #"(?i)house|property|real estate"})

(defn infer-account-type
  "Infers account type from account name."
  [account-name]
  (or (some (fn [[type pattern]]
              (when (re-find pattern account-name)
                type))
            account-type-mapping)
      :checking))

;; Category keywords
(def categories
  #{:groceries :dining :transportation :insurance :housing :utilities
    :entertainment :healthcare :income :transfer :investment :uncategorized})

(ns finance-aggregator.plaid.types
  "Plaid type definitions and EDN readers.

   Provides lookup maps and EDN readers for Plaid enum types.
   Use these readers in config files to specify Plaid values directly."
  (:import
   [com.plaid.client.model CountryCode Products]))

;;
;; Lookup Maps (not case statements!)
;;

(def country-codes
  "Map of country code strings to Plaid CountryCode enum values."
  {"US" CountryCode/US
   "CA" CountryCode/CA
   "GB" CountryCode/GB
   "FR" CountryCode/FR
   "ES" CountryCode/ES
   "NL" CountryCode/NL
   "IE" CountryCode/IE
   "DE" CountryCode/DE
   "DK" CountryCode/DK
   "EE" CountryCode/EE
   "LT" CountryCode/LT
   "LV" CountryCode/LV
   "NO" CountryCode/NO
   "PL" CountryCode/PL
   "SE" CountryCode/SE
   "BE" CountryCode/BE
   "PT" CountryCode/PT
   "IT" CountryCode/IT})

(def products
  "Map of product keywords to Plaid Products enum values."
  {:transactions Products/TRANSACTIONS
   :auth Products/AUTH
   :identity Products/IDENTITY
   :assets Products/ASSETS
   :investments Products/INVESTMENTS
   :liabilities Products/LIABILITIES
   :payment-initiation Products/PAYMENT_INITIATION
   :identity-verification Products/IDENTITY_VERIFICATION
   :transfer Products/TRANSFER
   :employment Products/EMPLOYMENT
   :income-verification Products/INCOME_VERIFICATION
   :standing-orders Products/STANDING_ORDERS
   :signal Products/SIGNAL
   :statements Products/STATEMENTS})

(def environments
  "Map of environment keywords to Plaid API base URLs."
  {:sandbox "https://sandbox.plaid.com"
   :development "https://development.plaid.com"
   :production "https://production.plaid.com"})

;;
;; EDN Readers
;;

(defn read-country-code
  "EDN reader for #plaid/country-code \"US\".
   Returns the Plaid CountryCode enum value."
  [code]
  (let [code-upper (clojure.string/upper-case (str code))]
    (or (country-codes code-upper)
        (throw (ex-info (str "Unknown Plaid country code: " code)
                        {:code code
                         :valid-codes (keys country-codes)})))))

(defn read-product
  "EDN reader for #plaid/product :transactions.
   Returns the Plaid Products enum value."
  [product]
  (let [kw (keyword product)]
    (or (products kw)
        (throw (ex-info (str "Unknown Plaid product: " product)
                        {:product product
                         :valid-products (keys products)})))))

(defn read-environment
  "EDN reader for #plaid/environment :sandbox.
   Returns the Plaid API base URL string."
  [env]
  (let [kw (keyword env)]
    (or (environments kw)
        (throw (ex-info (str "Unknown Plaid environment: " env)
                        {:environment env
                         :valid-environments (keys environments)})))))

(def readers
  "EDN readers for Plaid types. Merge with other readers when loading config."
  {'plaid/country-code read-country-code
   'plaid/product read-product
   'plaid/environment read-environment})

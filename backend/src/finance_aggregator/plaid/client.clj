(ns finance-aggregator.plaid.client
  "Pure Plaid API client functions using Plaid Java SDK.
   All functions are pure - they take configuration and parameters,
   call the Plaid API, and return data. No side effects or component dependencies."
  (:require
   [finance-aggregator.plaid.types :as types])
  (:import
   [com.plaid.client ApiClient]
   [com.plaid.client.model CountryCode LinkTokenCreateRequest
    LinkTokenCreateRequestUser Products ItemPublicTokenExchangeRequest
    AccountsGetRequest TransactionsGetRequest ItemGetRequest
    InstitutionsGetByIdRequest]
   [com.plaid.client.request PlaidApi]
   [java.util HashMap]))

(defn- create-api-client
  "Create a Plaid API client from configuration.
   plaid-config: map with :client-id, :secret, :environment"
  [{:keys [client-id secret environment]}]
  (let [api-keys (doto (HashMap.)
                   (.put "clientId" client-id)
                   (.put "secret" secret))
        api-client (ApiClient. api-keys)
        ;; environment can be a keyword or already a URL string from #plaid/environment
        plaid-adapter (if (keyword? environment)
                        (or (types/environments environment)
                            (throw (ex-info "Invalid Plaid environment" {:environment environment})))
                        environment)]
    (.setPlaidAdapter api-client plaid-adapter)
    api-client))

(defn- ensure-enum
  "Ensure value is the expected enum type, converting if needed.
   value: Already an enum value (from EDN reader) or keyword/string to convert
   lookup-map: Map from keyword/string to enum value
   type-name: String name for error messages"
  [value lookup-map type-name]
  (cond
    ;; Already an enum value (from EDN reader)
    (instance? Enum value) value
    ;; Keyword or string - look up in map
    :else (or (lookup-map (keyword value))
              (lookup-map (str value))
              (throw (ex-info (str "Unknown " type-name ": " value)
                              {:value value
                               :valid-values (keys lookup-map)})))))

(defn create-link-token
  "Generate link token for Plaid Link frontend initialization.

   plaid-config: map with:
     - :client-id, :secret, :environment (credentials)
     - :client-name (string, displayed in Plaid Link)
     - :country-codes (vector of CountryCode enums or strings)
     - :language (string, e.g. \"en\")
     - :products (vector of Products enums or keywords)

   user-id: string identifying the user
   Returns: link_token string"
  [plaid-config user-id]
  (let [{:keys [client-name country-codes language products]
         :or {client-name "Finance App"
              country-codes [CountryCode/US]
              language "en"
              products [Products/TRANSACTIONS]}} plaid-config]
    (try
      (let [api-client (create-api-client plaid-config)
            plaid-api (.createService api-client PlaidApi)
            user (-> (LinkTokenCreateRequestUser.)
                     (.clientUserId user-id))
            ;; Ensure values are proper enum types
            product-enums (mapv #(ensure-enum % types/products "product") products)
            country-enums (mapv #(ensure-enum % types/country-codes "country code") country-codes)
            request (-> (LinkTokenCreateRequest.)
                        (.user user)
                        (.clientName client-name)
                        (.products product-enums)
                        (.countryCodes country-enums)
                        (.language language))
            response (.linkTokenCreate plaid-api request)
            result (.body (.execute response))]
        (.getLinkToken result))
      (catch Exception e
        (throw (ex-info "Failed to create link token"
                        {:user-id user-id
                         :error (.getMessage e)}
                        e))))))

(defn exchange-public-token
  "Exchange public_token for access_token.
   plaid-config: map with :client-id, :secret, :environment
   public-token: string from Plaid Link onSuccess callback
   Returns: {:access_token string :item_id string}"
  [plaid-config public-token]
  (try
    (let [api-client (create-api-client plaid-config)
          plaid-api (.createService api-client PlaidApi)
          request (-> (ItemPublicTokenExchangeRequest.)
                      (.publicToken public-token))
          response (.itemPublicTokenExchange plaid-api request)
          result (.body (.execute response))]
      {:access_token (.getAccessToken result)
       :item_id (.getItemId result)})
    (catch Exception e
      (throw (ex-info "Failed to exchange public token"
                      {:public-token public-token
                       :error (.getMessage e)}
                      e)))))

(defn fetch-accounts
  "Fetch account list using access_token.
   plaid-config: map with :client-id, :secret, :environment
   access-token: string from exchange-public-token
   Returns: list of account maps"
  [plaid-config access-token]
  (try
    (let [api-client (create-api-client plaid-config)
          plaid-api (.createService api-client PlaidApi)
          request (-> (AccountsGetRequest.)
                      (.accessToken access-token))
          response (.accountsGet plaid-api request)
          result (.body (.execute response))
          accounts (.getAccounts result)]
      (mapv (fn [account]
              {:account_id (.getAccountId account)
               :name (.getName account)
               :official_name (.getOfficialName account)
               :type (str (.getType account))
               :subtype (str (.getSubtype account))
               :mask (.getMask account)
               :balance {:available (some-> account .getBalances .getAvailable)
                        :current (some-> account .getBalances .getCurrent)
                        :limit (some-> account .getBalances .getLimit)
                        :iso_currency_code (some-> account .getBalances .getIsoCurrencyCode)}})
            accounts))
    (catch Exception e
      (throw (ex-info "Failed to fetch accounts"
                      {:access-token access-token
                       :error (.getMessage e)}
                      e)))))

(defn fetch-transactions
  "Fetch transactions for date range.
   plaid-config: map with :client-id, :secret, :environment
   access-token: string
   start-date: string 'YYYY-MM-DD'
   end-date: string 'YYYY-MM-DD'
   Returns: list of transaction maps"
  [plaid-config access-token start-date end-date]
  (try
    (let [api-client (create-api-client plaid-config)
          plaid-api (.createService api-client PlaidApi)
          request (-> (TransactionsGetRequest.)
                      (.accessToken access-token)
                      (.startDate (java.time.LocalDate/parse start-date))
                      (.endDate (java.time.LocalDate/parse end-date)))
          response (.transactionsGet plaid-api request)
          result (.body (.execute response))
          transactions (.getTransactions result)]
      (mapv (fn [txn]
              {:transaction_id (.getTransactionId txn)
               :account_id (.getAccountId txn)
               :amount (.getAmount txn)
               :date (str (.getDate txn))
               :name (.getName txn)
               :merchant_name (.getMerchantName txn)
               :pending (.getPending txn)
               :category (vec (.getCategory txn))
               :payment_channel (str (.getPaymentChannel txn))})
            transactions))
    (catch Exception e
      (throw (ex-info "Failed to fetch transactions"
                      {:access-token access-token
                       :start-date start-date
                       :end-date end-date
                       :error (.getMessage e)}
                      e)))))

(defn fetch-item
  "Fetch item metadata including institution_id.

   An item represents a connection to a financial institution.
   Use this to get the institution_id needed for fetching institution details.

   plaid-config: map with :client-id, :secret, :environment
   access-token: string from exchange-public-token

   Returns: {:item_id string
            :institution_id string
            :available_products vector of product strings
            :billed_products vector of product strings}"
  [plaid-config access-token]
  (try
    (let [api-client (create-api-client plaid-config)
          plaid-api (.createService api-client PlaidApi)
          request (-> (ItemGetRequest.)
                      (.accessToken access-token))
          response (.itemGet plaid-api request)
          result (.body (.execute response))
          item (.getItem result)]
      {:item_id (.getItemId item)
       :institution_id (.getInstitutionId item)
       :available_products (vec (.getAvailableProducts item))
       :billed_products (vec (.getBilledProducts item))})
    (catch Exception e
      (throw (ex-info "Failed to fetch item"
                      {:access-token access-token
                       :error (.getMessage e)}
                      e)))))

(defn fetch-institution
  "Fetch institution details by institution ID.

   plaid-config: map with :client-id, :secret, :environment
   institution-id: string from fetch-item
   country-codes: vector of CountryCode enums or strings (default [\"US\"])

   Returns: {:institution_id string
            :name string
            :url string (or nil)
            :primary_color string (or nil)
            :logo string (or nil)}"
  ([plaid-config institution-id]
   (fetch-institution plaid-config institution-id ["US"]))
  ([plaid-config institution-id country-codes]
   (try
     (let [api-client (create-api-client plaid-config)
           plaid-api (.createService api-client PlaidApi)
           country-enums (mapv #(ensure-enum % types/country-codes "country code") country-codes)
           request (-> (InstitutionsGetByIdRequest.)
                       (.institutionId institution-id)
                       (.countryCodes country-enums))
           response (.institutionsGetById plaid-api request)
           result (.body (.execute response))
           institution (.getInstitution result)]
       {:institution_id (.getInstitutionId institution)
        :name (.getName institution)
        :url (.getUrl institution)
        :primary_color (.getPrimaryColor institution)
        :logo (.getLogo institution)})
     (catch Exception e
       (throw (ex-info "Failed to fetch institution"
                       {:institution-id institution-id
                        :country-codes country-codes
                        :error (.getMessage e)}
                       e))))))

(ns finance-aggregator.plaid.client
  "Pure Plaid API client functions using Plaid Java SDK.
   All functions are pure - they take configuration and parameters,
   call the Plaid API, and return data. No side effects or component dependencies."
  (:import
   [com.plaid.client ApiClient]
   [com.plaid.client.model CountryCode LinkTokenCreateRequest
    LinkTokenCreateRequestUser Products ItemPublicTokenExchangeRequest
    AccountsGetRequest TransactionsGetRequest]
   [com.plaid.client.request PlaidApi]
   [java.util HashMap]))

(defn- environment-keyword->plaid-adapter
  "Convert environment keyword to Plaid adapter string."
  [env-kw]
  (case env-kw
    :sandbox "https://sandbox.plaid.com"
    :development "https://development.plaid.com"
    :production "https://production.plaid.com"
    (throw (ex-info "Invalid Plaid environment" {:environment env-kw}))))

(defn- create-api-client
  "Create a Plaid API client from configuration.
   plaid-config: map with :client-id, :secret, :environment"
  [{:keys [client-id secret environment]}]
  (let [api-keys (doto (HashMap.)
                   (.put "clientId" client-id)
                   (.put "secret" secret))
        api-client (ApiClient. api-keys)
        plaid-adapter (environment-keyword->plaid-adapter environment)]
    (.setPlaidAdapter api-client plaid-adapter)
    api-client))

(defn create-link-token
  "Generate link token for Plaid Link frontend initialization.
   plaid-config: map with :client-id, :secret, :environment
   user-id: string identifying the user
   Returns: link_token string"
  [plaid-config user-id]
  (try
    (let [api-client (create-api-client plaid-config)
          plaid-api (.createService api-client PlaidApi)
          user (-> (LinkTokenCreateRequestUser.)
                   (.clientUserId user-id))
          request (-> (LinkTokenCreateRequest.)
                      (.user user)
                      (.clientName "Finance Aggregator")
                      (.products [Products/TRANSACTIONS])
                      (.countryCodes [CountryCode/US])
                      (.language "en"))
          response (.linkTokenCreate plaid-api request)
          result (.body (.execute response))]
      (.getLinkToken result))
    (catch Exception e
      (throw (ex-info "Failed to create link token"
                      {:user-id user-id
                       :error (.getMessage e)}
                      e)))))

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

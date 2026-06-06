(ns finance-aggregator.plaid.client
  "Pure Plaid API client functions using Plaid Java SDK.
   All functions are pure - they take configuration and parameters,
   call the Plaid API, and return data. No side effects or component dependencies."
  (:require
   [finance-aggregator.lib.log :as log]
   [finance-aggregator.plaid.types :as types])
  (:import
   [com.plaid.client ApiClient]
   [com.plaid.client.model CountryCode LinkTokenCreateRequest
    LinkTokenCreateRequestUser LinkTokenTransactions Products
    ItemPublicTokenExchangeRequest AccountsGetRequest TransactionsGetRequest
    ItemGetRequest InstitutionsGetByIdRequest TransactionsSyncRequest
    TransactionsSyncRequestOptions]
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
     - :days-requested (int, 1-730, REQUIRED - configures transaction history depth)

   user-id: string identifying the user
   Returns: link_token string

   Note: days-requested configures how much transaction history to fetch.
   This value is set during Link token creation and cannot be changed after
   Transactions is initialized for an Item."
  [plaid-config user-id]
  (let [{:keys [client-name country-codes language products days-requested]} plaid-config]
    (when-not days-requested
      (throw (ex-info "days-requested is required in plaid-config"
                      {:hint "Add :days-requested to plaid link-config (1-730)"})))
    (log/info "Creating Plaid link token"
              {:user-id user-id
               :days-requested days-requested
               :products products
               :country-codes country-codes})
    (try
      (let [api-client (create-api-client plaid-config)
            plaid-api (.createService api-client PlaidApi)
            user (-> (LinkTokenCreateRequestUser.)
                     (.clientUserId user-id))
            ;; Ensure values are proper enum types
            product-enums (mapv #(ensure-enum % types/products "product") products)
            country-enums (mapv #(ensure-enum % types/country-codes "country code") country-codes)
            ;; Configure transactions options for maximum history
            transactions-options (-> (LinkTokenTransactions.)
                                     (.daysRequested (int days-requested)))
            _ (log/info "Plaid LinkTokenTransactions object configured"
                        {:transactions-options-str (str transactions-options)
                         :days-requested-value (.getDaysRequested transactions-options)})
            request (-> (LinkTokenCreateRequest.)
                        (.user user)
                        (.clientName client-name)
                        (.products product-enums)
                        (.countryCodes country-enums)
                        (.language language)
                        (.transactions transactions-options))
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

(defn- txn->map
  "Convert Plaid Transaction object to a Clojure map.
   Extracts all fields needed for parsing into database schema."
  [txn]
  {:transaction_id (.getTransactionId txn)
   :account_id (.getAccountId txn)
   :amount (.getAmount txn)
   :date (str (.getDate txn))
   :name (.getName txn)
   :merchant_name (.getMerchantName txn)
   :pending (.getPending txn)
   :category (some-> (.getCategory txn) vec)
   :payment_channel (some-> (.getPaymentChannel txn) str)})

(defn- removed-txn->map
  "Convert a RemovedTransaction object to a map.
   The removed array contains objects with only transaction_id."
  [removed-txn]
  {:transaction_id (.getTransactionId removed-txn)})

(defn sync-transactions
  "Sync transactions using /transactions/sync with cursor-based incremental updates.

   This is the recommended approach per Plaid documentation. It returns:
   - added: New transactions since last cursor
   - modified: Transactions that have been updated
   - removed: Transactions that have been deleted
   - next_cursor: Cursor for the next sync call
   - has_more: Whether more pages are available
   - transactions_update_status: Status of transaction data availability

   plaid-config: map with :client-id, :secret, :environment
   access-token: string from exchange-public-token
   cursor: string from previous sync, or nil for initial sync
   opts: {:count int (max 500, default 500)
          :days-requested int (1-730, REQUIRED for initial sync when cursor is nil)}

   For initial sync (cursor = nil):
   - days-requested MUST be provided - controls how much history to fetch
   - days-requested only takes effect on first sync; cannot be changed after

   For incremental sync (cursor = previous next_cursor):
   - Returns only changes since last cursor
   - days-requested is ignored (can be omitted)

   Returns: {:added [txn-maps]
            :modified [txn-maps]
            :removed [txn-maps with :transaction_id only]
            :next_cursor string
            :has_more boolean
            :transactions_update_status keyword - :not-ready, :initial-update-complete, or :historical-update-complete}"
  ([plaid-config access-token cursor opts]
   (let [{:keys [count days-requested]
          :or {count 500}} opts]
     (when (and (nil? cursor) (nil? days-requested))
       (throw (ex-info "days-requested is required for initial sync (cursor is nil)"
                       {:hint "Pass :days-requested in opts from plaid-config"})))
     (try
       (let [api-client (create-api-client plaid-config)
             plaid-api (.createService api-client PlaidApi)
             ;; Build request with access token and optional cursor
             request (cond-> (-> (TransactionsSyncRequest.)
                                 (.accessToken access-token)
                                 (.count (int count)))
                       ;; Add cursor if provided (incremental sync)
                       cursor (.cursor cursor)
                       ;; On initial sync (no cursor), set days_requested for max history
                       (nil? cursor) (.options (-> (TransactionsSyncRequestOptions.)
                                                   (.daysRequested (int days-requested)))))
             response (.transactionsSync plaid-api request)
             result (.body (.execute response))
             ;; Parse transactions_update_status enum to keyword
             update-status (some-> (.getTransactionsUpdateStatus result)
                                   str
                                   clojure.string/lower-case
                                   (clojure.string/replace "_" "-")
                                   keyword)]
         {:added (mapv txn->map (.getAdded result))
          :modified (mapv txn->map (.getModified result))
          :removed (mapv removed-txn->map (.getRemoved result))
          :next_cursor (.getNextCursor result)
          :has_more (.getHasMore result)
          :transactions_update_status update-status})
       (catch Exception e
         (throw (ex-info "Failed to sync transactions"
                         {:access-token access-token
                          :cursor cursor
                          :error (.getMessage e)}
                         e)))))))

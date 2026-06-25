(ns finance-aggregator.plaid.client
  "Pure Plaid API client functions using Plaid Java SDK.
   All functions take configuration and parameters, call the Plaid API, and
   return data. No component dependencies.

   Every call goes through `execute!`, which inspects the Retrofit response and,
   on a non-2xx, parses Plaid's structured error body so the thrown ex-info
   carries the `error_code` (classification reads it directly - no second
   round-trip). A transport failure surfaces as the underlying IOException."
  (:require
   [clojure.data.json :as json]
   [clojure.string :as str]
   [finance-aggregator.lib.log :as log]
   [finance-aggregator.plaid.types :as types])
  (:import
   [com.plaid.client ApiClient]
   [com.plaid.client.model LinkTokenCreateRequest
    LinkTokenCreateRequestUser LinkTokenTransactions
    ItemPublicTokenExchangeRequest AccountsGetRequest
    ItemGetRequest InstitutionsGetByIdRequest TransactionsSyncRequest
    TransactionsSyncRequestOptions]
   [com.plaid.client.request PlaidApi]
   [java.util HashMap]
   [okhttp3 ResponseBody]
   [retrofit2 Call Response]))

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

(defn- error->ex-info
  "Parse a Plaid error response body into an ex-info, surfacing the structured
   `error_code` / `error_type` / `error_message` in the ex-data (Plaid says to
   branch on error_code, not HTTP status). `ctx` adds call-site context."
  [^Response resp ctx]
  (let [eb   (.errorBody resp)
        body (when eb (.string ^ResponseBody eb))
        {:strs [error_code error_type error_message]}
        (when (seq body) (try (json/read-str body) (catch Exception _ nil)))]
    (ex-info (or error_message "Plaid API error")
             (merge ctx
                    {:error-code    error_code
                     :error-type    error_type
                     :error-message error_message
                     :http-status   (.code resp)}))))

(defn- execute!
  "Run a Retrofit `Call` and return its deserialized body, or throw on an API
   error (see `error->ex-info`). The single chokepoint every Plaid call passes
   through, so error_code surfacing is uniform."
  [^Call call ctx]
  (let [^Response resp (.execute call)]
    (if (.isSuccessful resp)
      (.body resp)
      (throw (error->ex-info resp ctx)))))

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
          request (-> (LinkTokenCreateRequest.)
                      (.user user)
                      (.clientName client-name)
                      (.products product-enums)
                      (.countryCodes country-enums)
                      (.language language)
                      (.transactions transactions-options))
          result (execute! (.linkTokenCreate plaid-api request)
                           {:operation :create-link-token :user-id user-id})]
      (.getLinkToken result))))

(defn create-update-link-token
  "Generate a link token for re-auth via Link **update mode**. Built from an
   existing access_token with **no `products`** (update mode reuses the Item's
   existing products); the user re-authenticates a broken Item (e.g. after
   ITEM_LOGIN_REQUIRED) without creating a new Item or access_token.

   plaid-config: same map as create-link-token (credentials + client-name,
                 country-codes, language)
   access-token: the existing Item's access_token
   user-id: string identifying the user

   Returns: link_token string"
  [plaid-config access-token user-id]
  (let [{:keys [client-name country-codes language]} plaid-config]
    (log/info "Creating Plaid update-mode link token" {:user-id user-id})
    (let [api-client (create-api-client plaid-config)
          plaid-api (.createService api-client PlaidApi)
          user (-> (LinkTokenCreateRequestUser.)
                   (.clientUserId user-id))
          country-enums (mapv #(ensure-enum % types/country-codes "country code") country-codes)
          ;; No .products: update mode reuses the existing Item's products.
          request (-> (LinkTokenCreateRequest.)
                      (.user user)
                      (.clientName client-name)
                      (.countryCodes country-enums)
                      (.language language)
                      (.accessToken access-token))
          result (execute! (.linkTokenCreate plaid-api request)
                           {:operation :create-update-link-token :user-id user-id})]
      (.getLinkToken result))))

(defn exchange-public-token
  "Exchange public_token for access_token.
   plaid-config: map with :client-id, :secret, :environment
   public-token: string from Plaid Link onSuccess callback
   Returns: {:access_token string :item_id string}"
  [plaid-config public-token]
  (let [api-client (create-api-client plaid-config)
        plaid-api (.createService api-client PlaidApi)
        request (-> (ItemPublicTokenExchangeRequest.)
                    (.publicToken public-token))
        result (execute! (.itemPublicTokenExchange plaid-api request)
                         {:operation :exchange-public-token})]
    {:access_token (.getAccessToken result)
     :item_id (.getItemId result)}))

(defn fetch-accounts
  "Fetch account list using access_token.
   plaid-config: map with :client-id, :secret, :environment
   access-token: string from exchange-public-token
   Returns: list of account maps"
  [plaid-config access-token]
  (let [api-client (create-api-client plaid-config)
        plaid-api (.createService api-client PlaidApi)
        request (-> (AccountsGetRequest.)
                    (.accessToken access-token))
        result (execute! (.accountsGet plaid-api request)
                         {:operation :fetch-accounts})
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
          accounts)))

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
  (let [api-client (create-api-client plaid-config)
        plaid-api (.createService api-client PlaidApi)
        request (-> (ItemGetRequest.)
                    (.accessToken access-token))
        result (execute! (.itemGet plaid-api request)
                         {:operation :fetch-item})
        item (.getItem result)]
    {:item_id (.getItemId item)
     :institution_id (.getInstitutionId item)
     :available_products (vec (.getAvailableProducts item))
     :billed_products (vec (.getBilledProducts item))}))

(defn fetch-item-error
  "Fetch the structured `item.error` from /item/get - the health signal for a
   broken Item. Returns {:error-code string :error-message string} when Plaid
   reports an error on the Item, or nil when the Item is healthy. Best-effort:
   any failure to read the error (network, etc.) returns nil rather than masking
   the original sync failure that prompted the check.

   This is the *supplement* to the error_code carried by the failing call: item-
   level problems (ITEM_LOGIN_REQUIRED, PENDING_EXPIRATION, ...) are reported
   here even when the failing call didn't carry a code.

   plaid-config: map with :client-id, :secret, :environment
   access-token: string from exchange-public-token"
  [plaid-config access-token]
  (try
    (let [api-client (create-api-client plaid-config)
          plaid-api (.createService api-client PlaidApi)
          request (-> (ItemGetRequest.)
                      (.accessToken access-token))
          result (execute! (.itemGet plaid-api request)
                           {:operation :fetch-item-error})
          error (some-> (.getItem result) .getError)]
      (when error
        {:error-code (some-> (.getErrorCode error) str)
         :error-message (.getErrorMessage error)}))
    (catch Exception e
      (log/warn "Failed to fetch item error" {:error (.getMessage e)})
      nil)))

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
   (let [api-client (create-api-client plaid-config)
         plaid-api (.createService api-client PlaidApi)
         country-enums (mapv #(ensure-enum % types/country-codes "country code") country-codes)
         request (-> (InstitutionsGetByIdRequest.)
                     (.institutionId institution-id)
                     (.countryCodes country-enums))
         result (execute! (.institutionsGetById plaid-api request)
                          {:operation :fetch-institution :institution-id institution-id})
         institution (.getInstitution result)]
     {:institution_id (.getInstitutionId institution)
      :name (.getName institution)
      :url (.getUrl institution)
      :primary_color (.getPrimaryColor institution)
      :logo (.getLogo institution)})))

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
           result (execute! (.transactionsSync plaid-api request)
                            {:operation :sync-transactions :cursor cursor})
           ;; Parse transactions_update_status enum to keyword
           update-status (some-> (.getTransactionsUpdateStatus result)
                                 str
                                 str/lower-case
                                 (str/replace "_" "-")
                                 keyword)]
       {:added (mapv txn->map (.getAdded result))
        :modified (mapv txn->map (.getModified result))
        :removed (mapv removed-txn->map (.getRemoved result))
        :next_cursor (.getNextCursor result)
        :has_more (.getHasMore result)
        :transactions_update_status update-status}))))

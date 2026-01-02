(ns finance-aggregator.server
  (:require
   [org.httpkit.server :as http]
   [charred.api :as json]
   [datalevin.core :as d]
   [finance-aggregator.db :as db]
   [finance-aggregator.db.categories :as categories]
   [finance-aggregator.db.transactions :as transactions]
   [finance-aggregator.plaid.client :as plaid]
   [finance-aggregator.db.credentials :as credentials]
   [finance-aggregator.lib.secrets :as secrets]))

(defn json-response [data]
  "Create a JSON response"
  {:status 200
   :headers {"Content-Type" "application/json"
             "Access-Control-Allow-Origin" "*"}
   :body (json/write-json-str data)})

(defn read-json [body-str]
  "Read JSON data from request body"
  (json/read-json body-str :key-fn keyword))

(defn- query-handler [query-str]
  "Execute a Datalog query and return results"
  (try
    (let [query (read-string query-str)
          results (d/q query (d/db db/conn))]
      (json-response {:success true :data results}))
    (catch Exception e
      {:status 400
       :headers {"Content-Type" "application/json"
                 "Access-Control-Allow-Origin" "*"}
       :body (json/write-json-str {:success false :error (.getMessage e)})})))

(defn- get-all-handler [entity-type]
  "Get all entities of a specific type"
  (try
    (let [query (case entity-type
                  "institutions" '[:find [(pull ?e [*]) ...]
                                   :where [?e :institution/id _]]
                  "accounts" '[:find [(pull ?e [*]) ...]
                               :where [?e :account/external-id _]]
                  "transactions" '[:find [(pull ?e [* {:transaction/category [:db/id :category/name]
                                                       :transaction/account [:db/id :account/external-name]}]) ...]
                                  :where [?e :transaction/external-id _]]
                  (throw (Exception. "Unknown entity type")))
          results (d/q query (d/db db/conn))]
      (json-response {:success true :data results}))
    (catch Exception e
      {:status 400
       :headers {"Content-Type" "application/json"
                 "Access-Control-Allow-Origin" "*"}
       :body (json/write-json-str {:success false :error (.getMessage e)})})))

(defn- stats-handler []
  "Get basic database statistics"
  (try
    (let [db (d/db db/conn)
          institution-count (count (d/q '[:find ?e :where [?e :institution/id _]] db))
          account-count (count (d/q '[:find ?e :where [?e :account/external-id _]] db))
          transaction-count (count (d/q '[:find ?e :where [?e :transaction/external-id _]] db))]
      (json-response {:success true
                      :data {:institutions institution-count
                             :accounts account-count
                             :transactions transaction-count}}))
    (catch Exception e
      {:status 500
       :headers {"Content-Type" "application/json"
                 "Access-Control-Allow-Origin" "*"}
       :body (json/write-json-str {:success false :error (.getMessage e)})})))

;;
;; Plaid Endpoint Handlers (Phase 2)
;; For Phase 2/3: Load secrets and plaid-config directly in handlers
;; TODO Phase 5: Refactor to use dependency injection via component system
;;

(def ^:private hardcoded-user-id
  "Hardcoded user ID for Phase 2/3 testing. Will be removed in Phase 7."
  "test-user")

(defn- load-secrets-and-config
  "Load secrets and plaid config. Returns map with :secrets and :plaid-config.
   Throws exception if secrets cannot be loaded."
  []
  (try
    (let [secrets-data (secrets/load-secrets)
          plaid-config (secrets/get-secret secrets-data :plaid)]
      (when-not plaid-config
        (throw (ex-info "Plaid configuration not found in secrets"
                        {:hint "Run 'bb secrets edit' to add Plaid credentials"})))
      {:secrets secrets-data
       :plaid-config plaid-config})
    (catch Exception e
      (throw (ex-info "Failed to load secrets for Plaid integration"
                      {:error (.getMessage e)}
                      e)))))

(defn- plaid-create-link-token-handler []
  "POST /api/plaid/create-link-token
   Generate link_token for Plaid Link frontend initialization.
   Uses hardcoded user-id for Phase 2/3."
  (try
    (let [{:keys [plaid-config]} (load-secrets-and-config)
          link-token (plaid/create-link-token plaid-config hardcoded-user-id)]
      (json-response {:success true
                      :data {:linkToken link-token}}))
    (catch Exception e
      {:status 500
       :headers {"Content-Type" "application/json"
                 "Access-Control-Allow-Origin" "*"}
       :body (json/write-json-str {:success false :error (.getMessage e)})})))

(defn- plaid-exchange-token-handler [req]
  "POST /api/plaid/exchange-token
   Exchange public_token for access_token and store encrypted credential.
   Request body: {:publicToken string}
   Returns raw Plaid response with access_token and item_id."
  (try
    (let [body (slurp (:body req))
          data (read-json body)
          public-token (:publicToken data)
          {:keys [secrets plaid-config]} (load-secrets-and-config)]

      (when-not public-token
        (throw (ex-info "publicToken is required" {:body data})))

      ;; Exchange public token for access token
      (let [result (plaid/exchange-public-token plaid-config public-token)
            access-token (:access_token result)]

        ;; Store encrypted credential in database
        (credentials/store-credential! db/conn secrets :plaid access-token)

        ;; Return raw Plaid response
        (json-response {:success true :data result})))
    (catch Exception e
      {:status 400
       :headers {"Content-Type" "application/json"
                 "Access-Control-Allow-Origin" "*"}
       :body (json/write-json-str {:success false :error (.getMessage e)})})))

(defn- plaid-get-accounts-handler []
  "GET /api/plaid/accounts
   Fetch accounts using stored credential.
   Returns raw Plaid accounts JSON."
  (try
    (let [{:keys [secrets plaid-config]} (load-secrets-and-config)
          access-token (credentials/get-credential db/conn secrets :plaid)]

      (when-not access-token
        (throw (ex-info "No Plaid credential found. Please connect your bank account first."
                        {:hint "Use POST /api/plaid/exchange-token to link your account"})))

      ;; Fetch accounts from Plaid
      (let [accounts (plaid/fetch-accounts plaid-config access-token)]
        (json-response {:success true :data accounts})))
    (catch Exception e
      {:status 400
       :headers {"Content-Type" "application/json"
                 "Access-Control-Allow-Origin" "*"}
       :body (json/write-json-str {:success false :error (.getMessage e)})})))

(defn- plaid-get-transactions-handler [req]
  "POST /api/plaid/transactions
   Fetch transactions using stored credential.
   Request body: {:startDate string, :endDate string} (YYYY-MM-DD format)
   Returns raw Plaid transactions JSON."
  (try
    (let [body (slurp (:body req))
          data (read-json body)
          start-date (:startDate data)
          end-date (:endDate data)
          {:keys [secrets plaid-config]} (load-secrets-and-config)
          access-token (credentials/get-credential db/conn secrets :plaid)]

      (when-not access-token
        (throw (ex-info "No Plaid credential found. Please connect your bank account first."
                        {:hint "Use POST /api/plaid/exchange-token to link your account"})))

      (when-not (and start-date end-date)
        (throw (ex-info "startDate and endDate are required"
                        {:body data
                         :hint "Format: YYYY-MM-DD"})))

      ;; Fetch transactions from Plaid
      (let [transactions (plaid/fetch-transactions plaid-config access-token start-date end-date)]
        (json-response {:success true :data transactions})))
    (catch Exception e
      {:status 400
       :headers {"Content-Type" "application/json"
                 "Access-Control-Allow-Origin" "*"}
       :body (json/write-json-str {:success false :error (.getMessage e)})})))

(defn app [req]
  "Main request handler"
  (let [uri (:uri req)
        method (:request-method req)]
    (println "Request:" method uri) ;; Debug logging
    (cond
      ;; CORS preflight
      (= method :options)
      (do
        (println "Handling OPTIONS request for:" uri) ;; Debug logging
        {:status 200
         :headers {"Access-Control-Allow-Origin" "*"
                   "Access-Control-Allow-Methods" "GET, POST, PUT, DELETE, OPTIONS"
                   "Access-Control-Allow-Headers" "Content-Type"}})

      ;; Stats endpoint
      (= uri "/api/stats")
      (stats-handler)

      ;; Get all entities of a type
      (and (= method :get) (re-matches #"/api/(institutions|accounts|transactions)" uri))
      (let [entity-type (last (clojure.string/split uri #"/"))]
        (get-all-handler entity-type))

      ;; Execute custom query
      (and (= method :post) (= uri "/api/query"))
      (let [body (slurp (:body req))
            data (read-json body)
            query-str (:query data)]
        (query-handler query-str))

      ;; Serve index.html for root path
      (= uri "/")
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (slurp "resources/public/index.html")}

      ;; Serve static files from public directory
      (re-matches #"/js/.*\.cljs" uri)
      (let [file-path (str "resources/public" uri)]
        (try
          {:status 200
           :headers {"Content-Type" "text/plain; charset=utf-8"}
           :body (slurp file-path)}
          (catch Exception e
            {:status 404
             :headers {"Content-Type" "application/json"}
             :body (json/write-json-str {:error "File not found"})})))

      ;; Serve favicon (or return 204 No Content to suppress error)
      (= uri "/favicon.ico")
      {:status 204}

      ;; Category endpoints
      (and (= method :get) (= uri "/api/categories"))
      (try
        (let [cats (categories/list-all db/conn)]
          (json-response {:success true :data cats}))
        (catch Exception e
          {:status 500
           :headers {"Content-Type" "application/json"
                     "Access-Control-Allow-Origin" "*"}
           :body (json/write-json-str {:success false :error (.getMessage e)})}))

      (and (= method :post) (= uri "/api/categories"))
      (try
        (let [body (slurp (:body req))
              data (read-json body)
              ;; Add category namespace to keys and convert values to keywords
              normalized-data (cond-> {:category/name (:name data)
                                       :category/type (keyword (:type data))
                                       :category/ident (keyword (:ident data))}
                                (:sortOrder data) (assoc :category/sort-order (:sortOrder data)))
              category (categories/create! db/conn normalized-data)]
          (json-response {:success true :data category}))
        (catch Exception e
          {:status 400
           :headers {"Content-Type" "application/json"
                     "Access-Control-Allow-Origin" "*"}
           :body (json/write-json-str {:success false :error (.getMessage e)})}))

      (and (= method :put) (re-matches #"/api/categories/(\d+)" uri))
      (try
        (let [db-id (parse-long (last (re-matches #"/api/categories/(\d+)" uri)))
              body (slurp (:body req))
              data (read-json body)
              ;; Add category namespace to keys and convert values to keywords
              normalized-data (cond-> {}
                                (:name data) (assoc :category/name (:name data))
                                (:type data) (assoc :category/type (keyword (:type data)))
                                (:ident data) (assoc :category/ident (keyword (:ident data)))
                                (:sortOrder data) (assoc :category/sort-order (:sortOrder data)))
              updated (categories/update! db/conn db-id normalized-data)]
          (json-response {:success true :data updated}))
        (catch Exception e
          {:status 400
           :headers {"Content-Type" "application/json"
                     "Access-Control-Allow-Origin" "*"}
           :body (json/write-json-str {:success false :error (.getMessage e)})}))

      (and (= method :delete) (re-matches #"/api/categories/(\d+)" uri))
      (try
        (let [db-id (parse-long (last (re-matches #"/api/categories/(\d+)" uri)))]
          ;; Check if category has transactions before deleting
          (if (categories/has-transactions? db/conn db-id)
            {:status 400
             :headers {"Content-Type" "application/json"
                       "Access-Control-Allow-Origin" "*"}
             :body (json/write-json-str {:success false :error "Cannot delete category with assigned transactions"})}
            (do
              (categories/delete! db/conn db-id)
              (json-response {:success true :data {:deleted true}}))))
        (catch Exception e
          {:status 400
           :headers {"Content-Type" "application/json"
                     "Access-Control-Allow-Origin" "*"}
           :body (json/write-json-str {:success false :error (.getMessage e)})}))

      ;; Batch update category sort orders
      (and (= method :post) (= uri "/api/categories/batch-sort"))
      (try
        (let [body (slurp (:body req))
              data (read-json body)
              ;; Convert from camelCase to category namespace format
              updates (mapv (fn [update]
                              {:db/id (:id update)
                               :category/sort-order (:sortOrder update)})
                            (:updates data))
              result (categories/batch-update-sort-orders! db/conn updates)]
          (json-response {:success true :data result}))
        (catch Exception e
          {:status 400
           :headers {"Content-Type" "application/json"
                     "Access-Control-Allow-Origin" "*"}
           :body (json/write-json-str {:success false :error (.getMessage e)})}))

      ;; Transaction category assignment endpoint
      (and (= method :put) (re-matches #"/api/transactions/(\d+)/category" uri))
      (try
        (let [tx-id (parse-long (second (re-matches #"/api/transactions/(\d+)/category" uri)))
              body (slurp (:body req))
              data (read-json body)
              category-id (:categoryId data)
              updated (transactions/update-category! db/conn tx-id category-id)]
          (json-response {:success true :data updated}))
        (catch Exception e
          {:status 400
           :headers {"Content-Type" "application/json"
                     "Access-Control-Allow-Origin" "*"}
           :body (json/write-json-str {:success false :error (.getMessage e)})}))

      ;; Plaid endpoints (Phase 2)
      (and (= method :post) (= uri "/api/plaid/create-link-token"))
      (plaid-create-link-token-handler)

      (and (= method :post) (= uri "/api/plaid/exchange-token"))
      (plaid-exchange-token-handler req)

      (and (= method :get) (= uri "/api/plaid/accounts"))
      (plaid-get-accounts-handler)

      (and (= method :post) (= uri "/api/plaid/transactions"))
      (plaid-get-transactions-handler req)

      ;; Default 404
      :else
      {:status 404
       :headers {"Content-Type" "application/json"}
       :body (json/write-json-str {:error "Not found"})})))

(defonce server (atom nil))

(defn start-server!
  "Start the HTTP server on the specified port"
  ([] (start-server! 8080))
  ([port]
   (when @server
     (println "Stopping existing server...")
     (@server))
   ;; Database connection should already be open from defonce
   (println "Database connection ready")
   (println (str "Starting server on http://localhost:" port))
   (reset! server (http/run-server #'app {:port port}))
   (println "Server started!")))

(defn stop-server! []
  "Stop the HTTP server"
  (when @server
    (@server)
    (reset! server nil)
    (println "Server stopped!")))

(comment
  (start-server!)
  (stop-server!))

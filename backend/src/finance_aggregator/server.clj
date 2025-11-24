(ns finance-aggregator.server
  (:require
   [org.httpkit.server :as http]
   [charred.api :as json]
   [datalevin.core :as d]
   [finance-aggregator.db :as db]
   [finance-aggregator.db.categories :as categories]
   [finance-aggregator.db.transactions :as transactions]))

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

(defn app [req]
  "Main request handler"
  (let [uri (:uri req)
        method (:request-method req)]
    (cond
      ;; CORS preflight
      (= method :options)
      {:status 200
       :headers {"Access-Control-Allow-Origin" "*"
                 "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
                 "Access-Control-Allow-Headers" "Content-Type"}}

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

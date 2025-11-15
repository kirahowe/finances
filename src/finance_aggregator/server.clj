(ns finance-aggregator.server
  (:require
   [org.httpkit.server :as http]
   [charred.api :as json]
   [datalevin.core :as d]
   [finance-aggregator.db :as db]
   [finance-aggregator.db.categories :as categories]))

(defn json-response [data]
  "Create a JSON response"
  {:status 200
   :headers {"Content-Type" "application/json"
             "Access-Control-Allow-Origin" "*"}
   :body (json/write-json-str data)})

(defn read-json [body-str]
  "Read JSON data from request body"
  (json/read-json body-str))

(defn- query-handler [query-str]
  "Execute a Datalog query and return results"
  (try
    (let [query (read-string query-str)
          results (d/q query @db/conn)]
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
                  "transactions" '[:find [(pull ?e [*]) ...]
                                   :where [?e :transaction/external-id _]]
                  (throw (Exception. "Unknown entity type")))
          results (d/q query @db/conn)]
      (json-response {:success true :data results}))
    (catch Exception e
      {:status 400
       :headers {"Content-Type" "application/json"
                 "Access-Control-Allow-Origin" "*"}
       :body (json/write-json-str {:success false :error (.getMessage e)})})))

(defn- stats-handler []
  "Get basic database statistics"
  (try
    (let [institution-count (count (d/q '[:find ?e :where [?e :institution/id _]] @db/conn))
          account-count (count (d/q '[:find ?e :where [?e :account/external-id _]] @db/conn))
          transaction-count (count (d/q '[:find ?e :where [?e :transaction/external-id _]] @db/conn))]
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
              category (categories/create! db/conn data)]
          (json-response {:success true :data category}))
        (catch Exception e
          {:status 400
           :headers {"Content-Type" "application/json"
                     "Access-Control-Allow-Origin" "*"}
           :body (json/write-json-str {:success false :error (.getMessage e)})}))

      (and (= method :put) (re-matches #"/api/categories/(\d+)" uri))
      (try
        (let [db-id (Long/parseLong (last (re-matches #"/api/categories/(\d+)" uri)))
              body (slurp (:body req))
              data (read-json body)
              updated (categories/update! db/conn db-id data)]
          (json-response {:success true :data updated}))
        (catch Exception e
          {:status 400
           :headers {"Content-Type" "application/json"
                     "Access-Control-Allow-Origin" "*"}
           :body (json/write-json-str {:success false :error (.getMessage e)})}))

      (and (= method :delete) (re-matches #"/api/categories/(\d+)" uri))
      (try
        (let [db-id (Long/parseLong (last (re-matches #"/api/categories/(\d+)" uri)))]
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

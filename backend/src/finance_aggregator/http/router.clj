(ns finance-aggregator.http.router
  "HTTP router component using reitit.

   Creates and configures the main application router with:
   - API routes (under /api)
   - Static file routes
   - Middleware (CORS, JSON, exception handling)"
  (:require
   [reitit.ring :as ring]
   [reitit.ring.middleware.parameters :as parameters]
   [finance-aggregator.http.routes.api :as api]
   [finance-aggregator.http.routes.static :as static]
   [finance-aggregator.http.middleware :as middleware]
   [finance-aggregator.http.errors :as errors]))

(defn create-router
  "Create reitit router with all routes.

   Args:
     deps - Dependencies map with :db-conn, :secrets, :plaid-config

   Returns:
     Reitit router"
  [deps]
  (ring/router
   [(api/api-routes deps)
    (static/static-routes)]
   {;; Middleware is applied in create-handler, not here
    ;; Allow specific routes before parameterized routes
    :conflicts nil}))

(defn create-handler
  "Create Ring handler from router with default handler for 404s.

   Args:
     deps - Dependencies map with :db-conn, :secrets, :plaid-config, :cors-config

   Returns:
     Ring handler function"
  [{:keys [cors-config] :as deps}]
  (-> (ring/ring-handler
       (create-router deps)
       (ring/create-default-handler
        {:not-found (constantly {:status 404
                                 :headers {"Content-Type" "application/json"}
                                 :body "{\"error\":\"Not found\"}"})}))
      ;; Apply global middleware in correct order (innermost to outermost)
      ;; 1. Exception handling catches errors first
      errors/wrap-exception-handling
      ;; 2. JSON parsing/serialization
      middleware/wrap-json
      ;; 3. CORS wraps everything (outermost) so errors also get CORS headers
      (middleware/wrap-cors cors-config)))

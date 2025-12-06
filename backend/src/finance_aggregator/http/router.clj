(ns finance-aggregator.http.router
  "HTTP router component using reitit.

   Creates and configures the main application router with:
   - API routes (under /api)
   - Static file routes
   - Middleware (CORS, JSON, exception handling)"
  (:require
   [reitit.ring :as ring]
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
   {:data {:middleware [middleware/wrap-cors
                        middleware/wrap-json
                        errors/wrap-exception-handling]}
    ;; Allow specific routes before parameterized routes
    :conflicts nil}))

(defn create-handler
  "Create Ring handler from router with default handler for 404s.

   Args:
     deps - Dependencies map with :db-conn, :secrets, :plaid-config

   Returns:
     Ring handler function"
  [deps]
  (ring/ring-handler
   (create-router deps)
   (ring/create-default-handler
    {:not-found (constantly {:status 404
                             :headers {"Content-Type" "application/json"}
                             :body "{\"error\":\"Not found\"}"})})
   {:middleware [middleware/wrap-cors
                 middleware/wrap-json
                 errors/wrap-exception-handling]}))

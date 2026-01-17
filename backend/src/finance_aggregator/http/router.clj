(ns finance-aggregator.http.router
  "HTTP router component using reitit.

   Creates and configures the main application router with:
   - WebSocket endpoint (at /ws)
   - API routes (under /api)
   - Static file routes
   - Middleware (CORS, JSON, query params, exception handling)"
  (:require
   [reitit.ring :as ring]
   [ring.middleware.params :as params]
   [finance-aggregator.http.routes.api :as api]
   [finance-aggregator.http.routes.static :as static]
   [finance-aggregator.http.middleware :as middleware]
   [finance-aggregator.http.errors :as errors]
   [finance-aggregator.ws.handler :as ws]))

(defn- ws-routes
  "WebSocket routes. No middleware - raw http-kit WebSocket handling."
  []
  ["/ws" {:get {:handler ws/ws-handler
                :no-doc true}}])

(defn create-router
  "Create reitit router with all routes.

   Args:
     deps - Dependencies map with :db-conn, :secrets, :plaid-config

   Returns:
     Reitit router"
  [deps]
  (ring/router
   [(ws-routes)
    (api/api-routes deps)
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
                                 :body "{\"success\":false,\"error\":\"Not found\"}"})
         :method-not-allowed (constantly {:status 405
                                          :headers {"Content-Type" "application/json"}
                                          :body "{\"success\":false,\"error\":\"Method not allowed\"}"})
         :not-acceptable (constantly {:status 406
                                      :headers {"Content-Type" "application/json"}
                                      :body "{\"success\":false,\"error\":\"Not acceptable\"}"})}))
      ;; Apply global middleware in correct order (innermost to outermost)
      ;; 1. Exception handling catches errors first
      errors/wrap-exception-handling
      ;; 2. Query params parsing (parses :query-string into :query-params)
      params/wrap-params
      ;; 3. JSON parsing/serialization
      middleware/wrap-json
      ;; 4. CORS wraps everything (outermost) so errors also get CORS headers
      (middleware/wrap-cors cors-config)))

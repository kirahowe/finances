(ns finance-aggregator.http.server
  "HTTP server component using http-kit.
   Provides lifecycle management and delegates to the full server handler."
  (:require
   [org.httpkit.server :as http-kit]
   [finance-aggregator.server :as server]))

;;
;; Handler Delegation
;;

(defn create-handler
  "Create a Ring handler with database dependency injected.
   Delegates to the full application handler in server.clj."
  [db-component]
  ;; The server/app handler expects to use the global db/conn,
  ;; but we're using component-based architecture now.
  ;; For now, just delegate to the existing handler.
  ;; TODO: Refactor server.clj to accept db-component parameter
  server/app)

(defn wrap-middleware
  "Apply standard middleware to handler.
   Note: The server/app handler already has its own middleware,
   so we don't need to add additional layers here."
  [handler]
  handler)

;;
;; Server Lifecycle
;;

(defn start-server!
  "Start HTTP server on given port with database component.
   Returns a map with :server and :stop-fn."
  [port db-component]
  (let [handler (create-handler db-component)
        app (wrap-middleware handler)
        server (http-kit/run-server app {:port port})]
    {:server server
     :stop-fn (fn []
                (server :timeout 100)
                nil)
     :port port}))

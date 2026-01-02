(ns finance-aggregator.http.server
  "HTTP server component using http-kit.
   Provides lifecycle management for the HTTP server."
  (:require
   [finance-aggregator.lib.log :as log]
   [org.httpkit.server :as http-kit]))

;;
;; Server Lifecycle
;;

(defn start-server!
  "Start HTTP server on given port with router handler.

   Args:
     port - Port number to listen on
     router-handler - Ring handler function from router component

   Returns:
     Map with :server and :stop-fn"
  [port router-handler]
  (log/info "Starting HTTP server" {:port port})
  (let [server (http-kit/run-server router-handler {:port port})]
    (log/info "HTTP server started successfully" {:port port})
    {:server server
     :stop-fn (fn []
                (log/info "Stopping HTTP server" {:port port})
                (server :timeout 100)
                (log/info "HTTP server stopped")
                nil)
     :port port}))

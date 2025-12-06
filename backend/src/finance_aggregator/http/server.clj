(ns finance-aggregator.http.server
  "HTTP server component using http-kit.
   Provides lifecycle management for the HTTP server."
  (:require
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
  (println (str "Starting HTTP server on port " port "..."))
  (let [server (http-kit/run-server router-handler {:port port})]
    (println "HTTP server started successfully")
    {:server server
     :stop-fn (fn []
                (println "Stopping HTTP server...")
                (server :timeout 100)
                (println "HTTP server stopped")
                nil)
     :port port}))

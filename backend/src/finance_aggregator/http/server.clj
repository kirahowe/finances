(ns finance-aggregator.http.server
  "HTTP server component using http-kit.
   Provides lifecycle management and basic request handling."
  (:require
   [org.httpkit.server :as http-kit]
   [ring.middleware.cors :refer [wrap-cors]]
   [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.params :refer [wrap-params]]))

;;
;; Handler
;;

(defn health-handler
  "Simple health check endpoint."
  [_request]
  {:status 200
   :body {:status "ok"
          :service "finance-aggregator"}})

(defn not-found-handler
  "Default 404 handler."
  [_request]
  {:status 404
   :body {:error "Not found"}})

(defn create-handler
  "Create a Ring handler with database dependency injected.
   For now, just provides a health check endpoint."
  [db-component]
  (fn [request]
    (case [(:request-method request) (:uri request)]
      [:get "/health"] (health-handler request)
      (not-found-handler request))))

(defn wrap-middleware
  "Apply standard middleware to handler."
  [handler]
  (-> handler
      (wrap-json-response)
      (wrap-keyword-params)
      (wrap-json-body {:keywords? true})
      (wrap-params)
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :post :put :delete :options]
                 :access-control-allow-headers ["Content-Type" "Authorization"])))

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

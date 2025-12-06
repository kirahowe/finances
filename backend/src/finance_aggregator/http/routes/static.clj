(ns finance-aggregator.http.routes.static
  "Static file serving routes"
  (:require
   [clojure.java.io :as io]
   [charred.api :as json]))

(defn- serve-static-file
  "Serve a static file from resources/public directory.

   Args:
     file-path - Path relative to resources/public

   Returns:
     Ring response map"
  [file-path content-type]
  (let [resource-path (str "resources/public" file-path)]
    (if-let [file (io/file resource-path)]
      (if (.exists file)
        {:status 200
         :headers {"Content-Type" content-type}
         :body (slurp file)}
        {:status 404
         :headers {"Content-Type" "application/json"}
         :body (json/write-json-str {:error "File not found"})})
      {:status 404
       :headers {"Content-Type" "application/json"}
       :body (json/write-json-str {:error "File not found"})})))

(defn static-routes
  "Define static file serving routes.

   Returns:
     Reitit route data"
  []
  [""
   ;; Serve index.html for root path
   ["/" {:get {:handler (fn [_]
                          (serve-static-file "/index.html" "text/html"))
               :name ::index}}]
   ;; Serve ClojureScript files
   ["/js/*path" {:get {:handler (fn [request]
                                  (let [path (get-in request [:path-params :path])]
                                    (serve-static-file (str "/js/" path) "text/plain; charset=utf-8")))
                       :name ::js-files}}]
   ;; Serve favicon (or return 204 No Content to suppress error)
   ["/favicon.ico" {:get {:handler (fn [_] {:status 204})
                          :name ::favicon}}]])

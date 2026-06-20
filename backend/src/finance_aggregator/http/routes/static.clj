(ns finance-aggregator.http.routes.static
  "Static file serving routes.

   Serves vendored/built assets from the classpath under public/ (resources is on
   the classpath via deps.edn :paths). Content type is inferred from the file
   extension — notably JS is served as text/javascript so ES-module island
   bundles and the Datastar runtime load correctly."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [charred.api :as json]))

(def ^:private content-types
  {"html" "text/html; charset=utf-8"
   "js"   "text/javascript; charset=utf-8"
   "mjs"  "text/javascript; charset=utf-8"
   "css"  "text/css; charset=utf-8"
   "json" "application/json; charset=utf-8"
   "map"  "application/json; charset=utf-8"
   "svg"  "image/svg+xml"
   "ico"  "image/x-icon"})

(defn- ext [path]
  (when-let [i (str/last-index-of path ".")]
    (subs path (inc i))))

(defn- serve
  "Serve a file from the classpath under public/, inferring content type from the
   extension. Refuses path traversal; 404s when the resource is missing."
  [rel-path]
  (let [res (when-not (str/includes? rel-path "..")
              (io/resource (str "public/" rel-path)))]
    (if res
      {:status 200
       :headers {"Content-Type" (get content-types (ext rel-path) "application/octet-stream")}
       :body (slurp res)}
      {:status 404
       :headers {"Content-Type" "application/json"}
       :body (json/write-json-str {:error "File not found"})})))

(defn static-routes
  "Define static file serving routes.

   Returns:
     Reitit route data"
  []
  [""
   ;; Serve index.html for root path (fallback; hypermedia routes take precedence)
   ["/" {:get {:handler (fn [_] (serve "index.html"))
               :name ::index}}]
   ;; JavaScript: vendored runtime (/js/datastar.js) + island bundles (/js/islands/*)
   ["/js/*path" {:get {:handler (fn [req]
                                  (serve (str "js/" (get-in req [:path-params :path]))))
                       :name ::js-files}}]
   ;; Stylesheets
   ["/css/*path" {:get {:handler (fn [req]
                                   (serve (str "css/" (get-in req [:path-params :path]))))
                        :name ::css-files}}]
   ;; Serve favicon (or return 204 No Content to suppress error)
   ["/favicon.ico" {:get {:handler (fn [_] {:status 204})
                          :name ::favicon}}]])

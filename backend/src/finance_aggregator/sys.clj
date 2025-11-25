(ns finance-aggregator.sys
  "System lifecycle management.
   Provides functions for loading configuration and starting/stopping the system."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [integrant.core :as ig]))

(defn load-configs
  "Load and merge EDN config files from resources.
   Later configs override earlier ones."
  [paths]
  (reduce
   (fn [acc path]
     (let [resource (io/resource path)]
       (when-not resource
         (throw (ex-info (str "Config file not found: " path) {:path path})))
       (let [config (edn/read-string {:readers {'ig/ref ig/ref}}
                                     (slurp resource))]
         (merge acc config))))
   {}
   paths))

(defn prep-config
  "Prepare config for Integrant initialization.
   Validates config structure and loads necessary namespaces."
  [config]
  (when-not (map? config)
    (throw (ex-info "Config must be a map" {:config config})))
  ;; Load namespaces for all Integrant keys
  (doseq [k (keys config)]
    (when (qualified-keyword? k)
      (try
        (require (symbol (namespace k)))
        (catch Exception _
          ;; Namespace might not exist yet, that's ok
          nil))))
  config)

(defn start-system!
  "Start the system with given config.
   Returns the running system."
  [config]
  (ig/init config))

(defn stop-system!
  "Stop the running system gracefully."
  [system]
  (ig/halt! system))

(ns finance-aggregator.sys
  "System lifecycle management.
   Provides functions for loading configuration and starting/stopping the system."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [integrant.core :as ig]))

;; Holds the running system instance
(defonce system nil)

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

(defn start-system
  "Start the system with given config files.
   Loads configs, preps them, initializes the system, and stores it in the system var.
   Returns the running system."
  [configs]
  (let [initialised-sys (-> configs
                            (load-configs)
                            (prep-config)
                            (ig/init))]
    (println "System initialised")
    (alter-var-root #'system (constantly initialised-sys))
    initialised-sys))

(defn stop-system!
  "Stop the running system gracefully.
   This is not guaranteed to be called in all circumstances,
   but should be called upon receipt of a SIGTERM."
  []
  (println "Stopping system")
  (when system
    (ig/halt! system)))

(defn add-shutdown-hook!
  "Register a shutdown hook with the JVM.
   This is not guaranteed to be called in all circumstances,
   but should be called upon receipt of a SIGTERM."
  []
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. stop-system!)))

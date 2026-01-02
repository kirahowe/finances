(ns finance-aggregator.sys
  "System lifecycle management.
   Provides functions for loading configuration and starting/stopping the system.

   Follows 12-factor app principles:
   - Configuration in environment-specific files
   - Clear separation of environments (base, dev, test, prod)

   Uses meta-merge for deep merging of configs, allowing environment-specific
   configs to selectively override defaults from base-system.edn."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [finance-aggregator.lib.log :as log]
   [finance-aggregator.plaid.types :as plaid-types]
   [integrant.core :as ig]
   [meta-merge.core :refer [meta-merge]])
  (:import (java.net URI)))

;; Holds the running system instance
(defonce system nil)

;;
;; Default Configuration
;;

(def default-config-files
  "Default config files to load. Environment-specific config.edn is resolved
   from the classpath (e.g., env/dev/resources/config.edn for dev)."
  ["system/base-system.edn" "config.edn"])

;;
;; Environment Variable Support
;;

(defn get-env
  "Get environment variable by name.

   Parameters:
   - s: String or symbol representing the environment variable name

   Returns:
   String value of the environment variable, or nil if not set"
  [s]
  (System/getenv (str s)))

(def readers
  "Data readers for EDN configuration files.
   These readers enable flexible configuration with environment variables,
   defaults, and type conversions.

   Available readers:
   - #env: Read environment variable (e.g., #env \"PORT\")
   - #or: Return first non-nil value (e.g., #or [#env \"PORT\" \"8080\"])
   - #int: Convert string to integer (e.g., #int \"8080\")
   - #uri: Create URI from string (e.g., #uri \"http://localhost:8080\")
   - #resource: Load classpath resource (e.g., #resource \"config.edn\")
   - #regex: Create regex pattern (e.g., #regex \"test-.*\")
   - #plaid/country-code: Plaid CountryCode enum (e.g., #plaid/country-code \"US\")
   - #plaid/product: Plaid Products enum (e.g., #plaid/product :transactions)
   - #plaid/environment: Plaid API URL (e.g., #plaid/environment :sandbox)"
  (merge
   {'env get-env
    'or (fn [pair]
          (apply #(or %1 %2) pair))
    'uri (fn [v] (URI. v))
    'int (fn [v]
           (Integer/parseInt v))
    'resource (fn [v] (io/resource v))
    'regex (fn [r] (re-pattern r))}
   plaid-types/readers))

(defn- read-config
  "Read a single EDN config file from resources."
  [path]
  (let [resource (io/resource path)]
    (when-not resource
      (throw (ex-info (str "Config file not found: " path) {:path path})))
    (edn/read-string {:readers (merge readers {'ig/ref ig/ref})}
                     (slurp resource))))

(defn load-configs
  "Load and deep-merge EDN config files from resources.
   Uses meta-merge for deep merging - later configs override earlier ones,
   but nested maps are merged rather than replaced.

   This allows environment-specific configs to selectively override
   specific keys within nested structures.

   Parameters:
   - paths: Vector of resource paths (e.g. [\"system/base-system.edn\"
                                            \"config.edn\"])

   Returns:
   Deep-merged configuration map

   Throws:
   Exception if any config file is not found"
  [paths]
  (->> paths
       (map read-config)
       (apply meta-merge)))

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
   Returns the running system.

   Parameters:
   - configs: (optional) Vector of config file paths. Defaults to default-config-files."
  ([]
   (start-system default-config-files))
  ([configs]
   (log/info "Loading config files" {:files configs})
   (let [initialised-sys (-> configs
                             (load-configs)
                             (prep-config)
                             (ig/init))]
     (log/info "System initialised")
     (alter-var-root #'system (constantly initialised-sys))
     initialised-sys)))

(defn stop-system!
  "Stop the running system gracefully.
   This is not guaranteed to be called in all circumstances,
   but should be called upon receipt of a SIGTERM."
  []
  (log/info "Stopping system")
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

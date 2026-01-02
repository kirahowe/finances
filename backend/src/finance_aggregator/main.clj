(ns finance-aggregator.main
  "Main entry point for running the application server."
  (:require
   [finance-aggregator.lib.log :as log]
   [finance-aggregator.sys :as sys])
  (:gen-class))

(defn -main
  "Start the finance aggregator system.
   Loads configuration and starts all components (database, HTTP server).

   Uses default config files:
   - system/base-system.edn (base configuration)
   - config.edn (environment-specific overrides from classpath)"
  [& _args]
  (log/init!)
  (log/info "Starting Finance Aggregator" {:ci (System/getenv "CI")})
  (sys/add-shutdown-hook!)
  ;; Uses default-config-files which loads base-system.edn + config.edn
  ;; Environment-specific config.edn is resolved from classpath via alias:
  ;; - :dev alias -> env/dev/resources/config.edn
  ;; - :test alias -> env/test/resources/config.edn
  ;; - :prod alias -> env/prod/resources/config.edn
  (sys/start-system)
  ;; Keep the main thread alive
  @(promise))

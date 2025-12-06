(ns finance-aggregator.main
  "Main entry point for running the application server."
  (:require
   [finance-aggregator.sys :as sys])
  (:gen-class))

(defn -main
  "Start the finance aggregator system.
   Loads configuration and starts all components (database, HTTP server)."
  [& _args]
  (println (format "Starting Finance Aggregator... CI='%s'" (System/getenv "CI")))
  (sys/add-shutdown-hook!)
  (sys/start-system ["system/base-system.edn"
                     "system/dev.edn"])
  ;; Keep the main thread alive
  @(promise))

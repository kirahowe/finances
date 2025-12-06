(ns finance-aggregator.main
  "Main entry point for running the application server."
  (:require
   [finance-aggregator.sys :as sys])
  (:gen-class))

(defn -main
  "Start the finance aggregator system.
   Loads configuration and starts all components (database, HTTP server)."
  [& _args]
  (println "Starting Finance Aggregator...")
  (println "Loading configuration...")

  (let [config (sys/load-configs ["system/base-system.edn"
                                  "system/dev.edn"])
        config (sys/prep-config config)]

    (println "Starting system components...")
    (let [system (sys/start-system! config)]
      (println "✓ System started successfully")
      (println)
      (println "  HTTP Server: http://localhost:8080")
      (println "  Health Check: http://localhost:8080/health")
      (println "  Database: ./data/finance.db")
      (println)
      (println "Press Ctrl+C to stop")

      ;; Add shutdown hook to gracefully stop the system
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread. (fn []
                  (println)
                  (println "Shutting down gracefully...")
                  (sys/stop-system! system)
                  (println "✓ System stopped"))))

      ;; Keep the main thread alive
      @(promise))))

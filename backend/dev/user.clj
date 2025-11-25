(ns user
  "Development namespace for REPL-driven development.
   Provides helpers for starting, stopping, and resetting the system."
  (:require
   [finance-aggregator.sys :as sys]
   [integrant.repl :refer [clear go halt prep init reset reset-all]]
   [integrant.repl.state :as state]))

;; Configure integrant.repl to use our system configuration
(integrant.repl/set-prep!
 #(sys/prep-config
   (sys/load-configs ["system/base-system.edn"
                      "system/dev.edn"])))

(defn db
  "Get the database component from the running system."
  []
  (get state/system :finance-aggregator.db/connection))

(defn server
  "Get the HTTP server component from the running system."
  []
  (get state/system :finance-aggregator.http/server))

(comment
  ;; REPL workflow:

  ;; Start the system
  (go)

  ;; Get components
  (db)
  (server)

  ;; Access the database connection
  (require '[datalevin.core :as d])
  (require '[finance-aggregator.db.core :as db-core])

  (def conn (db-core/get-conn (db)))

  ;; Query the database
  (d/q '[:find ?name
         :where [_ :institution/name ?name]]
       @conn)

  ;; Reload code and restart system (after making changes)
  (reset)

  ;; Stop the system
  (halt)

  ;; Clear and reinitialize
  (reset-all)
  )

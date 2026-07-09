(ns finance-aggregator.db.core
  "Database connection lifecycle management.
   Handles opening and closing Datalevin connections."
  (:require
   [clojure.java.io :as io]
   [datalevin.core :as d]
   [finance-aggregator.data.schema :as schema]
   [finance-aggregator.db.migrations :as migrations]))

(defn start-db!
  "Open a Datalevin connection with the application schema, then run every pending
   idempotent data migration (db.migrations) so every entry point opens onto an
   up-to-date database. Returns the connection."
  [db-path]
  (let [conn (d/get-conn db-path schema/schema)]
    (migrations/migrate-splits! conn)
    ;; After migrate-splits! — it intentionally still writes the legacy attr this converts.
    (migrations/migrate-reviewed->reconciled! conn)
    conn))

(defn stop-db!
  "Close a Datalevin connection gracefully.
   Returns nil."
  [conn]
  (d/close conn)
  nil)

(defn get-conn
  "Extract the connection from a db component.
   Component is expected to be a map with :conn key."
  [db-component]
  (:conn db-component))

(defn delete-database!
  "Delete a database directory (for testing).
   WARNING: This permanently deletes all data!"
  [db-path]
  (let [dir (io/file db-path)]
    (when (.exists dir)
      (doseq [f (reverse (file-seq dir))]
        (.delete f)))))

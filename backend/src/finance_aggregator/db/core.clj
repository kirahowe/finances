(ns finance-aggregator.db.core
  "Database connection lifecycle management.
   Handles opening and closing Datalevin connections."
  (:require
   [datalevin.core :as d]
   [finance-aggregator.data.schema :as schema]))

(defn start-db!
  "Open a Datalevin connection with the application schema.
   Returns the connection."
  [db-path]
  (d/get-conn db-path schema/schema))

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
  (let [dir (clojure.java.io/file db-path)]
    (when (.exists dir)
      (doseq [f (reverse (file-seq dir))]
        (.delete f)))))

(ns finance-aggregator.system
  "Integrant component definitions for the finance aggregator system.
   Each component defines lifecycle methods (init-key, halt-key!) for
   managed resources like database connections and HTTP servers."
  (:require
   [finance-aggregator.db.core :as db]
   [finance-aggregator.http.server :as http]
   [integrant.core :as ig]))

;;
;; Configuration Values (pass through)
;;

(defmethod ig/init-key :finance-aggregator.system/db-path
  [_ value]
  value)

(defmethod ig/init-key :finance-aggregator.system/http-port
  [_ value]
  value)

;;
;; Database Component
;;

(defmethod ig/init-key :finance-aggregator.db/connection
  [_ {:keys [db-path]}]
  (when-not db-path
    (throw (ex-info "Database path is required" {:config _})))
  (let [conn (db/start-db! db-path)]
    {:conn conn
     :db-path db-path}))

(defmethod ig/halt-key! :finance-aggregator.db/connection
  [_ component]
  (when-let [conn (:conn component)]
    (db/stop-db! conn)))

;;
;; HTTP Server Component
;;

(defmethod ig/init-key :finance-aggregator.http/server
  [_ {:keys [port db] :as config}]
  (when-not port
    (throw (ex-info "HTTP port is required" {:config config})))
  (when-not db
    (throw (ex-info "Database component is required" {:config config})))
  (http/start-server! port db))

(defmethod ig/halt-key! :finance-aggregator.http/server
  [_ component]
  (when-let [stop-fn (:stop-fn component)]
    (stop-fn)))

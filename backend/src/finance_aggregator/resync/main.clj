(ns finance-aggregator.resync.main
  "Headless entry point: run ONE resilient-sync pass over all connections, then
   exit. Initializes only the components a resync needs - secrets, the db
   connection, and Plaid config - and NOT the http server, so it is safe to run
   from `bb resync` or a cron job alongside the live server. JVM is required
   (Datalevin and the Plaid SDK can't run under babashka SCI), which is why this
   is a `clojure -M:resync` entry rather than a bb task body.

   `bb resync` -> `clojure -M:resync` -> here -> resync/resync-all! -> exit 0."
  (:require
   [finance-aggregator.lib.log :as log]
   [finance-aggregator.resync :as resync]
   [finance-aggregator.sys :as sys]
   [integrant.core :as ig])
  (:gen-class))

(def ^:private components
  "The subset of system keys a resync pass needs. ig/init pulls in their
   transitive deps (secrets, config constants); the http router/server keys in
   the config are left uninitialized."
  [:finance-aggregator.system/secrets
   :finance-aggregator.db/connection
   :finance-aggregator.plaid/config])

(defn -main [& _args]
  (log/init!)
  (log/info "Starting resync pass")
  (let [system (-> sys/default-config-files
                   sys/load-configs
                   sys/prep-config
                   (ig/init components))]
    (try
      (let [deps {:db-conn (:conn (get system :finance-aggregator.db/connection))
                  :secrets (get system :finance-aggregator.system/secrets)
                  :plaid-config (get system :finance-aggregator.plaid/config)}
            summary (resync/resync-all! deps)]
        (log/info "Resync pass finished" {:summary summary}))
      (finally
        (ig/halt! system))))
  (System/exit 0))

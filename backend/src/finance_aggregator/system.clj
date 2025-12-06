(ns finance-aggregator.system
  "Integrant component definitions for the finance aggregator system.
   Each component defines lifecycle methods (init-key, halt-key!) for
   managed resources like database connections and HTTP servers."
  (:require
   [finance-aggregator.db.core :as db]
   [finance-aggregator.http.server :as http]
   [finance-aggregator.http.router :as router]
   [finance-aggregator.plaid.client :as plaid]
   [finance-aggregator.lib.secrets :as secrets]
   [integrant.core :as ig]))

;;
;; Configuration Constants
;; Simple values that pass through unchanged derive from ::const
;;

(defmethod ig/init-key ::const [_ value] value)

(derive :finance-aggregator.system/db-path ::const)
(derive :finance-aggregator.system/http-port ::const)
(derive :finance-aggregator.system/secrets-key-file ::const)
(derive :finance-aggregator.system/secrets-file ::const)

;;
;; Secrets Component
;;

(defmethod ig/init-key :finance-aggregator.system/secrets
  [_ {:keys [key-file secrets-file] :as config}]
  (when-not key-file
    (throw (ex-info "Secrets key file is required" {:config config})))
  (when-not secrets-file
    (throw (ex-info "Secrets file is required" {:config config})))
  (println "Loading encrypted secrets from" secrets-file)
  (println "Using key file:" key-file)
  (let [loaded-secrets (secrets/load-secrets key-file secrets-file)]
    (println "Secrets loaded successfully")
    loaded-secrets))

(defmethod ig/halt-key! :finance-aggregator.system/secrets
  [_ _component]
  ;; Secrets are just data, nothing to clean up
  nil)

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
;; HTTP Router Component
;;

(defmethod ig/init-key :finance-aggregator.http/router
  [_ {:keys [db secrets plaid-config] :as config}]
  (when-not db
    (throw (ex-info "Database component is required for router" {:config config})))
  (when-not secrets
    (throw (ex-info "Secrets component is required for router" {:config config})))
  (when-not plaid-config
    (throw (ex-info "Plaid config component is required for router" {:config config})))
  (println "Creating HTTP router with dependencies")
  (let [deps {:db-conn (:conn db)
              :secrets secrets
              :plaid-config plaid-config}]
    (router/create-handler deps)))

(defmethod ig/halt-key! :finance-aggregator.http/router
  [_ _component]
  ;; Router is stateless, nothing to clean up
  nil)

;;
;; HTTP Server Component
;;

(defmethod ig/init-key :finance-aggregator.http/server
  [_ {:keys [port router] :as config}]
  (when-not port
    (throw (ex-info "HTTP port is required" {:config config})))
  (when-not router
    (throw (ex-info "Router component is required" {:config config})))
  (http/start-server! port router))

(defmethod ig/halt-key! :finance-aggregator.http/server
  [_ component]
  (when-let [stop-fn (:stop-fn component)]
    (stop-fn)))

;;
;; Plaid Configuration Component
;;

(defmethod ig/init-key :finance-aggregator.plaid/config
  [_ {:keys [secrets] :as config}]
  (when-not secrets
    (throw (ex-info "Secrets component is required for Plaid config" {:config config})))
  (println "Loading Plaid configuration from secrets...")
  (if-let [plaid-config (secrets/get-secret secrets :plaid)]
    (do
      (println "Plaid configuration loaded:"
               {:client-id "***" ; Don't log secrets
                :secret "***"
                :environment (:environment plaid-config)})
      plaid-config)
    (throw (ex-info "Plaid credentials not found in secrets"
                    {:available-keys (keys secrets)
                     :hint "Run 'bb secrets edit' to add Plaid credentials"}))))

(defmethod ig/halt-key! :finance-aggregator.plaid/config
  [_ _component]
  ;; Plaid config is stateless, nothing to clean up
  nil)

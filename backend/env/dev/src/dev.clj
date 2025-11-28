(ns dev
  "Development namespace with tools and helpers for REPL-driven development.

   This namespace is loaded when you call (dev) from the user namespace.
   It provides convenient access to the system, database, and dev tools."
  (:refer-clojure :exclude [test])
  (:require
   ;; REPL utilities
   [clojure.repl :refer :all]
   [clojure.pprint :as pp]
   [clojure.java.io :as io]
   [clojure.test]

   ;; Code reloading
   [clojure.tools.namespace.repl :as tns :refer [refresh refresh-all]]

   ;; Integrant system management
   [integrant.repl :refer [clear go halt prep init reset reset-all]]
   [integrant.repl.state :as state]

   ;; Application code
   [finance-aggregator.sys :as sys]
   [finance-aggregator.db.core :as db-core]

   ;; Database
   [datalevin.core :as d]))

;;
;; Configure tools.namespace
;;

;; Don't reload the dev namespace itself when refreshing
;; (Temporarily disable this line if you need to work on the dev namespace)
(tns/disable-reload! (find-ns 'dev))

;; Set directories to scan for code changes
(tns/set-refresh-dirs "env/dev/src" "src" "test" "resources")

;;
;; Load scope-capture as a side effect
;; This provides excellent debugging capabilities
;;

(require 'sc.api)

(comment
  ;; Scope capture usage examples:

  ;; 1. Add (sc.api/spy) to any form to capture its value
  ;;    Example: (+ 1 (sc.api/spy (* 2 3)))

  ;; 2. Use (sc.api/defsc) instead of defn to instrument a function
  ;;    Example: (sc.api/defsc my-fn [x] (+ x 1))

  ;; 3. View captured values in your editor or with:
  ;;    (sc.api/ep-info)  ; Show recent captures
  ;;    (sc.api/brk)      ; Set a breakpoint

  ;; See: https://github.com/vvvvalvalval/scope-capture
  )

;;
;; Configure Integrant
;;

(integrant.repl/set-prep!
 #(sys/prep-config
   (sys/load-configs ["system/base-system.edn"
                      "system/dev.edn"])))

;;
;; Convenience Aliases
;;

(def start go)
(def stop halt)

;;
;; Component Access Helpers
;;

(defn system
  "Get the current running system (all components)."
  []
  state/system)

(defn db
  "Get the database component from the running system."
  []
  (get state/system :finance-aggregator.db/connection))

(defn conn
  "Get the Datalevin connection from the database component."
  []
  (when-let [db-component (db)]
    (db-core/get-conn db-component)))

(defn server
  "Get the HTTP server component from the running system."
  []
  (get state/system :finance-aggregator.http/server))

;;
;; Database Helpers
;;

(defn query
  "Execute a Datalog query against the current database.

   Examples:
     (query '[:find ?name :where [_ :institution/name ?name]])
     (query '[:find ?e :where [?e :user/id \"test-user\"]])"
  [q & inputs]
  (when-let [c (conn)]
    (apply d/q q @c inputs)))

(defn pull-entity
  "Pull an entity by lookup ref or eid.

   Examples:
     (pull-entity [:user/id \"test-user\"])
     (pull-entity 123)"
  [eid-or-lookup]
  (when-let [c (conn)]
    (d/pull @c '[*] eid-or-lookup)))

(defn all-entities
  "Get all entities of a given type.

   Examples:
     (all-entities :user/id)
     (all-entities :account/external-id)"
  [attr]
  (query `[:find [(pull ?e [~'*]) ...]
           :where [?e ~attr]]))

(defn transact!
  "Execute a transaction against the current database.

   Example:
     (transact! [{:user/id \"test-user\"
                  :user/email \"test@example.com\"
                  :user/created-at (java.util.Date.)}])"
  [tx-data]
  (when-let [c (conn)]
    (d/transact! c tx-data)))

;;
;; Development Utilities
;;

(defn clear-db!
  "Clear all data from the current database.
   WARNING: This deletes everything!"
  []
  (when-let [c (conn)]
    (let [eids (d/q '[:find [?e ...]
                      :where [?e _ _]]
                   @c)]
      (d/transact! c (mapv (fn [eid] [:db/retractEntity eid]) eids))
      (println "Cleared" (count eids) "entities from database."))))

(defn show-schema
  "Show the database schema."
  []
  (require 'finance-aggregator.data.schema)
  (let [schema-var (resolve 'finance-aggregator.data.schema/schema)]
    (pp/pprint (keys (var-get schema-var)))))

(defn show-config
  "Show the current system configuration."
  []
  (let [config (sys/load-configs ["system/base-system.edn" "system/dev.edn"])]
    (pp/pprint config)))

(defn server-info
  "Show information about the running server."
  []
  (if-let [s (server)]
    (do
      (println "HTTP Server Running:")
      (println "  Port:" (:port s))
      (println "  Health: http://localhost:" (:port s) "/health")
      (println "  Stop with: (halt) or (stop)"))
    (println "Server not running. Start with: (go) or (start)")))

(defn db-info
  "Show information about the database."
  []
  (if-let [c (conn)]
    (let [entity-count (count (d/q '[:find [?e ...]
                                     :where [?e _ _]]
                                  @c))
          db-component (db)
          _ (require 'finance-aggregator.data.schema)
          schema-var (resolve 'finance-aggregator.data.schema/schema)
          schema-count (count (keys (var-get schema-var)))]
      (println "Database Connected:")
      (println "  Path:" (:db-path db-component))
      (println "  Entities:" entity-count)
      (println "  Schema attributes:" schema-count))
    (println "Database not connected. Start system with: (go) or (start)")))

(defn status
  "Show the status of all components."
  []
  (println)
  (println "System Status")
  (println "=============")
  (println)
  (if (system)
    (do
      (println "Components running:")
      (doseq [k (keys (system))]
        (println "  ✓" k))
      (println)
      (db-info)
      (println)
      (server-info))
    (do
      (println "System not running.")
      (println "Start with: (go) or (start)")))
  (println))

;;
;; Test Helpers
;;

(defn test
  "Run tests for a namespace or all tests.

   Examples:
     (test)                              ; Run all tests
     (test 'finance-aggregator.db.core-test)  ; Run specific namespace"
  ([]
   (tns/refresh)
   (clojure.test/run-all-tests #"finance-aggregator\..*-test"))
  ([ns-sym]
   (tns/refresh)
   (require ns-sym)
   (clojure.test/run-tests ns-sym)))

;;
;; Sample Data Helpers
;;

(defn create-test-user!
  "Create a test user in the database.
   Returns the user entity."
  ([] (create-test-user! "test-user"))
  ([user-id]
   (transact! [{:user/id user-id
               :user/email (str user-id "@example.com")
               :user/created-at (java.util.Date.)}])
   (pull-entity [:user/id user-id])))

(defn create-test-institution!
  "Create a test institution in the database.
   Returns the institution entity."
  ([] (create-test-institution! "test-bank"))
  ([inst-id]
   (transact! [{:institution/id inst-id
               :institution/name (str "Test " inst-id)
               :institution/domain (str inst-id ".com")}])
   (pull-entity [:institution/id inst-id])))

(comment
  ;; ===========================================
  ;; Common REPL Workflows
  ;; ===========================================

  ;; Start the system
  (go)

  ;; Check status
  (status)

  ;; Work with the database
  (create-test-user!)
  (all-entities :user/id)
  (query '[:find ?email :where [_ :user/email ?email]])

  ;; After making code changes
  (reset)  ; or (refresh) if you don't need to restart components

  ;; Check what's running
  (system)
  (db)
  (server)
  (conn)

  ;; Database introspection
  (show-schema)
  (db-info)

  ;; Clear test data
  (clear-db!)

  ;; Stop the system
  (halt)

  ;; Full reset (clears everything)
  (reset-all)

  ;; ===========================================
  ;; Debugging with scope-capture
  ;; ===========================================

  ;; Add (sc.api/spy) to capture intermediate values:
  (let [x 10
        y (sc.api/spy (* x 2))  ; Captures the value 20
        z (+ y 5)]
    z)

  ;; View recent captures
  (sc.api/ep-info)

  ;; See docs: https://github.com/vvvvalvalval/scope-capture

  )

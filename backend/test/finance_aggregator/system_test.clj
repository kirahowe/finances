(ns finance-aggregator.system-test
  "Integration tests for Integrant system components."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datalevin.core :as d]
   [finance-aggregator.db.core :as db]
   [finance-aggregator.sys :as sys]
   [finance-aggregator.system] ;; Load Integrant component definitions
   [integrant.core :as ig]))

(def test-db-path (str "data/test-system-" (System/currentTimeMillis) ".db"))

(use-fixtures :each
  (fn [f]
    ;; Cleanup before test
    (when (.exists (clojure.java.io/file test-db-path))
      (db/delete-database! test-db-path))
    (f)
    ;; Cleanup after test
    (db/delete-database! test-db-path)))

(deftest db-component-lifecycle-test
  (testing "database component starts and stops correctly"
    (let [config {:finance-aggregator.db/connection
                  {:db-path test-db-path}}
          system (ig/init config)
          db-component (get system :finance-aggregator.db/connection)]

      (is (some? db-component) "DB component initialized")
      (is (contains? db-component :conn) "Component has :conn key")
      (is (d/conn? (:conn db-component)) "Connection is a Datalevin connection")

      ;; Stop the system
      (ig/halt! system)

      ;; Verify database file was created
      (is (.exists (clojure.java.io/file test-db-path))
          "Database file exists after shutdown"))))

(deftest http-server-component-lifecycle-test
  (testing "HTTP server component starts and stops correctly"
    (let [config (sys/load-configs ["system/base-system.edn" "system/dev.edn"])
          ;; Override db path for testing
          config (assoc config :finance-aggregator.system/db-path test-db-path)
          ;; Use a different port to avoid conflicts
          config (assoc config :finance-aggregator.system/http-port 8081)
          prepped (sys/prep-config config)
          system (ig/init prepped)
          server (get system :finance-aggregator.http/server)]

      (is (some? server) "HTTP server component initialized")
      (is (contains? server :server) "Component has :server key")
      (is (fn? (:stop-fn server)) "Component has stop function")

      ;; Stop the system
      (ig/halt! system)

      ;; Verify database cleanup
      (db/delete-database! test-db-path))))

(deftest full-system-integration-test
  (testing "full system starts with all components"
    (let [config (sys/load-configs ["system/base-system.edn" "system/dev.edn"])
          config (assoc config :finance-aggregator.system/db-path test-db-path)
          config (assoc config :finance-aggregator.system/http-port 8082)
          config (sys/prep-config config)
          system (ig/init config)]

      (is (some? system) "System initialized")
      (is (contains? system :finance-aggregator.db/connection) "Has database component")
      (is (contains? system :finance-aggregator.http/server) "Has HTTP server component")

      ;; Test database is functional
      (let [db-component (get system :finance-aggregator.db/connection)
            conn (db/get-conn db-component)]
        (is (d/conn? conn) "Database connection is valid")

        ;; Test that we can transact data
        (d/transact! conn [{:db/id -1
                           :institution/id "test-inst"
                           :institution/name "Test Institution"}])

        ;; Test that we can query data
        (let [result (d/q '[:find ?name .
                           :where [_ :institution/name ?name]]
                         @conn)]
          (is (= "Test Institution" result) "Can query transacted data")))

      ;; Stop the system
      (ig/halt! system)

      ;; Verify database cleanup
      (db/delete-database! test-db-path))))

(deftest system-restart-test
  (testing "system can be stopped and restarted"
    (let [config (sys/load-configs ["system/base-system.edn" "system/dev.edn"])
          config (assoc config :finance-aggregator.system/db-path test-db-path)
          config (assoc config :finance-aggregator.system/http-port 8083)
          prepped (sys/prep-config config)]

      ;; Start system
      (let [system1 (ig/init prepped)]
        (is (some? system1) "First system started")

        ;; Stop system
        (ig/halt! system1))

      ;; Start again with same config
      (let [system2 (ig/init prepped)]
        (is (some? system2) "Second system started")

        ;; Stop system
        (ig/halt! system2))

      ;; Cleanup
      (db/delete-database! test-db-path))))

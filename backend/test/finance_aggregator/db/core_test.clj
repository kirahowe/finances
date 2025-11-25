(ns finance-aggregator.db.core-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datalevin.core :as d]
   [finance-aggregator.db.core :as db]))

(def test-db-path (str "data/test-db-core-" (System/currentTimeMillis) ".db"))

(use-fixtures :each
  (fn [f]
    ;; Cleanup before test
    (when (.exists (clojure.java.io/file test-db-path))
      (db/delete-database! test-db-path))
    (f)
    ;; Cleanup after test
    (db/delete-database! test-db-path)))

(deftest start-db-test
  (testing "starts a database connection"
    (let [conn (db/start-db! test-db-path)]
      (is (some? conn) "Connection is not nil")
      (is (d/conn? conn) "Returns a Datalevin connection")
      (db/stop-db! conn)))

  (testing "creates database file"
    (let [conn (db/start-db! test-db-path)]
      (is (.exists (clojure.java.io/file test-db-path)))
      (db/stop-db! conn))))

(deftest stop-db-test
  (testing "stops database connection"
    (let [conn (db/start-db! test-db-path)]
      (is (nil? (db/stop-db! conn)) "Returns nil"))))

(deftest get-conn-test
  (testing "extracts connection from component"
    (let [conn (db/start-db! test-db-path)
          component {:conn conn}
          extracted (db/get-conn component)]
      (is (= conn extracted) "Returns the same connection")
      (db/stop-db! conn))))

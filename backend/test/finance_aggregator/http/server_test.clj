(ns finance-aggregator.http.server-test
  "Tests for HTTP server component."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [finance-aggregator.db.core :as db]
   [finance-aggregator.http.server :as server]
   [org.httpkit.client :as http-client]))

(def test-db-path (str "data/test-http-server-" (System/currentTimeMillis) ".db"))
(def test-port 9090)

(use-fixtures :each
  (fn [f]
    ;; Cleanup before test
    (when (.exists (clojure.java.io/file test-db-path))
      (db/delete-database! test-db-path))
    (f)
    ;; Cleanup after test
    (db/delete-database! test-db-path)))

(deftest start-server-test
  (testing "starts HTTP server on specified port"
    (let [conn (db/start-db! test-db-path)
          db-component {:conn conn}
          server (server/start-server! test-port db-component)]

      (is (some? server) "Server component created")
      (is (contains? server :server) "Has :server key")
      (is (fn? (:stop-fn server)) "Has :stop-fn")

      ;; Test that server is responding
      (let [response @(http-client/get (str "http://localhost:" test-port "/health")
                                      {:timeout 2000})]
        (is (= 200 (:status response)) "Health endpoint returns 200"))

      ;; Stop server
      ((:stop-fn server))
      (db/stop-db! conn))))

(deftest stop-server-test
  (testing "stops HTTP server cleanly"
    (let [conn (db/start-db! test-db-path)
          db-component {:conn conn}
          server (server/start-server! test-port db-component)
          stop-fn (:stop-fn server)]

      ;; Server should be running
      (let [response @(http-client/get (str "http://localhost:" test-port "/health")
                                      {:timeout 2000})]
        (is (= 200 (:status response)) "Server is running"))

      ;; Stop the server
      (stop-fn)

      ;; Server should no longer respond (or connection refused)
      (let [response (try
                      @(http-client/get (str "http://localhost:" test-port "/health")
                                       {:timeout 1000})
                      (catch Exception e
                        {:error :connection-refused}))]
        (is (or (= :connection-refused (:error response))
                (not= 200 (:status response)))
            "Server stopped and no longer responds"))

      (db/stop-db! conn))))

(deftest handler-test
  (testing "creates Ring handler with database dependency"
    (let [conn (db/start-db! test-db-path)
          db-component {:conn conn}
          handler (server/create-handler db-component)]

      (is (fn? handler) "Handler is a function")

      ;; Test health endpoint
      (let [response (handler {:request-method :get
                              :uri "/health"})]
        (is (= 200 (:status response)) "Health check returns 200")
        (is (contains? (:body response) :status) "Response has status"))

      (db/stop-db! conn))))

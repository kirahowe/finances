(ns finance-aggregator.integration.routing-test
  "Integration tests for the new routing system"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [finance-aggregator.http.router :as router]
   [finance-aggregator.db.core :as db]
   [finance-aggregator.lib.secrets :as secrets]
   [charred.api :as json]))

(def ^:dynamic *test-db-conn* nil)
(def ^:dynamic *test-secrets* nil)
(def ^:dynamic *test-plaid-config* nil)

(defn test-db-fixture [f]
  "Create test database and mock dependencies for each test"
  (let [test-dir (str "/tmp/test-routing-" (System/currentTimeMillis))
        conn (db/start-db! test-dir)]
    (try
      ;; Define schema
      (binding [*test-db-conn* conn
                *test-secrets* {:test "secret"}
                *test-plaid-config* {:environment :sandbox
                                     :client-id "test-client-id"
                                     :secret "test-secret"}]
        (f))
      (finally
        (db/stop-db! conn)
        ;; Clean up test db directory
        (let [dir (clojure.java.io/file test-dir)]
          (when (.exists dir)
            (doseq [file (.listFiles dir)]
              (.delete file))
            (.delete dir)))))))

(use-fixtures :each test-db-fixture)

(deftest router-creation-test
  (testing "Router can be created with dependencies"
    (let [deps {:db-conn *test-db-conn*
                :secrets *test-secrets*
                :plaid-config *test-plaid-config*}
          handler (router/create-handler deps)]
      (is (fn? handler) "Handler is a function"))))

(deftest stats-endpoint-test
  (testing "GET /api/stats returns stats"
    (let [deps {:db-conn *test-db-conn*
                :secrets *test-secrets*
                :plaid-config *test-plaid-config*}
          handler (router/create-handler deps)
          response (handler {:request-method :get
                            :uri "/api/stats"})]
      (is (= 200 (:status response)))
      (is (string? (:body response)))
      (let [body (json/read-json (:body response) :key-fn keyword)]
        (is (true? (:success body)))
        (is (map? (:data body)))
        (is (contains? (:data body) :institutions))
        (is (contains? (:data body) :accounts))
        (is (contains? (:data body) :transactions))))))

(deftest not-found-test
  (testing "Unknown routes return 404"
    (let [deps {:db-conn *test-db-conn*
                :secrets *test-secrets*
                :plaid-config *test-plaid-config*}
          handler (router/create-handler deps)
          response (handler {:request-method :get
                            :uri "/api/nonexistent"})]
      (is (= 404 (:status response))))))

(deftest cors-test
  (testing "OPTIONS requests return CORS headers"
    (let [deps {:db-conn *test-db-conn*
                :secrets *test-secrets*
                :plaid-config *test-plaid-config*}
          handler (router/create-handler deps)
          response (handler {:request-method :options
                            :uri "/api/stats"})]
      (is (= 200 (:status response)))
      (is (= "*" (get-in response [:headers "Access-Control-Allow-Origin"]))))))

(deftest static-routes-test
  (testing "Favicon returns 204"
    (let [deps {:db-conn *test-db-conn*
                :secrets *test-secrets*
                :plaid-config *test-plaid-config*}
          handler (router/create-handler deps)
          response (handler {:request-method :get
                            :uri "/favicon.ico"})]
      (is (= 204 (:status response))))))

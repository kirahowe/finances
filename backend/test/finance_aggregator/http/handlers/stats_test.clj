(ns finance-aggregator.http.handlers.stats-test
  "Tests for stats handler"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [finance-aggregator.http.handlers.stats :as stats]
   [finance-aggregator.http.responses :as responses]
   [charred.api :as json]
   [datalevin.core :as d]))

(def ^:dynamic *test-conn* nil)

(defn test-db-fixture [f]
  "Create a test database for each test"
  (let [test-dir (str "/tmp/test-db-" (System/currentTimeMillis))
        conn (d/get-conn test-dir)]
    (try
      ;; Define schema
      (d/transact! conn
                   [{:db/ident :institution/id
                     :db/valueType :db.type/string
                     :db/unique :db.unique/identity}
                    {:db/ident :account/external-id
                     :db/valueType :db.type/string
                     :db/unique :db.unique/identity}
                    {:db/ident :transaction/external-id
                     :db/valueType :db.type/string
                     :db/unique :db.unique/identity}])
      (binding [*test-conn* conn]
        (f))
      (finally
        (d/close conn)
        ;; Clean up test db directory
        (let [dir (clojure.java.io/file test-dir)]
          (when (.exists dir)
            (doseq [file (.listFiles dir)]
              (.delete file))
            (.delete dir)))))))

(use-fixtures :each test-db-fixture)

(deftest stats-handler-test
  (testing "returns stats for empty database"
    (let [handler (stats/stats-handler {:db-conn *test-conn*})
          request {}
          response (handler request)]
      (is (= 200 (:status response)))
      (is (string? (:body response)))
      (let [body (json/read-json (:body response) :key-fn keyword)]
        (is (true? (:success body)))
        (let [data (:data body)]
          (is (= 0 (:institutions data)))
          (is (= 0 (:accounts data)))
          (is (= 0 (:transactions data)))))))

  (testing "returns accurate stats after adding data"
    ;; Add some test data
    (d/transact! *test-conn*
                 [{:institution/id "inst-1"}
                  {:institution/id "inst-2"}
                  {:account/external-id "acc-1"}
                  {:account/external-id "acc-2"}
                  {:account/external-id "acc-3"}
                  {:transaction/external-id "tx-1"}
                  {:transaction/external-id "tx-2"}
                  {:transaction/external-id "tx-3"}
                  {:transaction/external-id "tx-4"}])

    (let [handler (stats/stats-handler {:db-conn *test-conn*})
          request {}
          response (handler request)]
      (is (= 200 (:status response)))
      (let [body (json/read-json (:body response) :key-fn keyword)
            data (:data body)]
        (is (= 2 (:institutions data)))
        (is (= 3 (:accounts data)))
        (is (= 4 (:transactions data))))))

  (testing "handler is a factory function"
    (let [handler (stats/stats-handler {:db-conn *test-conn*})]
      (is (fn? handler))
      ;; Should be callable multiple times
      (let [r1 (handler {})
            r2 (handler {})]
        (is (= 200 (:status r1)))
        (is (= 200 (:status r2)))))))

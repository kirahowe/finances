(ns finance-aggregator.http.server-test
  "Tests for HTTP server component."
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.http.server :as server]))

(deftest start-server-test
  (testing "starts HTTP server on specified port with handler"
    (let [test-handler (fn [_] {:status 200 :body "OK"})
          test-port 9191
          server-component (server/start-server! test-port test-handler)]

      (is (some? server-component) "Server component created")
      (is (contains? server-component :server) "Has :server key")
      (is (fn? (:stop-fn server-component)) "Has :stop-fn")
      (is (= test-port (:port server-component)) "Port is correct")

      ;; Stop server
      ((:stop-fn server-component)))))

(deftest stop-server-test
  (testing "stops HTTP server cleanly"
    (let [test-handler (fn [_] {:status 200 :body "OK"})
          test-port 9192
          server-component (server/start-server! test-port test-handler)
          stop-fn (:stop-fn server-component)]

      (is (some? server-component) "Server created")

      ;; Stop the server - should not throw
      (is (nil? (stop-fn)) "Stop function returns nil"))))

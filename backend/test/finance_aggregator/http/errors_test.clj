(ns finance-aggregator.http.errors-test
  "Tests for HTTP error handling and exception middleware"
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.http.errors :as errors]))

(deftest error-response-test
  (testing "error-response creates proper error response map"
    (let [response (errors/error-response 400 "Bad request")]
      (is (= 400 (:status response)))
      (is (= {"Content-Type" "application/json"} (:headers response)))
      (is (string? (:body response)))
      (is (re-find #"Bad request" (:body response)))))

  (testing "error-response with additional data"
    (let [response (errors/error-response 404 "Not found" {:id 123})]
      (is (= 404 (:status response)))
      (is (re-find #"Not found" (:body response)))
      (is (re-find #"123" (:body response))))))

(deftest exception-to-response-test
  (testing "converts generic Exception to 500 error"
    (let [ex (Exception. "Something went wrong")
          response (errors/exception->response ex)]
      (is (= 500 (:status response)))
      (is (re-find #"Something went wrong" (:body response)))))

  (testing "converts ex-info with :type to appropriate status"
    (let [ex (ex-info "Not found" {:type :not-found})
          response (errors/exception->response ex)]
      (is (= 404 (:status response)))
      (is (re-find #"Not found" (:body response)))))

  (testing "converts ex-info with :type :bad-request"
    (let [ex (ex-info "Invalid input" {:type :bad-request})
          response (errors/exception->response ex)]
      (is (= 400 (:status response)))))

  (testing "converts ex-info with :type :conflict"
    (let [ex (ex-info "Already exists" {:type :conflict})
          response (errors/exception->response ex)]
      (is (= 409 (:status response)))))

  (testing "includes ex-data in response"
    (let [ex (ex-info "Validation failed" {:type :bad-request :fields [:name :email]})
          response (errors/exception->response ex)]
      (is (= 400 (:status response)))
      (is (re-find #"fields" (:body response))))))

(deftest wrap-exception-handling-test
  (testing "passes through successful responses"
    (let [handler (fn [_] {:status 200 :body "OK"})
          wrapped (errors/wrap-exception-handling handler)
          response (wrapped {})]
      (is (= 200 (:status response)))
      (is (= "OK" (:body response)))))

  (testing "catches exceptions and converts to error response"
    (let [handler (fn [_] (throw (Exception. "Error occurred")))
          wrapped (errors/wrap-exception-handling handler)
          response (wrapped {})]
      (is (= 500 (:status response)))
      (is (string? (:body response)))
      (is (re-find #"Error occurred" (:body response)))))

  (testing "catches ex-info with type and converts appropriately"
    (let [handler (fn [_] (throw (ex-info "Not found" {:type :not-found})))
          wrapped (errors/wrap-exception-handling handler)
          response (wrapped {})]
      (is (= 404 (:status response)))
      (is (re-find #"Not found" (:body response))))))

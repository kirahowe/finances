(ns finance-aggregator.http.middleware-test
  "Tests for HTTP middleware"
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.http.middleware :as middleware]
   [charred.api :as json]
   [clojure.java.io :as io]))

(def test-cors-config
  "Default CORS config for tests"
  {:allowed-origins ["*"]
   :allowed-methods ["GET" "POST" "PUT" "DELETE" "OPTIONS"]
   :allowed-headers ["*"]
   :max-age 3600})

(deftest wrap-cors-test
  (testing "adds CORS headers to response"
    (let [handler (fn [_] {:status 200 :body "OK"})
          wrapped (middleware/wrap-cors handler test-cors-config)
          response (wrapped {})]
      (is (= 200 (:status response)))
      (is (= "*" (get-in response [:headers "Access-Control-Allow-Origin"])))
      (is (= "GET, POST, PUT, DELETE, OPTIONS"
             (get-in response [:headers "Access-Control-Allow-Methods"])))
      (is (= "*"
             (get-in response [:headers "Access-Control-Allow-Headers"])))))

  (testing "handles OPTIONS preflight requests"
    (let [handler (fn [_] {:status 200 :body "OK"})
          wrapped (middleware/wrap-cors handler test-cors-config)
          request {:request-method :options :uri "/api/test"}
          response (wrapped request)]
      (is (= 200 (:status response)))
      (is (= "*" (get-in response [:headers "Access-Control-Allow-Origin"])))))

  (testing "preserves existing headers"
    (let [handler (fn [_] {:status 200
                           :headers {"X-Custom" "value"}
                           :body "OK"})
          wrapped (middleware/wrap-cors handler test-cors-config)
          response (wrapped {})]
      (is (= "value" (get-in response [:headers "X-Custom"])))
      (is (= "*" (get-in response [:headers "Access-Control-Allow-Origin"])))))

  (testing "uses custom CORS config"
    (let [handler (fn [_] {:status 200 :body "OK"})
          custom-config {:allowed-origins ["https://example.com"]
                         :allowed-methods ["GET" "POST"]
                         :allowed-headers ["Content-Type"]
                         :max-age 7200}
          wrapped (middleware/wrap-cors handler custom-config)
          response (wrapped {})]
      (is (= "https://example.com" (get-in response [:headers "Access-Control-Allow-Origin"])))
      (is (= "GET, POST" (get-in response [:headers "Access-Control-Allow-Methods"])))
      (is (= "Content-Type" (get-in response [:headers "Access-Control-Allow-Headers"])))
      (is (= "7200" (get-in response [:headers "Access-Control-Max-Age"]))))))

(deftest wrap-json-request-test
  (testing "parses JSON request body"
    (let [handler (fn [req] {:status 200 :body (pr-str (:body-params req))})
          wrapped (middleware/wrap-json-request handler)
          body-str (json/write-json-str {:name "Alice" :age 30})
          request {:body (io/input-stream (.getBytes body-str))
                   :headers {"content-type" "application/json"}}
          response (wrapped request)]
      (is (= 200 (:status response)))
      (is (string? (:body response)))
      (let [body-params (read-string (:body response))]
        (is (= "Alice" (:name body-params)))
        (is (= 30 (:age body-params))))))

  (testing "passes through non-JSON requests unchanged"
    (let [handler (fn [req] {:status 200 :body (str "has body-params: " (contains? req :body-params))})
          wrapped (middleware/wrap-json-request handler)
          request {:body "plain text"
                   :headers {"content-type" "text/plain"}}
          response (wrapped request)]
      (is (= 200 (:status response)))))

  (testing "handles missing body"
    (let [handler (fn [req] {:status 200 :body "OK"})
          wrapped (middleware/wrap-json-request handler)
          request {:headers {"content-type" "application/json"}}
          response (wrapped request)]
      (is (= 200 (:status response))))))

(deftest wrap-json-response-test
  (testing "serializes map body to JSON"
    (let [handler (fn [_] {:status 200 :body {:result "success" :count 42}})
          wrapped (middleware/wrap-json-response handler)
          response (wrapped {})]
      (is (= 200 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))
      (is (string? (:body response)))
      (let [parsed (json/read-json (:body response) :key-fn keyword)]
        (is (= "success" (:result parsed)))
        (is (= 42 (:count parsed))))))

  (testing "preserves string body"
    (let [handler (fn [_] {:status 200 :body "plain text"})
          wrapped (middleware/wrap-json-response handler)
          response (wrapped {})]
      (is (= 200 (:status response)))
      (is (= "plain text" (:body response)))))

  (testing "preserves nil body"
    (let [handler (fn [_] {:status 204})
          wrapped (middleware/wrap-json-response handler)
          response (wrapped {})]
      (is (= 204 (:status response)))
      (is (nil? (:body response))))))

(deftest wrap-json-test
  (testing "combines request and response JSON handling"
    (let [handler (fn [req] {:status 200 :body {:received (:body-params req)}})
          wrapped (middleware/wrap-json handler)
          body-str (json/write-json-str {:test "data"})
          request {:body (io/input-stream (.getBytes body-str))
                   :headers {"content-type" "application/json"}}
          response (wrapped request)]
      (is (= 200 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))
      (let [parsed (json/read-json (:body response) :key-fn keyword)]
        (is (= "data" (get-in parsed [:received :test])))))))

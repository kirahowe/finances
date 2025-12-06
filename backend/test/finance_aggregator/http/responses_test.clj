(ns finance-aggregator.http.responses-test
  "Tests for HTTP response helper functions"
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.http.responses :as responses]
   [charred.api :as json]))

(deftest json-response-test
  (testing "creates basic JSON response with data"
    (let [data {:foo "bar" :baz 123}
          response (responses/json-response data)]
      (is (= 200 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))
      (is (string? (:body response)))
      (let [parsed (json/read-json (:body response) :key-fn keyword)]
        (is (= data parsed)))))

  (testing "creates JSON response with custom status"
    (let [response (responses/json-response {:error "not found"} 404)]
      (is (= 404 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))))

  (testing "handles nil data"
    (let [response (responses/json-response nil)]
      (is (= 200 (:status response)))
      (is (string? (:body response))))))

(deftest success-response-test
  (testing "wraps data in success envelope"
    (let [data {:users [{:id 1 :name "Alice"}]}
          response (responses/success-response data)]
      (is (= 200 (:status response)))
      (let [parsed (json/read-json (:body response) :key-fn keyword)]
        (is (= true (:success parsed)))
        (is (= data (:data parsed))))))

  (testing "success-response with custom status"
    (let [response (responses/success-response {:created true} 201)]
      (is (= 201 (:status response)))
      (let [parsed (json/read-json (:body response) :key-fn keyword)]
        (is (= true (:success parsed)))))))

(deftest no-content-response-test
  (testing "creates 204 No Content response"
    (let [response (responses/no-content-response)]
      (is (= 204 (:status response)))
      (is (nil? (:body response))))))

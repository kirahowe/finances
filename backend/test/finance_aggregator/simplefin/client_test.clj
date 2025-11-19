(ns finance-aggregator.simplefin.client-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [finance-aggregator.simplefin.client :as client])
  (:import [java.util Base64]))

;; ============================================================================
;; Test Data
;; ============================================================================

(def demo-setup-token
  "Demo token from SimpleFin documentation"
  "aHR0cHM6Ly9icmlkZ2Uuc2ltcGxlZmluLm9yZy9zaW1wbGVmaW4vY2xhaW0vZGVtbw==")

(def sample-access-url
  "https://test-user:test-pass@bridge.simplefin.org/simplefin")

(def sample-accounts-response
  {:accounts
   [{:id "account-1"
     :name "Test Checking"
     :currency "CAD"
     :balance "1234.56"
     :balance-date 1698796800
     :transactions
     [{:id "tx-1"
       :posted 1698796800
       :amount "100.00"
       :description "Test Transaction"}]}]})

;; ============================================================================
;; Unit Tests
;; ============================================================================

(deftest test-decode-setup-token
  (testing "Decodes valid base64-encoded setup token"
    (is (= "https://bridge.simplefin.org/simplefin/claim/demo"
           (#'client/decode-setup-token demo-setup-token))))

  (testing "Decodes custom base64 token"
    (let [custom-url "https://example.com/claim/abc123"
          encoded (.encodeToString (Base64/getEncoder) (.getBytes custom-url "UTF-8"))]
      (is (= custom-url (#'client/decode-setup-token encoded)))))

  (testing "Throws on invalid base64"
    (is (thrown? clojure.lang.ExceptionInfo
                 (#'client/decode-setup-token "not-valid-base64!!!@@@"))))

  (testing "Empty token decodes to empty string"
    (is (= "" (#'client/decode-setup-token "")))))

(deftest test-parse-access-url
  (testing "Parses valid SimpleFin access URL"
    (let [result (#'client/parse-access-url sample-access-url)]
      (is (= "test-user" (:username result)))
      (is (= "test-pass" (:password result)))
      (is (= "bridge.simplefin.org" (:host result)))
      (is (= "/simplefin" (:path result)))
      (is (= "https://bridge.simplefin.org/simplefin" (:base-url result)))))

  (testing "Parses URL with special characters in password (except @ and :)"
    (let [url "https://user:p!#$%word@bridge.simplefin.org/simplefin"
          result (#'client/parse-access-url url)]
      (is (= "user" (:username result)))
      (is (= "p!#$%word" (:password result)))))

  (testing "Parses URL with subdomain and versioned path"
    (let [url "https://user:pass@api.bridge.simplefin.org/v1/simplefin"
          result (#'client/parse-access-url url)]
      (is (= "api.bridge.simplefin.org" (:host result)))
      (is (= "/v1/simplefin" (:path result)))
      (is (= "https://api.bridge.simplefin.org/v1/simplefin" (:base-url result)))))

  (testing "Returns nil for invalid URLs"
    (is (nil? (#'client/parse-access-url "not-a-url")))
    (is (nil? (#'client/parse-access-url "http://no-credentials@host.com/path")))
    (is (nil? (#'client/parse-access-url "https://only-user@host.com/path")))
    (is (nil? (#'client/parse-access-url nil)))
    (is (nil? (#'client/parse-access-url "")))))

(deftest test-build-fetch-request
  (testing "Builds request for valid access URL"
    (let [result (client/build-fetch-request sample-access-url 2024 10)]
      (is (some? result))
      (is (= "https://bridge.simplefin.org/simplefin/accounts" (:url result)))
      (is (= ["test-user" "test-pass"] (get-in result [:options :basic-auth])))
      (is (= :json (get-in result [:options :accept])))
      (is (= :json (get-in result [:options :as])))
      (is (contains? (get-in result [:options :query-params]) :start-date))
      (is (contains? (get-in result [:options :query-params]) :end-date))))

  (testing "Returns nil for invalid access URL"
    (is (nil? (client/build-fetch-request "invalid-url" 2024 10)))
    (is (nil? (client/build-fetch-request nil 2024 10)))))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest test-claim-setup-token*
  (testing "Returns access URL on successful claim (200 status)"
    (let [mock-http-post (fn [_url]
                           {:status 200
                            :body "https://user:pass@bridge.simplefin.org/simplefin"})
          result (client/claim-setup-token* mock-http-post demo-setup-token)]
      (is (= "https://user:pass@bridge.simplefin.org/simplefin" result))))

  (testing "Returns nil on non-200 status"
    (let [mock-http-post (fn [_url] {:status 404 :body "Not found"})
          result (client/claim-setup-token* mock-http-post demo-setup-token)]
      (is (nil? result))))

  (testing "Calls HTTP POST with decoded URL"
    (let [captured-url (atom nil)
          mock-http-post (fn [url]
                           (reset! captured-url url)
                           {:status 200 :body "https://user:pass@host.com/path"})]
      (client/claim-setup-token* mock-http-post demo-setup-token)
      (is (= "https://bridge.simplefin.org/simplefin/claim/demo" @captured-url))))

  (testing "Throws on HTTP error with context"
    (let [mock-http-post (fn [_url] (throw (Exception. "Network error")))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Failed to claim setup token"
           (client/claim-setup-token* mock-http-post demo-setup-token)))))

  (testing "Exception includes claim URL in ex-data"
    (let [mock-http-post (fn [_url] (throw (Exception. "Network error")))]
      (try
        (client/claim-setup-token* mock-http-post demo-setup-token)
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= "https://bridge.simplefin.org/simplefin/claim/demo"
                 (:claim-url (ex-data e)))))))))

(deftest test-fetch-transactions*
  (testing "Returns accounts on successful fetch (200 status)"
    (let [mock-http-get (fn [_url _options]
                          {:status 200
                           :body sample-accounts-response})
          result (client/fetch-transactions* mock-http-get sample-access-url 2024 10)]
      (is (= sample-accounts-response result))
      (is (contains? result :accounts))
      (is (= 1 (count (:accounts result))))))

  (testing "Calls HTTP GET with correct URL and auth"
    (let [captured-args (atom {})
          mock-http-get (fn [url options]
                          (reset! captured-args {:url url :options options})
                          {:status 200 :body sample-accounts-response})]
      (client/fetch-transactions* mock-http-get sample-access-url 2024 10)
      (is (= "https://bridge.simplefin.org/simplefin/accounts"
             (:url @captured-args)))
      (is (= ["test-user" "test-pass"]
             (get-in @captured-args [:options :basic-auth])))
      (is (= :json (get-in @captured-args [:options :accept])))
      (is (= :json (get-in @captured-args [:options :as])))))

  (testing "Passes query params to HTTP client"
    (let [captured-args (atom {})
          mock-http-get (fn [url options]
                          (reset! captured-args {:url url :options options})
                          {:status 200 :body sample-accounts-response})]
      (client/fetch-transactions* mock-http-get sample-access-url 2024 10)
      (let [params (get-in @captured-args [:options :query-params])]
        (is (contains? params :start-date))
        (is (contains? params :end-date)))))

  (testing "Returns nil on non-200 status"
    (let [mock-http-get (fn [_url _options] {:status 401 :body "Unauthorized"})]
      (is (nil? (client/fetch-transactions* mock-http-get sample-access-url 2024 10)))))

  (testing "Returns error map on exception"
    (let [mock-http-get (fn [_url _options] (throw (Exception. "Network timeout")))
          result (client/fetch-transactions* mock-http-get sample-access-url 2024 10)]
      (is (contains? result :error))
      (is (string? (:error result)))
      (is (re-find #"Network timeout" (:error result)))))

  (testing "Returns nil for invalid access URL"
    (let [mock-http-get (fn [_url _options] {:status 200 :body sample-accounts-response})]
      (is (nil? (client/fetch-transactions* mock-http-get "invalid-url" 2024 10)))
      (is (nil? (client/fetch-transactions* mock-http-get nil 2024 10)))))

  (testing "Empty response body returns nil"
    (let [mock-http-get (fn [_url _options] {:status 200 :body nil})]
      (is (nil? (client/fetch-transactions* mock-http-get sample-access-url 2024 10)))))

  (testing "Multiple accounts in response"
    (let [multi-account-response {:accounts
                                  [{:id "acc-1" :name "Account 1"}
                                   {:id "acc-2" :name "Account 2"}
                                   {:id "acc-3" :name "Account 3"}]}
          mock-http-get (fn [_url _options] {:status 200 :body multi-account-response})
          result (client/fetch-transactions* mock-http-get sample-access-url 2024 10)]
      (is (= 3 (count (:accounts result)))))))

;; ============================================================================
;; Property-Based Tests
;; ============================================================================

(defspec decode-then-encode-roundtrip 50
  (prop/for-all [s (gen/not-empty gen/string-ascii)]
    (let [encoded (.encodeToString (Base64/getEncoder) (.getBytes s "UTF-8"))
          decoded (#'client/decode-setup-token encoded)]
      (= s decoded))))

(defspec parse-access-url-extracts-components 50
  (prop/for-all [username (gen/not-empty gen/string-alphanumeric)
                 password (gen/not-empty gen/string-alphanumeric)
                 host (gen/fmap #(str % ".example.com")
                                (gen/not-empty gen/string-alphanumeric))
                 path (gen/fmap #(str "/" %)
                                (gen/not-empty gen/string-alphanumeric))]
    (let [url (str "https://" username ":" password "@" host path)
          result (#'client/parse-access-url url)]
      (and (= username (:username result))
           (= password (:password result))
           (= host (:host result))
           (= path (:path result))
           (= (str "https://" host path) (:base-url result))))))

(defspec invalid-urls-return-nil 50
  (prop/for-all [invalid-url (gen/one-of [(gen/return nil)
                                           (gen/return "")
                                           gen/string-alphanumeric
                                           (gen/return "not a url")])]
    (nil? (#'client/parse-access-url invalid-url))))

(defspec build-fetch-request-structure-valid 30
  (prop/for-all [year (gen/choose 2020 2030)
                 month (gen/choose 1 12)]
    (let [result (client/build-fetch-request sample-access-url year month)]
      (and (some? result)
           (string? (:url result))
           (map? (:options result))
           (vector? (get-in result [:options :basic-auth]))
           (= 2 (count (get-in result [:options :basic-auth])))
           (= :json (get-in result [:options :accept]))
           (= :json (get-in result [:options :as]))))))

;; ============================================================================
;; Regression Tests
;; ============================================================================

(deftest test-error-cases
  (testing "Base URL construction preserves path structure"
    (let [url "https://user:pass@api.example.com/v2/simplefin"
          result (#'client/parse-access-url url)]
      (is (= "https://api.example.com/v2/simplefin" (:base-url result)))))

  (testing "HTTP exceptions are caught and converted to error maps"
    (let [mock-http-get (fn [_url _options]
                          (throw (ex-info "HTTP 500" {:status 500})))
          result (client/fetch-transactions* mock-http-get sample-access-url 2024 10)]
      (is (map? result))
      (is (contains? result :error))
      (is (not (contains? result :accounts)))))

  (testing "Claim token exception preserves error context"
    (let [mock-http-post (fn [_url] (throw (Exception. "Connection refused")))]
      (try
        (client/claim-setup-token* mock-http-post demo-setup-token)
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (string? (:error (ex-data e))))
          (is (string? (:claim-url (ex-data e))))
          (is (.contains (:claim-url (ex-data e)) "bridge.simplefin.org")))))))

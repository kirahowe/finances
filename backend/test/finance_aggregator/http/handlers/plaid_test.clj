(ns finance-aggregator.http.handlers.plaid-test
  "Tests for Plaid integration handlers"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [finance-aggregator.http.handlers.plaid :as plaid-handlers]
   [finance-aggregator.lib.encryption :as encryption]
   [charred.api :as json]
   [datalevin.core :as d]))

(def ^:dynamic *test-conn* nil)
(def ^:dynamic *test-secrets* nil)
(def ^:dynamic *test-plaid-config* nil)

(defn test-db-fixture [f]
  "Create a test database with schema for each test"
  (let [test-dir (str "/tmp/test-plaid-handlers-" (System/currentTimeMillis))
        conn (d/get-conn test-dir)
        ;; Generate a test encryption key
        encryption-key (encryption/generate-encryption-key)
        secrets {:database {:encryption-key encryption-key}}
        plaid-config {:client-id "test-client-id"
                      :secret "test-secret"
                      :environment :sandbox}]
    (try
      ;; Define schema
      (d/transact! conn
                   [{:db/ident :user/id
                     :db/valueType :db.type/string
                     :db/unique :db.unique/identity}
                    {:db/ident :user/created-at
                     :db/valueType :db.type/instant}
                    {:db/ident :credential/id
                     :db/valueType :db.type/string
                     :db/unique :db.unique/identity}
                    {:db/ident :credential/user
                     :db/valueType :db.type/ref}
                    {:db/ident :credential/institution
                     :db/valueType :db.type/keyword}
                    {:db/ident :credential/encrypted-data
                     :db/valueType :db.type/string}
                    {:db/ident :credential/created-at
                     :db/valueType :db.type/instant}
                    {:db/ident :credential/last-used
                     :db/valueType :db.type/instant}])

      (binding [*test-conn* conn
                *test-secrets* secrets
                *test-plaid-config* plaid-config]
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

;;
;; create-link-token-handler tests
;;

(deftest create-link-token-handler-test
  (testing "handler is a factory function"
    (let [handler (plaid-handlers/create-link-token-handler {:plaid-config *test-plaid-config*})]
      (is (fn? handler) "Handler is a function")))

  (testing "handler structure without calling Plaid API"
    ;; We verify the handler exists and is callable
    ;; Actual Plaid API calls would require valid credentials
    (let [handler (plaid-handlers/create-link-token-handler {:plaid-config *test-plaid-config*})]
      (is (fn? handler)))))

;;
;; exchange-token-handler tests
;;

(deftest exchange-token-handler-missing-token-test
  (testing "returns 400 when publicToken is missing"
    (let [handler (plaid-handlers/exchange-token-handler
                   {:db-conn *test-conn*
                    :secrets *test-secrets*
                    :plaid-config *test-plaid-config*})
          request {:body-params {}}]
      (try
        (handler request)
        (is false "Should have thrown exception")
        (catch clojure.lang.ExceptionInfo e
          (is (= "publicToken is required" (.getMessage e)))
          (is (= :bad-request (-> e ex-data :type))))))))

(deftest exchange-token-handler-structure-test
  (testing "handler is a factory function"
    (let [handler (plaid-handlers/exchange-token-handler
                   {:db-conn *test-conn*
                    :secrets *test-secrets*
                    :plaid-config *test-plaid-config*})]
      (is (fn? handler)))))

;;
;; get-accounts-handler tests
;;

(deftest get-accounts-handler-no-credential-test
  (testing "returns 404 when no credential exists"
    (let [handler (plaid-handlers/get-accounts-handler
                   {:db-conn *test-conn*
                    :secrets *test-secrets*
                    :plaid-config *test-plaid-config*})
          request {}]
      (try
        (handler request)
        (is false "Should have thrown exception")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"No Plaid credential found" (.getMessage e)))
          (is (= :not-found (-> e ex-data :type))))))))

;;
;; get-transactions-handler tests
;;

(deftest get-transactions-handler-validation-test
  (testing "returns 404 when no credential exists"
    (let [handler (plaid-handlers/get-transactions-handler
                   {:db-conn *test-conn*
                    :secrets *test-secrets*
                    :plaid-config *test-plaid-config*})
          request {:body-params {:startDate "2025-01-01"
                                :endDate "2025-12-31"}}]
      (try
        (handler request)
        (is false "Should have thrown exception")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"No Plaid credential found" (.getMessage e)))
          (is (= :not-found (-> e ex-data :type))))))))

;;
;; Integration test: full flow
;;

(deftest plaid-handler-integration-test
  (testing "handlers work together correctly"
    ;; This test verifies the handler structure without calling Plaid API
    (let [create-link (plaid-handlers/create-link-token-handler
                       {:plaid-config *test-plaid-config*})
          exchange (plaid-handlers/exchange-token-handler
                    {:db-conn *test-conn*
                     :secrets *test-secrets*
                     :plaid-config *test-plaid-config*})
          get-accounts (plaid-handlers/get-accounts-handler
                        {:db-conn *test-conn*
                         :secrets *test-secrets*
                         :plaid-config *test-plaid-config*})
          get-transactions (plaid-handlers/get-transactions-handler
                            {:db-conn *test-conn*
                             :secrets *test-secrets*
                             :plaid-config *test-plaid-config*})]

      (is (fn? create-link))
      (is (fn? exchange))
      (is (fn? get-accounts))
      (is (fn? get-transactions))

      ;; Verify error handling works
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"publicToken is required"
                            (exchange {:body-params {}})))

      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No Plaid credential found"
                            (get-accounts {})))

      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No Plaid credential found"
                            (get-transactions {:body-params {:startDate "2025-01-01"
                                                            :endDate "2025-12-31"}}))))))

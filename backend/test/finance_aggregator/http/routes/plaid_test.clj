(ns finance-aggregator.http.routes.plaid-test
  "Tests for Plaid routes definition"
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.http.routes.plaid :as plaid-routes]))

(deftest plaid-routes-structure-test
  (testing "plaid-routes returns valid reitit route data"
    (let [deps {:db-conn :mock-conn
                :secrets {:test "secret"}
                :plaid-config {:environment :sandbox}}
          routes (plaid-routes/plaid-routes deps)]

      (is (vector? routes) "Routes should be a vector")
      (is (= "/plaid" (first routes)) "Routes should be prefixed with /plaid")

      ;; Check that all expected routes are defined
      (let [route-map (->> routes
                          rest
                          (map first)
                          set)]
        (is (contains? route-map "/create-link-token")
            "Should have create-link-token route")
        (is (contains? route-map "/exchange-token")
            "Should have exchange-token route")
        (is (contains? route-map "/accounts")
            "Should have accounts route")
        (is (contains? route-map "/transactions")
            "Should have transactions route")))))

(deftest plaid-routes-methods-test
  (testing "routes have correct HTTP methods"
    (let [deps {:db-conn :mock-conn
                :secrets {:test "secret"}
                :plaid-config {:environment :sandbox}}
          routes (plaid-routes/plaid-routes deps)
          route-specs (rest routes)]

      ;; Check create-link-token
      (let [[path spec] (first route-specs)]
        (is (= "/create-link-token" path))
        (is (contains? spec :post))
        (is (fn? (get-in spec [:post :handler]))))

      ;; Check exchange-token
      (let [[path spec] (second route-specs)]
        (is (= "/exchange-token" path))
        (is (contains? spec :post))
        (is (fn? (get-in spec [:post :handler]))))

      ;; Check accounts
      (let [[path spec] (nth route-specs 2)]
        (is (= "/accounts" path))
        (is (contains? spec :get))
        (is (fn? (get-in spec [:get :handler]))))

      ;; Check transactions
      (let [[path spec] (nth route-specs 3)]
        (is (= "/transactions" path))
        (is (contains? spec :post))
        (is (fn? (get-in spec [:post :handler])))))))

(deftest plaid-routes-handlers-test
  (testing "handlers are functions created with dependencies"
    (let [deps {:db-conn :mock-conn
                :secrets {:test "secret"}
                :plaid-config {:environment :sandbox}}
          routes (plaid-routes/plaid-routes deps)
          route-specs (rest routes)]

      (doseq [[_path spec] route-specs
              [_method route-data] spec]
        (let [handler (:handler route-data)]
          (is (fn? handler) "Handler should be a function"))))))

(deftest plaid-routes-route-names-test
  (testing "routes have named route identifiers"
    (let [deps {:db-conn :mock-conn
                :secrets {:test "secret"}
                :plaid-config {:environment :sandbox}}
          routes (plaid-routes/plaid-routes deps)
          route-specs (rest routes)]

      ;; Check that routes have names
      (let [[_path spec] (first route-specs)]
        (is (= ::plaid-routes/create-link-token (get-in spec [:post :name]))))

      (let [[_path spec] (second route-specs)]
        (is (= ::plaid-routes/exchange-token (get-in spec [:post :name]))))

      (let [[_path spec] (nth route-specs 2)]
        (is (= ::plaid-routes/get-accounts (get-in spec [:get :name]))))

      (let [[_path spec] (nth route-specs 3)]
        (is (= ::plaid-routes/get-transactions (get-in spec [:post :name])))))))

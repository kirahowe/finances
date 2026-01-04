(ns finance-aggregator.http.routes.plaid-test
  "Tests for Plaid routes definition"
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.http.routes.plaid :as plaid-routes]))

(defn- find-route
  "Find a route by path in the route specs"
  [route-specs path]
  (first (filter #(= path (first %)) route-specs)))

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
        (is (contains? route-map "/items")
            "Should have items route")
        (is (contains? route-map "/items/:item-id")
            "Should have items/:item-id route")
        (is (contains? route-map "/credential")
            "Should have credential route")
        (is (contains? route-map "/accounts")
            "Should have accounts route")
        (is (contains? route-map "/transactions")
            "Should have transactions route")
        (is (contains? route-map "/sync-accounts")
            "Should have sync-accounts route")
        (is (contains? route-map "/sync-transactions")
            "Should have sync-transactions route")))))

(deftest plaid-routes-methods-test
  (testing "routes have correct HTTP methods"
    (let [deps {:db-conn :mock-conn
                :secrets {:test "secret"}
                :plaid-config {:environment :sandbox}}
          routes (plaid-routes/plaid-routes deps)
          route-specs (rest routes)]

      ;; Check create-link-token - POST
      (let [[path spec] (find-route route-specs "/create-link-token")]
        (is (= "/create-link-token" path))
        (is (contains? spec :post))
        (is (fn? (get-in spec [:post :handler]))))

      ;; Check exchange-token - POST
      (let [[path spec] (find-route route-specs "/exchange-token")]
        (is (= "/exchange-token" path))
        (is (contains? spec :post))
        (is (fn? (get-in spec [:post :handler]))))

      ;; Check items - GET
      (let [[path spec] (find-route route-specs "/items")]
        (is (= "/items" path))
        (is (contains? spec :get))
        (is (fn? (get-in spec [:get :handler]))))

      ;; Check items/:item-id - DELETE
      (let [[path spec] (find-route route-specs "/items/:item-id")]
        (is (= "/items/:item-id" path))
        (is (contains? spec :delete))
        (is (fn? (get-in spec [:delete :handler]))))

      ;; Check credential - DELETE
      (let [[path spec] (find-route route-specs "/credential")]
        (is (= "/credential" path))
        (is (contains? spec :delete))
        (is (fn? (get-in spec [:delete :handler]))))

      ;; Check accounts - GET
      (let [[path spec] (find-route route-specs "/accounts")]
        (is (= "/accounts" path))
        (is (contains? spec :get))
        (is (fn? (get-in spec [:get :handler]))))

      ;; Check transactions - POST
      (let [[path spec] (find-route route-specs "/transactions")]
        (is (= "/transactions" path))
        (is (contains? spec :post))
        (is (fn? (get-in spec [:post :handler]))))

      ;; Check sync-accounts - POST
      (let [[path spec] (find-route route-specs "/sync-accounts")]
        (is (= "/sync-accounts" path))
        (is (contains? spec :post))
        (is (fn? (get-in spec [:post :handler]))))

      ;; Check sync-transactions - POST
      (let [[path spec] (find-route route-specs "/sync-transactions")]
        (is (= "/sync-transactions" path))
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

      ;; Check route names
      (let [[_path spec] (find-route route-specs "/create-link-token")]
        (is (= ::plaid-routes/create-link-token (get-in spec [:post :name]))))

      (let [[_path spec] (find-route route-specs "/exchange-token")]
        (is (= ::plaid-routes/exchange-token (get-in spec [:post :name]))))

      (let [[_path spec] (find-route route-specs "/items")]
        (is (= ::plaid-routes/list-items (get-in spec [:get :name]))))

      (let [[_path spec] (find-route route-specs "/items/:item-id")]
        (is (= ::plaid-routes/delete-item (get-in spec [:delete :name]))))

      (let [[_path spec] (find-route route-specs "/credential")]
        (is (= ::plaid-routes/delete-credential (get-in spec [:delete :name]))))

      (let [[_path spec] (find-route route-specs "/accounts")]
        (is (= ::plaid-routes/get-accounts (get-in spec [:get :name]))))

      (let [[_path spec] (find-route route-specs "/transactions")]
        (is (= ::plaid-routes/get-transactions (get-in spec [:post :name]))))

      (let [[_path spec] (find-route route-specs "/sync-accounts")]
        (is (= ::plaid-routes/sync-accounts (get-in spec [:post :name]))))

      (let [[_path spec] (find-route route-specs "/sync-transactions")]
        (is (= ::plaid-routes/sync-transactions (get-in spec [:post :name])))))))

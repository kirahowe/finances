(ns finance-aggregator.sys-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.sys :as sys]
   [integrant.core :as ig]))

(deftest load-configs-test
  (testing "loads and merges EDN config files using default config files"
    ;; Uses default-config-files which loads base-system.edn + config.edn
    ;; In test context, config.edn resolves to env/test/resources/config.edn
    (let [config (sys/load-configs sys/default-config-files)]
      (is (map? config) "Returns a map")
      (is (contains? config :finance-aggregator.system/db-path))
      (is (contains? config :finance-aggregator.system/http-port))
      (is (contains? config :finance-aggregator.db/connection))
      (is (contains? config :finance-aggregator.http/server))))

  (testing "test config overrides base config"
    (let [config (sys/load-configs sys/default-config-files)]
      ;; Test config uses ./data/test.db
      (is (= "./data/test.db" (:finance-aggregator.system/db-path config))
          "Test database path overrides base"))))

(deftest prep-config-test
  (testing "prepares config for Integrant"
    (let [raw-config (sys/load-configs sys/default-config-files)
          prepped (sys/prep-config raw-config)]
      (is (map? prepped) "Returns a map")
      ;; Integrant refs should be resolved
      (is (ig/ref? (get-in prepped [:finance-aggregator.db/connection :db-path]))))))

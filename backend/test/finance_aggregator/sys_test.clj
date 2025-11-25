(ns finance-aggregator.sys-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.sys :as sys]
   [integrant.core :as ig]))

(deftest load-configs-test
  (testing "loads and merges EDN config files"
    (let [config (sys/load-configs ["system/base-system.edn" "system/dev.edn"])]
      (is (map? config) "Returns a map")
      (is (contains? config :finance-aggregator.system/db-path))
      (is (contains? config :finance-aggregator.system/http-port))
      (is (contains? config :finance-aggregator.db/connection))
      (is (contains? config :finance-aggregator.http/server))))

  (testing "dev config overrides base config"
    (let [config (sys/load-configs ["system/base-system.edn" "system/dev.edn"])]
      (is (= "./data/dev.db" (:finance-aggregator.system/db-path config))
          "Dev database path overrides base"))))

(deftest prep-config-test
  (testing "prepares config for Integrant"
    (let [raw-config (sys/load-configs ["system/base-system.edn" "system/dev.edn"])
          prepped (sys/prep-config raw-config)]
      (is (map? prepped) "Returns a map")
      ;; Integrant refs should be resolved
      (is (ig/ref? (get-in prepped [:finance-aggregator.db/connection :db-path]))))))

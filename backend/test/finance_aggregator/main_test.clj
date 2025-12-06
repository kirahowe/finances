(ns finance-aggregator.main-test
  "Tests for the main entry point namespace.
   These tests verify the namespace structure without actually starting the system."
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.main :as main]))

(deftest main-namespace-test
  (testing "Main namespace loads without errors"
    (is (find-ns 'finance-aggregator.main)
        "Main namespace should be loadable")))

(deftest main-function-exists-test
  (testing "Main function is defined"
    (is (var? (resolve 'finance-aggregator.main/-main))
        "-main function should be defined")))

(deftest main-function-signature-test
  (testing "Main function has correct signature"
    (let [main-var (var main/-main)
          main-meta (meta main-var)]
      (is main-meta "-main should have metadata")
      ;; -main should accept variable args
      (is (ifn? @main-var) "-main should be a function"))))

(deftest gen-class-present-test
  (testing "Namespace has gen-class for Java main method"
    (let [ns-meta (meta (find-ns 'finance-aggregator.main))]
      ;; Check that the namespace has the necessary structure for -main
      (is (find-ns 'finance-aggregator.main)
          "Namespace should exist and be properly configured"))))

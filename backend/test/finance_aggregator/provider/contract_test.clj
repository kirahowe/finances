(ns finance-aggregator.provider.contract-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.provider.contract :as contract]))

(def ^:private imported
  {:transaction/external-id "tx-1"
   :transaction/account [:account/external-id "acc-1"]
   :transaction/amount (bigdec "10.00")
   :transaction/description "Coffee"
   :transaction/payee "Cafe"})

(deftest passes-clean-imported-transactions
  (testing "Provenance/imported fields are allowed and returned unchanged"
    (is (= [imported] (contract/assert-no-overlay-keys! [imported])))
    (is (= [] (contract/assert-no-overlay-keys! [])))))

(deftest throws-on-each-overlay-key
  (testing "Any user-overlay key in a provider transaction is rejected"
    (doseq [k contract/overlay-keys]
      (is (thrown? clojure.lang.ExceptionInfo
                   (contract/assert-no-overlay-keys! [(assoc imported k "x")]))
          (str "should reject " k)))))

(deftest error-names-the-offending-key
  (try
    (contract/assert-no-overlay-keys! [(assoc imported :transaction/reviewed true)])
    (is false "expected throw")
    (catch clojure.lang.ExceptionInfo e
      (is (= [:transaction/reviewed] (:overlay-keys (ex-data e))))
      (is (= "tx-1" (:transaction/external-id (ex-data e)))))))

(deftest rejects-split-parent-key
  (testing "A provider transaction carrying :transaction/split-parent is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (contract/assert-no-overlay-keys!
                  [(assoc imported :transaction/split-parent [:transaction/external-id "tx-0"])])))))

(deftest rejects-split-order-key
  (testing "A provider transaction carrying :transaction/split-order is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (contract/assert-no-overlay-keys!
                  [(assoc imported :transaction/split-order 1)])))))

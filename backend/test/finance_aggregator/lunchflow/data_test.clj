(ns finance-aggregator.lunchflow.data-test
  "Fixture-based tests for the pure Lunchflow -> canonical transforms."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.lunchflow.data :as data])
  (:import
   [java.util Date]))

(def sample-account
  {:id 1
   :name "Everyday Chequing"
   :institution_name "Tangerine Bank"
   :institution_logo nil
   :provider "gocardless"
   :currency "CAD"
   :status "ACTIVE"})

(def sample-transaction
  {:id 99
   :accountId 1
   :amount -12.34
   :currency "CAD"
   :date "2026-06-05"
   :merchant "Loblaws"
   :description "weekly groceries"
   :isPending false})

(def pending-transaction
  {:id 100 :accountId 1 :amount -5 :date "2026-06-04"
   :merchant "Tim Hortons" :isPending true})

(def idless-transaction
  {:id nil :accountId 1 :amount -7.00 :date "2026-06-03"
   :merchant "ATM Withdrawal" :description "cash" :isPending false})

(deftest account-external-id-namespaces-the-id
  (is (= "lunchflow-1" (data/account-external-id sample-account)))
  (is (= "lunchflow-99" (data/account-external-id {:id 99}))))

(deftest parse-institution-synthesizes-from-name
  (let [result (data/parse-institution sample-account)]
    (is (= "lunchflow-tangerine-bank" (:institution/id result)))
    (is (= "Tangerine Bank" (:institution/name result))))
  (testing "logo is included when present, omitted when nil"
    (is (= "https://cdn.example/logo.png"
           (:institution/logo
            (data/parse-institution (assoc sample-account
                                           :institution_logo "https://cdn.example/logo.png")))))
    (is (not (contains? (data/parse-institution sample-account) :institution/logo))
        "sample-account has institution_logo nil, so the key is omitted")))

(deftest parse-account-maps-canonical-fields
  (let [result (data/parse-account sample-account "test-user")]
    (is (= "lunchflow-1" (:account/external-id result)))
    (is (= "Everyday Chequing" (:account/external-name result)))
    (is (= "CAD" (:account/currency result)))
    (is (= :lunchflow (:account/provider result)))
    (testing "the upstream connector becomes provider-type"
      (is (= "gocardless" (:account/provider-type result))))
    (is (= [:institution/id "lunchflow-tangerine-bank"] (:account/institution result)))
    (is (= [:user/id "test-user"] (:account/user result)))))

(deftest parse-account-defaults-currency-to-cad
  (is (= "CAD" (:account/currency
                (data/parse-account (dissoc sample-account :currency) "test-user")))))

(deftest parse-transaction-maps-canonical-fields
  (let [result (data/parse-transaction sample-transaction "test-user")]
    (is (= "lunchflow-99" (:transaction/external-id result)))
    (is (= [:account/external-id "lunchflow-1"] (:transaction/account result)))
    (is (= [:user/id "test-user"] (:transaction/user result)))
    (is (= "Loblaws" (:transaction/payee result)))
    (is (= "weekly groceries" (:transaction/description result)))
    (is (= :lunchflow (:transaction/provider result)))
    (testing "date and posted-date are the same Date"
      (is (instance? Date (:transaction/date result)))
      (is (= (:transaction/date result) (:transaction/posted-date result))))
    (testing "outflow stays negative (canonical sign, no flip)"
      (is (= (bigdec "-12.34") (:transaction/amount result))))))

(deftest parse-transaction-filters-pending
  (is (nil? (data/parse-transaction pending-transaction "test-user"))))

(deftest parse-transaction-hash-fallback-when-id-missing
  (let [r1 (data/parse-transaction idless-transaction "test-user")
        r2 (data/parse-transaction idless-transaction "test-user")]
    (testing "external-id is present and namespaced"
      (is (str/starts-with? (:transaction/external-id r1) "lunchflow-")))
    (testing "fallback is deterministic for identical input"
      (is (= (:transaction/external-id r1) (:transaction/external-id r2))))
    (testing "fallback does not collapse to the literal nil id"
      (is (not= "lunchflow-" (:transaction/external-id r1)))
      (is (not= "lunchflow-null" (:transaction/external-id r1))))))

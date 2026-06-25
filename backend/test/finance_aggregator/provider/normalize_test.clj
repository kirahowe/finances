(ns finance-aggregator.provider.normalize-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.provider.normalize :as normalize]))

(defn- txn [ext-id amount]
  {:transaction/external-id (str "t-" ext-id "-" amount)
   :transaction/account [:account/external-id ext-id]
   :transaction/amount (bigdec amount)})

(deftest empty-inverted-set-passes-through
  (let [txns [(txn "a" 10) (txn "b" -5)]]
    (is (= txns (normalize/normalize-amounts txns #{})))
    (is (= txns (normalize/normalize-amounts txns nil)))))

(deftest flips-only-inverted-accounts
  (testing "Only transactions of inverted accounts have their sign flipped"
    (let [txns [(txn "a" 10) (txn "b" -5) (txn "a" -7)]
          out (normalize/normalize-amounts txns #{"a"})]
      (is (= [(bigdec -10) (bigdec -5) (bigdec 7)]
             (map :transaction/amount out))))))

(deftest non-lookup-ref-account-untouched
  (testing "A transaction whose account ref isn't a canonical lookup ref is left alone"
    (let [t {:transaction/account 12345 :transaction/amount (bigdec 9)}]
      (is (= [(bigdec 9)]
             (map :transaction/amount (normalize/normalize-amounts [t] #{"a"})))))))

(deftest txn-without-amount-untouched
  (let [t {:transaction/account [:account/external-id "a"]}]
    (is (= [t] (normalize/normalize-amounts [t] #{"a"})))))

(deftest provider-agnostic-shapes
  (testing "Plaid / Lunchflow / manual-shaped maps normalize uniformly"
    (let [plaid  {:transaction/account [:account/external-id "plaid-1"] :transaction/amount (bigdec -20)}
          lunch  {:transaction/account [:account/external-id "lunchflow-2"] :transaction/amount (bigdec 30)}
          manual {:transaction/account [:account/external-id "manual-3"] :transaction/amount (bigdec -1)}
          out (normalize/normalize-amounts [plaid lunch manual] #{"plaid-1" "manual-3"})]
      (is (= [(bigdec 20) (bigdec 30) (bigdec 1)]
             (map :transaction/amount out))))))

(deftest returns-a-vector
  (is (vector? (normalize/normalize-amounts (list (txn "a" 1)) #{})))
  (is (vector? (normalize/normalize-amounts (list (txn "a" 1)) #{"a"}))))

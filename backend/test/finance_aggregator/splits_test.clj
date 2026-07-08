(ns finance-aggregator.splits-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.splits :as splits]))

(defn- part [amount category-id]
  {:amount amount :category-id category-id})

(deftest validate-splits-test
  (testing "empty splits are valid (clear / un-split)"
    (is (nil? (splits/validate-splits -100.00M []))))

  (testing "a single part is rejected"
    (is (= "A split must have at least 2 parts"
           (splits/validate-splits -100.00M [(part "-100.00" 1)]))))

  (testing "two parts summing exactly to the parent are valid"
    (is (nil? (splits/validate-splits -100.00M [(part "-60.00" 1) (part "-40.00" 2)]))))

  (testing "reconciliation is scale-insensitive (== not =)"
    (is (nil? (splits/validate-splits -100M [(part "-60.00" 1) (part "-40.00" 2)]))))

  (testing "off by a cent is rejected"
    (is (= "Splits must add up to the transaction amount"
           (splits/validate-splits -100.00M [(part "-60.00" 1) (part "-39.99" 2)]))))

  (testing "a non-numeric amount returns an error, not an exception"
    (is (= "Every split amount must be a valid number"
           (splits/validate-splits -100.00M [(part "abc" 1) (part "-40.00" 2)]))))

  (testing "a zero amount is rejected"
    (is (= "Split amounts must be non-zero"
           (splits/validate-splits -100.00M [(part "0.00" 1) (part "-100.00" 2)]))))

  (testing "a missing category is allowed (the Uncategorized chip owns those parts)"
    (is (nil? (splits/validate-splits -100.00M [(part "-60.00" nil) (part "-40.00" 2)]))))

  (testing "mixed-sign parts that sum to the parent are valid"
    (is (nil? (splits/validate-splits -100.00M [(part "-120.00" 1) (part "20.00" 2)]))))

  (testing "fractional cents reconcile exactly with bigdec (would fail with doubles)"
    (is (nil? (splits/validate-splits -0.30M [(part "-0.10" 1) (part "-0.20" 2)])))))

(deftest reconciled?-test
  (testing "exact sum reconciles regardless of scale"
    (is (true? (splits/reconciled? -100M [-60.00M -40.00M])))
    (is (true? (splits/reconciled? -0.30M [-0.10M -0.20M]))))

  (testing "a non-reconciling sum is false"
    (is (false? (splits/reconciled? -100.00M [-60.00M -39.99M])))))

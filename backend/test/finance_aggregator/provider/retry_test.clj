(ns finance-aggregator.provider.retry-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.provider.retry :as retry])
  (:import
   [java.util Date]))

(def ^:private p retry/default-policy)

(deftest backoff-schedule-without-jitter
  (testing "Capped exponential: 1,2,4,8,15,15,15,15 minutes (cap at 15m)"
    (let [none (assoc p :jitter :none)
          mins (fn [attempt] (long (/ (retry/next-delay-ms none attempt) 60000)))]
      (is (= [1 2 4 8 15 15 15 15] (mapv mins (range 8)))))))

(deftest equal-jitter-stays-in-upper-half
  (testing "Equal jitter: delay in [raw/2, raw]; rand=0 -> raw/2, rand=0.5 -> 3raw/4"
    (let [raw 60000] ; attempt 0 base delay
      (is (= 30000 (retry/next-delay-ms p 0 (constantly 0.0))))
      (is (= 45000 (retry/next-delay-ms p 0 (constantly 0.5))))
      ;; never below half, never above raw
      (doseq [r [0.0 0.25 0.5 0.75 0.99]]
        (let [d (retry/next-delay-ms p 0 (constantly r))]
          (is (<= (/ raw 2) d raw)))))))

(deftest full-jitter-spans-zero-to-raw
  (let [full (assoc p :jitter :full)]
    (is (= 0 (retry/next-delay-ms full 0 (constantly 0.0))))
    (is (= 30000 (retry/next-delay-ms full 0 (constantly 0.5))))))

(deftest next-retry-at-adds-delay-to-now
  (let [now (Date. 1700000000000)
        none (assoc p :jitter :none)
        at (retry/next-retry-at none 0 now (constantly 0.0))]
    (is (= (+ 1700000000000 60000) (.getTime at)))))

(deftest stale-retry-at-uses-the-slow-cadence
  (testing "Once the budget is spent, the next attempt is stale-retry-ms out"
    (let [now (Date. 1700000000000)
          at (retry/stale-retry-at p now)]
      (is (= (+ 1700000000000 (:stale-retry-ms p)) (.getTime at))))))

(deftest exhausted-by-attempts-or-time
  (testing "Bounded by retry count"
    (is (false? (retry/exhausted? p {:retry-count 7})))
    (is (true? (retry/exhausted? p {:retry-count 8})))
    (is (true? (retry/exhausted? p {:retry-count 99}))))
  (testing "Bounded by elapsed wall-clock (whichever first)"
    (is (false? (retry/exhausted? p {:retry-count 1 :elapsed-ms 1000})))
    (is (true? (retry/exhausted? p {:retry-count 1 :elapsed-ms 7200001}))))
  (testing "Missing fields default safely"
    (is (false? (retry/exhausted? p {})))))

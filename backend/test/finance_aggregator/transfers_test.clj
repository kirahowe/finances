(ns finance-aggregator.transfers-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.transfers :as transfers]))

(defn- tx
  "Build a normalized transaction map for the matcher, with sensible defaults."
  [id amount day account-id & {:as overrides}]
  (merge {:id id :amount amount :day day :account-id account-id
          :real? false :paired? false :rejected #{}}
         overrides))

(defn- pairs->set [pairs]
  (set (map (juxt :outflow-id :inflow-id) pairs)))

(deftest real-activity?-test
  (testing "expense and income are real activity (never auto-suggested)"
    (is (true? (transfers/real-activity? :expense)))
    (is (true? (transfers/real-activity? :income))))
  (testing "transfer and uncategorized are not real activity"
    (is (false? (transfers/real-activity? :transfer)))
    (is (false? (transfers/real-activity? nil)))))

(deftest suggest-matches-test
  (testing "an inverse-amount pair across accounts within the window is suggested"
    (let [out (tx 1 -100.00M 10 :a)
          in  (tx 2 100.00M 11 :b)
          result (transfers/suggest-matches [out in] {})]
      (is (= #{[1 2]} (pairs->set result)))))

  (testing "match is scale-insensitive (== not =)"
    (let [out (tx 1 -100M 10 :a)
          in  (tx 2 100.00M 10 :b)]
      (is (= #{[1 2]} (pairs->set (transfers/suggest-matches [out in] {}))))))

  (testing "same sign does not pair"
    (is (empty? (transfers/suggest-matches [(tx 1 -100M 10 :a) (tx 2 -100M 10 :b)] {}))))

  (testing "different magnitude does not pair"
    (is (empty? (transfers/suggest-matches [(tx 1 -100M 10 :a) (tx 2 99M 10 :b)] {}))))

  (testing "same account does not pair"
    (is (empty? (transfers/suggest-matches [(tx 1 -100M 10 :a) (tx 2 100M 10 :a)] {}))))

  (testing "outside the date window does not pair (default 3 days)"
    (is (empty? (transfers/suggest-matches [(tx 1 -100M 10 :a) (tx 2 100M 14 :b)] {}))))

  (testing "on the date-window boundary pairs"
    (is (= #{[1 2]} (pairs->set (transfers/suggest-matches [(tx 1 -100M 10 :a) (tx 2 100M 13 :b)] {})))))

  (testing "custom window widens the range"
    (is (= #{[1 2]} (pairs->set (transfers/suggest-matches
                                 [(tx 1 -100M 10 :a) (tx 2 100M 18 :b)] {:window-days 10})))))

  (testing "already-paired legs are skipped"
    (is (empty? (transfers/suggest-matches
                 [(tx 1 -100M 10 :a :paired? true) (tx 2 100M 10 :b)] {}))))

  (testing "real expense/income legs are not auto-suggested"
    (is (empty? (transfers/suggest-matches
                 [(tx 1 -100M 10 :a :real? true) (tx 2 100M 10 :b)] {})))
    (is (empty? (transfers/suggest-matches
                 [(tx 1 -100M 10 :a) (tx 2 100M 10 :b :real? true)] {}))))

  (testing "a previously rejected pair is not re-suggested (either direction)"
    (is (empty? (transfers/suggest-matches
                 [(tx 1 -100M 10 :a :rejected #{2}) (tx 2 100M 10 :b)] {})))
    (is (empty? (transfers/suggest-matches
                 [(tx 1 -100M 10 :a) (tx 2 100M 10 :b :rejected #{1})] {}))))

  (testing "greedy: the closest-date inflow wins, each transaction used once"
    (let [out (tx 1 -100M 10 :a)
          near (tx 2 100M 11 :b)   ; 1 day away
          far  (tx 3 100M 13 :c)   ; 3 days away
          result (transfers/suggest-matches [out far near] {})]
      (is (= #{[1 2]} (pairs->set result)))))

  (testing "two independent pairs are both matched"
    (let [result (transfers/suggest-matches
                  [(tx 1 -100M 10 :a) (tx 2 100M 10 :b)
                   (tx 3 -50M 20 :a) (tx 4 50M 20 :c)] {})]
      (is (= #{[1 2] [3 4]} (pairs->set result)))))

  (testing "one outflow cannot satisfy two inflows"
    (let [result (transfers/suggest-matches
                  [(tx 1 -100M 10 :a) (tx 2 100M 10 :b) (tx 3 100M 10 :c)] {})]
      (is (= 1 (count result)))))

  (testing "a leg with a missing day is not auto-matched but does not crash"
    (is (empty? (transfers/suggest-matches
                 [(tx 1 -100M nil :a) (tx 2 100M 10 :b)] {}))))

  (testing "a leg with a missing amount is skipped, not crashed on"
    (is (empty? (transfers/suggest-matches
                 [(tx 1 nil 10 :a) (tx 2 100M 10 :b)] {})))))

(deftest opposite-amounts?-test
  (testing "equal magnitude, opposite sign (scale-insensitive)"
    (is (true? (transfers/opposite-amounts? -100M 100.00M)))
    (is (true? (transfers/opposite-amounts? 100.00M -100M))))
  (testing "same sign / different magnitude / zero / nil are not opposite"
    (is (false? (transfers/opposite-amounts? -100M -100M)))
    (is (false? (transfers/opposite-amounts? -100M 99M)))
    (is (false? (transfers/opposite-amounts? 0M 0M)))
    (is (false? (transfers/opposite-amounts? nil 100M)))
    (is (false? (transfers/opposite-amounts? -100M nil)))))

(deftest hidden-transfer?-test
  (testing "a matched pair with no real-activity leg is hidden"
    (is (true? (transfers/hidden-transfer? true :transfer nil))))
  (testing "an unmatched transaction is never hidden"
    (is (false? (transfers/hidden-transfer? false nil nil))))
  (testing "a matched pair stays visible when either leg is real expense/income"
    (is (false? (transfers/hidden-transfer? true :expense nil)))
    (is (false? (transfers/hidden-transfer? true nil :income)))))

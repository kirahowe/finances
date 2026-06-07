(ns finance-aggregator.provider-test
  "Guards the provider seam registries. The available-accounts endpoint must only
   accept providers that implement available-accounts; routing a sync-only
   provider (Plaid) there would otherwise blow up with a multimethod dispatch
   error instead of a clean 400."
  (:require
   [clojure.test :refer [deftest is testing]]
   [finance-aggregator.provider :as provider]
   ;; Side-effecting requires register the provider defmethods.
   [finance-aggregator.lunchflow.provider]
   [finance-aggregator.plaid.provider]))

(deftest selectable-is-a-subset-of-registered
  (testing "Lunchflow both syncs and is selectable"
    (is (contains? (provider/registered-providers) :lunchflow))
    (is (contains? (provider/selectable-providers) :lunchflow)))

  (testing "Plaid syncs but is NOT selectable (no available-accounts method)"
    (is (contains? (provider/registered-providers) :plaid))
    (is (not (contains? (provider/selectable-providers) :plaid))))

  (testing "every selectable provider is also registered"
    (is (every? (provider/registered-providers) (provider/selectable-providers)))))

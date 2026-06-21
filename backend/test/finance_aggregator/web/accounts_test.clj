(ns finance-aggregator.web.accounts-test
  (:require
   [clojure.test :refer [deftest is]]
   [finance-aggregator.web.accounts :as accounts]))

(deftest provider-label
  (is (= "Plaid" (accounts/provider-label :plaid)))
  (is (= "Manual" (accounts/provider-label :manual)))
  (is (= "Unknown" (accounts/provider-label nil))))

(deftest display-type
  (is (= "depository / checking"
         (accounts/display-type {:account/provider-type "depository" :account/provider-subtype "checking"}))
      "provider type + subtype")
  (is (= "depository" (accounts/display-type {:account/provider-type "depository"}))
      "provider type, no subtype")
  (is (= "manual" (accounts/display-type {:account/type :manual}))
      "falls back to the internal type")
  (is (= "—" (accounts/display-type {})) "nothing → dash"))

(deftest sort-accounts
  (is (= ["A" "B" "C"]
         (map :account/external-name
              (accounts/sort-accounts [{:account/external-name "C"}
                                       {:account/external-name "A"}
                                       {:account/external-name "B"}])))))

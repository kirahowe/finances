(ns finance-aggregator.db.accounts-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datalevin.core :as d]
   [finance-aggregator.db.accounts :as accounts]
   [finance-aggregator.test-utils.setup :as setup]))

(use-fixtures :each setup/with-empty-db)

(defn- put-account! [ext-id invert?]
  (d/transact! setup/*test-conn*
               [(cond-> {:account/external-id ext-id
                         :account/provider :plaid}
                  (some? invert?) (assoc :account/invert-amount invert?))]))

(deftest inverted-account-ids-returns-only-inverted
  (testing "Only accounts with :account/invert-amount true are returned"
    (put-account! "a" true)
    (put-account! "b" false)
    (put-account! "c" nil)
    (put-account! "d" true)
    (is (= #{"a" "d"} (accounts/inverted-account-ids setup/*test-conn*)))))

(deftest inverted-account-ids-empty-when-none
  (put-account! "a" nil)
  (put-account! "b" false)
  (is (= #{} (accounts/inverted-account-ids setup/*test-conn*))))

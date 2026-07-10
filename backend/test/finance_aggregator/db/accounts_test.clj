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

(defn- display-name-of [ext-id]
  (:account/display-name (d/pull (d/db setup/*test-conn*) '[:account/display-name]
                                 [:account/external-id ext-id])))

(deftest set-display-name-sets-trims-and-clears
  (put-account! "a" nil)
  (testing "sets a trimmed override"
    (accounts/set-display-name! setup/*test-conn* "a" "  My Chequing  ")
    (is (= "My Chequing" (display-name-of "a"))))
  (testing "re-setting overwrites the prior override"
    (accounts/set-display-name! setup/*test-conn* "a" "Everyday Chequing")
    (is (= "Everyday Chequing" (display-name-of "a"))))
  (testing "blank input retracts the override"
    (accounts/set-display-name! setup/*test-conn* "a" "  ")
    (is (nil? (display-name-of "a"))))
  (testing "nil input retracts the override"
    (accounts/set-display-name! setup/*test-conn* "a" "Temp Name")
    (accounts/set-display-name! setup/*test-conn* "a" nil)
    (is (nil? (display-name-of "a")))))

(deftest set-display-name-no-op-for-unknown-external-id
  (testing "an external-id that doesn't resolve to an account is a silent no-op"
    (is (nil? (accounts/set-display-name! setup/*test-conn* "does-not-exist" "X")))))

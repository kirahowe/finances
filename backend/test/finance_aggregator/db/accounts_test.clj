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

(deftest set-display-name-provider-name-is-a-clear
  ;; The inline rename cell edits the SHOWN label, so an untouched open-then-blur commits
  ;; the provider name verbatim — that must never become a stored override (it would drag
  ;; the muted provider-name caption out under an unchanged label).
  (d/transact! setup/*test-conn* [{:account/external-id "e" :account/provider :plaid
                                   :account/external-name "Chequing"}])
  (testing "committing the provider's own name never sets an override"
    (accounts/set-display-name! setup/*test-conn* "e" "Chequing")
    (is (nil? (display-name-of "e"))))
  (testing "committing it over an existing override clears that override"
    (accounts/set-display-name! setup/*test-conn* "e" "Daily Driver")
    (is (= "Daily Driver" (display-name-of "e")))
    (accounts/set-display-name! setup/*test-conn* "e" "  Chequing  ")
    (is (nil? (display-name-of "e")))))

(deftest set-display-name-no-op-for-unknown-external-id
  (testing "an external-id that doesn't resolve to an account is a silent no-op"
    (is (nil? (accounts/set-display-name! setup/*test-conn* "does-not-exist" "X")))))

(deftest by-external-id-fetches-one-account-or-nil
  (put-account! "a" nil)
  (testing "a known external-id pulls that account"
    (is (= "a" (:account/external-id (accounts/by-external-id setup/*test-conn* "a")))))
  (testing "an unknown external-id is nil, not a throw"
    (is (nil? (accounts/by-external-id setup/*test-conn* "does-not-exist")))))

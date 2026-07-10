(ns finance-aggregator.web.accounts-test
  (:require
   [clojure.test :refer [deftest is testing]]
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

(deftest account-label
  (testing "the rename overlay wins over the provider name"
    (is (= "My Chequing"
           (accounts/account-label {:account/external-name "Chequing" :account/display-name "My Chequing"}))))
  (testing "falls back to the provider name when there's no override"
    (is (= "Chequing" (accounts/account-label {:account/external-name "Chequing"}))))
  (testing "a blank override doesn't win over a real provider name"
    (is (= "Chequing" (accounts/account-label {:account/external-name "Chequing" :account/display-name "  "}))))
  (testing "nothing at all -> dash"
    (is (= "—" (accounts/account-label {})))))

(deftest sort-accounts
  (testing "sorts by external name when there's no override"
    (is (= ["A" "B" "C"]
           (map :account/external-name
                (accounts/sort-accounts [{:account/external-name "C"}
                                         {:account/external-name "A"}
                                         {:account/external-name "B"}])))))
  (testing "sorts by the SHOWN label, not the provider name — a rename can reorder"
    (is (= ["Aardvark's Chequing" "Zebra"]
           (map accounts/account-label
                (accounts/sort-accounts
                 [{:account/external-name "Zebra"}
                  {:account/external-name "Chequing" :account/display-name "Aardvark's Chequing"}]))))))

(deftest connection-status-presentation
  (is (= {:label "Synced" :tone "ok"} (accounts/connection-status :synced)))
  (is (= {:label "Needs reconnect" :tone "error"} (accounts/connection-status :needs-reconnect)))
  (is (= {:label "Retrying" :tone "warn"} (accounts/connection-status :stale)))
  (is (= "muted" (:tone (accounts/connection-status :something-new)))
      "unknown status -> muted tone")
  (is (= "Something-new" (:label (accounts/connection-status :something-new)))))

(def ^:private fixed-now (java.util.Date. 1700000000000))

(defn- acct
  ([name conn-id] (acct name conn-id {}))
  ([name conn-id {:keys [provider display-name external-id]}]
   (cond-> {:account/external-name name :account/provider (or provider :plaid)
            :account/external-id (or external-id name)}
     conn-id (assoc :account/connection {:connection/id conn-id})
     display-name (assoc :account/display-name display-name))))

(deftest connection-groups-buckets-accounts-by-connection
  (let [connections [{:connection/id "plaid:b" :connection/provider :plaid
                      :connection/institution-name "Bravo" :connection/status :synced
                      :connection/last-success-at (java.util.Date. (- 1700000000000 (* 3 60 1000)))}
                     {:connection/id "plaid:a" :connection/provider :plaid
                      :connection/institution-name "Alpha" :connection/status :pending}]
        accounts [(acct "Acct-A1" "plaid:a")
                  (acct "Acct-B1" "plaid:b")
                  (acct "Acct-B2" "plaid:b")
                  (acct "Orphan" "plaid:gone")     ; dangling ref -> unlinked
                  (acct "Legacy" nil)]             ; no ref yet -> unlinked
        {:keys [groups unlinked]} (accounts/connection-groups connections accounts fixed-now)]
    (testing "groups ordered by institution name"
      (is (= ["Alpha" "Bravo"] (map :institution-name groups))))
    (testing "each group carries its accounts, status pill and last-synced"
      (let [bravo (second groups)]
        (is (= ["Acct-B1" "Acct-B2"] (map :name (:accounts bravo))))
        (is (= {:label "Synced" :tone "ok"} (:status bravo)))
        (is (= "3 min ago" (:last-synced bravo)))
        (is (= "Plaid" (:badge-label bravo)))
        (is (= "badge-plaid" (:badge-class bravo)))))
    (testing "a pending connection with no accounts still renders, last-synced 'never'"
      (let [alpha (first groups)]
        (is (= ["Acct-A1"] (map :name (:accounts alpha))))
        (is (= "never" (:last-synced alpha)))))
    (testing "accounts with no/dangling connection ref are returned as unlinked"
      (is (= ["Legacy" "Orphan"] (map :name unlinked))))))

(deftest connection-groups-account-rows-carry-rename-and-sync-fields
  (let [connections [{:connection/id "lunchflow" :connection/provider :lunchflow
                      :connection/institution-name "Lunchflow" :connection/status :syncing}
                     {:connection/id "plaid:a" :connection/provider :plaid
                      :connection/institution-name "Alpha" :connection/status :synced}]
        accounts [(acct "Chequing" "lunchflow" {:provider :lunchflow :display-name "My Chequing"
                                                :external-id "lunchflow-1"})
                  (acct "Visa" "plaid:a" {:provider :plaid :external-id "acct-visa"})]
        {:keys [groups]} (accounts/connection-groups connections accounts fixed-now)
        lunchflow-row (first (:accounts (first (filter #(= "lunchflow" (:id %)) groups))))
        plaid-row (first (:accounts (first (filter #(= "plaid:a" (:id %)) groups))))]
    (testing "a Lunchflow account row carries the rename fields + a sync url, and
              reflects its connection's :syncing status"
      (is (= "My Chequing" (:name lunchflow-row)))
      (is (= "My Chequing" (:display-name lunchflow-row)))
      (is (= "Chequing" (:external-name lunchflow-row)))
      (is (= "lunchflow-1" (:external-id lunchflow-row)))
      (is (true? (:lunchflow? lunchflow-row)))
      (is (= "/setup/account/lunchflow-1/name" (:name-url lunchflow-row)))
      (is (= "/setup/sync-account?external-id=lunchflow-1" (:sync-url lunchflow-row)))
      (is (true? (:syncing? lunchflow-row))))
    (testing "a Plaid account row gets no sync affordance and no override yet"
      (is (= "" (:display-name plaid-row)))
      (is (false? (:lunchflow? plaid-row)))
      (is (false? (:syncing? plaid-row)) "its OWN connection (plaid:a) isn't :syncing"))))

(deftest present-bundles-stats-and-groups
  (let [model (accounts/present {:stats {:accounts 1} :connections [] :accounts [] :now fixed-now})]
    (is (= {:accounts 1} (:stats model)))
    (is (= [] (:groups model)))
    (is (= [] (:unlinked model)))))

(deftest provider-selection-groups-and-marks-connected
  (let [available [{:external-id "lunchflow-1" :name "Chequing" :institution-name "Tangerine"}
                   {:external-id "lunchflow-2" :name "Savings" :institution-name "Tangerine"
                    :institution-logo "logo.png"}
                   {:external-id "lunchflow-3" :name "Visa" :institution-name "EQ Bank"}]
        groups (accounts/provider-selection available #{"lunchflow-1"})]
    (testing "grouped + ordered by institution name"
      (is (= ["EQ Bank" "Tangerine"] (map :institution-name groups))))
    (testing "accounts ordered by name, connected flag set, logo picked up"
      (let [tangerine (second groups)]
        (is (= "logo.png" (:institution-logo tangerine)))
        (is (= [{:external-id "lunchflow-1" :name "Chequing" :connected? true}
                {:external-id "lunchflow-2" :name "Savings" :connected? false}]
               (:accounts tangerine)))))))

(ns finance-aggregator.db.statements-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datalevin.core :as d]
   [finance-aggregator.db.statements :as statements]
   [finance-aggregator.test-utils.setup :as setup])
  (:import
   [java.util Date]))

(use-fixtures :each setup/with-empty-db)

(defn- date [y m d]
  (-> (java.time.LocalDate/of y m d)
      (.atStartOfDay java.time.ZoneOffset/UTC) .toInstant Date/from))

(defn- account-eid [ext-id]
  (d/q '[:find ?a . :in $ ?ext :where [?a :account/external-id ?ext]]
       (d/db setup/*test-conn*) ext-id))

(defn- put-account! [ext-id nm]
  (d/transact! setup/*test-conn* [{:account/external-id ext-id :account/provider :plaid
                                   :account/external-name nm}]))

(deftest create-read-update-delete
  (put-account! "visa" "Visa")
  (let [acct (account-eid "visa")
        id (statements/create! setup/*test-conn*
                               {:account-eid acct :start-date (date 2026 4 16) :start-balance "500.00"
                                :end-date (date 2026 5 16) :end-balance "640.00"})]
    (testing "create returns the entity id and stores the fields (+ account name)"
      (is (int? id))
      (let [s (statements/by-id setup/*test-conn* id)]
        (is (= (date 2026 4 16) (:start-date s)))
        (is (= (bigdec "500.00") (:start-balance s)))
        (is (= (date 2026 5 16) (:end-date s)))
        (is (= (bigdec "640.00") (:end-balance s)))
        (is (= "Visa" (:account-name s)))
        (is (= acct (:account-eid s)))))
    (testing "update changes only the supplied fields"
      (statements/update! setup/*test-conn* id {:end-balance "660.00"})
      (is (= (bigdec "660.00") (:end-balance (statements/by-id setup/*test-conn* id))))
      (is (= (date 2026 4 16) (:start-date (statements/by-id setup/*test-conn* id))) "untouched"))
    (testing "delete retracts it; by-id then nil"
      (statements/delete! setup/*test-conn* id)
      (is (nil? (statements/by-id setup/*test-conn* id))))))

(deftest list-overlapping-scopes-to-the-span
  (testing "overlap is start < to AND end > from — a statement counts for every month it spans"
    (put-account! "visa" "Visa")
    (let [acct (account-eid "visa")]
      (statements/create! setup/*test-conn*
                          {:account-eid acct :start-date (date 2026 4 16) :start-balance "0"
                           :end-date (date 2026 5 16) :end-balance "0"})   ; Apr 16 → May 16
      (statements/create! setup/*test-conn*
                          {:account-eid acct :start-date (date 2026 5 16) :start-balance "0"
                           :end-date (date 2026 6 16) :end-balance "0"})   ; May 16 → Jun 16
      (testing "both statements overlap May (May 1 – Jun 1), earliest start first"
        (is (= [(date 2026 4 16) (date 2026 5 16)]
               (map :start-date (statements/list-overlapping setup/*test-conn* acct (date 2026 5 1) (date 2026 6 1))))))
      (testing "only the straddling one overlaps June"
        (is (= [(date 2026 5 16)]
               (map :start-date (statements/list-overlapping setup/*test-conn* acct (date 2026 6 1) (date 2026 7 1))))))
      (testing "neither overlaps a far month"
        (is (= [] (statements/list-overlapping setup/*test-conn* acct (date 2026 9 1) (date 2026 10 1))))))))

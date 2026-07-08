(ns finance-aggregator.db.migrations-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [finance-aggregator.db.core :as db-core]
            [finance-aggregator.db.migrations :as migrations]
            [finance-aggregator.test-utils.setup :as setup]))

(use-fixtures :each setup/with-empty-db)

(def ^:private posted (java.util.Date. 1700000000000))

(defn- make-parent!
  "Create a user/institution/account/transaction and return the transaction's :db/id.
   `tx-attrs` is merged over the base transaction map."
  [external-id tx-attrs]
  (d/transact! setup/*test-conn* [{:user/id "test-user"}
                                  {:institution/id (str "inst-" external-id)
                                   :institution/name "Test Bank"}
                                  {:account/external-id (str "acct-" external-id)
                                   :account/external-name "Test Account"}])
  (d/transact! setup/*test-conn*
               [(merge {:transaction/external-id external-id
                        :transaction/account [:account/external-id (str "acct-" external-id)]
                        :transaction/user [:user/id "test-user"]
                        :transaction/amount -100.00M
                        :transaction/payee "Costco"
                        :transaction/date posted
                        :transaction/posted-date posted}
                       tx-attrs)])
  (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:transaction/external-id external-id])))

(defn- make-category! [name ident]
  (d/transact! setup/*test-conn* [{:category/name name :category/type :expense :category/ident ident}])
  (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:category/ident ident])))

(defn- old-split-eids [tx-id]
  (d/q '[:find [?s ...] :in $ ?tx :where [?tx :transaction/splits ?s]]
       (d/db setup/*test-conn*) tx-id))

(defn- parts [tx-id]
  (->> (d/q '[:find [(pull ?p [* {:transaction/category [:db/id]}
                               {:transaction/account [:db/id]}
                               {:transaction/user [:db/id]}
                               {:transaction/split-parent [:db/id]}]) ...]
              :in $ ?tx :where [?p :transaction/split-parent ?tx]]
            (d/db setup/*test-conn*) tx-id)
       (sort-by :transaction/split-order)))

(deftest migrate-splits-field-copy-test
  (testing "each old sub-entity becomes a part transaction inheriting the parent's identity fields"
    (let [groceries (make-category! "Groceries" :category/groceries)
          tx-id (make-parent! "mig-1" {})
          acct (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:account/external-id "acct-mig-1"]))
          user (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:user/id "test-user"]))]
      (d/transact! setup/*test-conn*
                   [{:db/id tx-id
                     :transaction/splits [{:split/amount -60.00M :split/category groceries
                                           :split/order 0 :split/memo "food" :split/reviewed true}
                                          {:split/amount -40.00M :split/order 1}]}])
      (migrations/migrate-splits! setup/*test-conn*)
      (let [[p1 p2] (parts tx-id)]
        (is (= 2 (count (parts tx-id))))
        ;; Generated identity + provenance
        (is (str/starts-with? (:transaction/external-id p1) "split-"))
        (is (= :split (:transaction/provider p1)))
        (is (= tx-id (get-in p1 [:transaction/split-parent :db/id])))
        (is (= [0 1] (map :transaction/split-order [p1 p2])))
        ;; Copied from the parent
        (is (= acct (get-in p1 [:transaction/account :db/id])))
        (is (= user (get-in p1 [:transaction/user :db/id])))
        (is (= posted (:transaction/date p1)))
        (is (= posted (:transaction/posted-date p1)))
        (is (= "Costco" (:transaction/payee p1)))
        ;; Carried from the sub-entity
        (is (== -60.00M (:transaction/amount p1)))
        (is (= "food" (:transaction/description p1)))
        (is (= groceries (get-in p1 [:transaction/category :db/id])))
        (is (true? (:transaction/reviewed p1)))
        ;; Optional sub-entity fields stay absent, not nil/false
        (is (== -40.00M (:transaction/amount p2)))
        (is (not (contains? p2 :transaction/description)))
        (is (not (contains? p2 :transaction/category)))
        (is (not (contains? p2 :transaction/reviewed))))
      (testing "the old sub-entities (and the parent's :transaction/splits refs) are retracted"
        (is (empty? (old-split-eids tx-id)))))))

(deftest migrate-splits-idempotence-test
  (testing "a second run is a no-op (no duplicate parts, stable external-ids)"
    (let [tx-id (make-parent! "mig-2" {})]
      (d/transact! setup/*test-conn*
                   [{:db/id tx-id
                     :transaction/splits [{:split/amount -60.00M :split/order 0}
                                          {:split/amount -40.00M :split/order 1}]}])
      (migrations/migrate-splits! setup/*test-conn*)
      (let [first-ids (set (map :transaction/external-id (parts tx-id)))]
        (migrations/migrate-splits! setup/*test-conn*)
        (is (= 2 (count (parts tx-id))))
        (is (= first-ids (set (map :transaction/external-id (parts tx-id)))))))))

(deftest migrate-splits-posted-date-guard-test
  (testing "a parent with splits but no posted-date fails loudly (a part without one
            would vanish from every month query)"
    (let [tx-id (make-parent! "mig-3" {})]
      (d/transact! setup/*test-conn*
                   [[:db/retract tx-id :transaction/posted-date]
                    {:db/id tx-id
                     :transaction/splits [{:split/amount -60.00M :split/order 0}
                                          {:split/amount -40.00M :split/order 1}]}])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"posted-date"
                            (migrations/migrate-splits! setup/*test-conn*))))))

(deftest migrate-splits-untouched-data-test
  (testing "transactions without old-model splits are left alone"
    (let [tx-id (make-parent! "mig-4" {})]
      (migrations/migrate-splits! setup/*test-conn*)
      (is (empty? (parts tx-id)))
      (is (= "mig-4" (:transaction/external-id
                      (d/pull (d/db setup/*test-conn*) '[:transaction/external-id] tx-id)))))))

(deftest start-db-runs-migration-test
  (testing "opening the database migrates old-model splits (every entry point migrates)"
    (let [db-path (setup/create-temp-db-dir)]
      (try
        ;; Seed old-model data through a raw connection, then close it.
        (let [conn (db-core/start-db! db-path)]
          (d/transact! conn [{:transaction/external-id "boot-1"
                              :transaction/amount -10.00M
                              :transaction/posted-date posted
                              :transaction/splits [{:split/amount -6.00M :split/order 0}
                                                   {:split/amount -4.00M :split/order 1}]}])
          (db-core/stop-db! conn))
        (let [conn (db-core/start-db! db-path)]
          (try
            (is (= 2 (count (d/q '[:find [?p ...] :where [?p :transaction/split-parent _]]
                                 (d/db conn)))))
            (is (empty? (d/q '[:find [?s ...] :where [_ :transaction/splits ?s]] (d/db conn))))
            (finally (db-core/stop-db! conn))))
        (finally
          (setup/delete-directory-recursive (java.io.File. db-path)))))))

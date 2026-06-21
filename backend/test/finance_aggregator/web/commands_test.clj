(ns finance-aggregator.web.commands-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [datalevin.core :as d]
   [finance-aggregator.web.commands :as commands]
   [finance-aggregator.test-utils.setup :as setup]))

(use-fixtures :each setup/with-empty-db)

(defn- make-tx! [external-id]
  (d/transact! setup/*test-conn* [{:institution/id (str "inst-" external-id) :institution/name "Test Bank"}])
  (d/transact! setup/*test-conn* [{:account/external-id (str "acct-" external-id)
                                   :account/external-name "Test Account"
                                   :account/institution [:institution/id (str "inst-" external-id)]}])
  (d/transact! setup/*test-conn* [{:transaction/external-id external-id
                                   :transaction/account [:account/external-id (str "acct-" external-id)]
                                   :transaction/amount -100.00M :transaction/payee "Costco"
                                   :transaction/posted-date (java.util.Date.)}])
  (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:transaction/external-id external-id])))

(defn- reviewed? [tx-id]
  (true? (:transaction/reviewed (d/pull (d/db setup/*test-conn*) '[:transaction/reviewed] tx-id))))

(defn- cat! [name]
  (d/transact! setup/*test-conn* [{:category/name name :category/type :expense}])
  (ffirst (d/q '[:find ?c :in $ ?n :where [?c :category/name ?n]] (d/db setup/*test-conn*) name)))

(defn- split-count [tx-id]
  (count (:transaction/splits (d/pull (d/db setup/*test-conn*) '[{:transaction/splits [:db/id]}] tx-id))))

(deftest apply-undo-redo-reviewed
  (let [conn setup/*test-conn*
        tx-id (make-tx! "c-1")
        user :u1
        cmd {:type :set-reviewed :tx-id tx-id :before false :after true :label "Marked reviewed"}]
    (is (false? (reviewed? tx-id)) "starts unreviewed")

    (commands/apply! conn user cmd)
    (is (true? (reviewed? tx-id)) "apply runs the mutation to :after")
    (is (= #{tx-id} (commands/linger user)) "an edit lingers its tx")
    (is (= "Marked reviewed" (commands/undo-label user)) "undo label is the last action's")

    (commands/undo! conn user)
    (is (false? (reviewed? tx-id)) "undo runs the mutation to :before")
    (is (nil? (commands/undo-label user)) "nothing left to undo")

    (commands/redo! conn user)
    (is (true? (reviewed? tx-id)) "redo re-applies")
    (is (= "Marked reviewed" (commands/undo-label user)) "redo restores the undo entry")))

(deftest apply-undo-redo-splits
  (let [conn setup/*test-conn*
        tx-id (make-tx! "c-split")     ; amount -100.00
        cat (cat! "Groceries")
        user :usplit
        after [{:amount "-60.00" :category-id cat} {:amount "-40.00" :category-id cat}]
        cmd {:type :set-splits :tx-id tx-id :before [] :after after :label "Split transaction"}]
    (is (zero? (split-count tx-id)) "starts unsplit")

    (commands/apply! conn user cmd)
    (is (= 2 (split-count tx-id)) "apply creates the parts (full-replace to :after)")
    (is (= #{tx-id} (commands/linger user)) "the split lingers its tx")

    (commands/undo! conn user)
    (is (zero? (split-count tx-id)) "undo re-applies :before ([]) → un-split")

    (commands/redo! conn user)
    (is (= 2 (split-count tx-id)) "redo re-splits")))

(deftest a-new-edit-clears-redo
  (let [conn setup/*test-conn*
        tx-id (make-tx! "c-2")
        user :u2]
    (commands/apply! conn user {:type :set-reviewed :tx-id tx-id :before false :after true :label "a"})
    (commands/undo! conn user)
    (commands/apply! conn user {:type :set-reviewed :tx-id tx-id :before false :after true :label "b"})
    (is (nil? (commands/redo! conn user)) "a fresh edit drops the redo stack")))

(deftest linger-clears-on-view-change
  (let [conn setup/*test-conn*
        tx-id (make-tx! "c-3")
        user :u3]
    (commands/apply! conn user {:type :set-reviewed :tx-id tx-id :before false :after true :label "x"})
    (is (= #{tx-id} (commands/linger user)))
    (commands/clear-linger! user)
    (is (= #{} (commands/linger user)) "a pure view change clears lingering pins")))

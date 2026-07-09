(ns finance-aggregator.web.commands-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
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

(defn- posted-date-override [tx-id]
  (:transaction/user-posted-date (d/pull (d/db setup/*test-conn*) '[:transaction/user-posted-date] tx-id)))

(defn- cat! [name]
  (d/transact! setup/*test-conn* [{:category/name name :category/type :expense}])
  (ffirst (d/q '[:find ?c :in $ ?n :where [?c :category/name ?n]] (d/db setup/*test-conn*) name)))

(defn- split-count [tx-id]
  (count (d/q '[:find [?p ...] :in $ ?tx :where [?p :transaction/split-parent ?tx]]
              (d/db setup/*test-conn*) tx-id)))

(defn- partner [tx-id]
  (get-in (d/pull (d/db setup/*test-conn*) '[{:transaction/transfer-pair [:db/id]}] tx-id)
          [:transaction/transfer-pair :db/id]))

(defn- rejected? [tx-id other-id]
  (contains? (set (map :db/id (:transaction/transfer-rejected
                               (d/pull (d/db setup/*test-conn*)
                                       '[{:transaction/transfer-rejected [:db/id]}] tx-id))))
             other-id))

(defn- counterpart-tx!
  "A transaction on a fresh account with the given amount (so it can pair with make-tx!'s -100)."
  [external-id amount]
  (d/transact! setup/*test-conn* [{:institution/id (str "inst-" external-id) :institution/name "Test Bank"}])
  (d/transact! setup/*test-conn* [{:account/external-id (str "acct-" external-id) :account/external-name "Savings"
                                   :account/institution [:institution/id (str "inst-" external-id)]}])
  (d/transact! setup/*test-conn* [{:transaction/external-id external-id
                                   :transaction/account [:account/external-id (str "acct-" external-id)]
                                   :transaction/amount amount :transaction/payee "Counterpart"
                                   :transaction/posted-date (java.util.Date.)}])
  (:db/id (d/pull (d/db setup/*test-conn*) '[:db/id] [:transaction/external-id external-id])))

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

(deftest apply-undo-redo-posted-date
  (let [conn setup/*test-conn*
        tx-id (make-tx! "c-posted")
        user :uposted
        d1 #inst "2025-02-03"]
    (is (nil? (posted-date-override tx-id)) "starts with no override")

    (commands/apply! conn user {:type :set-posted-date :tx-id tx-id :before nil :after d1
                                :label "Set posted date"})
    (is (= d1 (posted-date-override tx-id)) "apply sets the override")
    (is (= #{tx-id} (commands/linger user)) "an edit lingers its tx")
    (is (= "Set posted date" (commands/undo-label user)))

    (commands/undo! conn user)
    (is (nil? (posted-date-override tx-id)) "undo retracts the override, falling back to imported")
    (is (nil? (commands/undo-label user)) "nothing left to undo")

    (commands/redo! conn user)
    (is (= d1 (posted-date-override tx-id)) "redo re-applies")

    ;; Clearing: a second command whose :after is nil, capturing the current override (d1) as
    ;; :before so ITS OWN undo restores it (not the absence from the very first apply).
    (commands/apply! conn user {:type :set-posted-date :tx-id tx-id :before d1 :after nil
                                :label "Cleared posted date"})
    (is (nil? (posted-date-override tx-id)) "apply clears the override")
    (is (= "Cleared posted date" (commands/undo-label user)))

    (commands/undo! conn user)
    (is (= d1 (posted-date-override tx-id)) "undo restores the prior override")))

(deftest apply-undo-redo-match
  (let [conn setup/*test-conn*
        out (make-tx! "c-out")               ; -100.00 on its own account
        in (counterpart-tx! "c-in" 100.00M)  ; +100.00 on a different account
        user :umatch]
    (is (nil? (partner out)) "starts unmatched")

    (commands/apply! conn user {:type :set-match :tx-id out :before nil :after in :label "Matched transfer"})
    (is (= in (partner out)) "apply links this leg")
    (is (= out (partner in)) "and the partner (bidirectional)")

    (commands/undo! conn user)
    (is (nil? (partner out)) "undo unlinks both legs")
    (is (nil? (partner in)))

    (commands/redo! conn user)
    (is (= in (partner out)) "redo relinks")))

(deftest apply-undo-reject
  (let [conn setup/*test-conn*
        a (make-tx! "c-rej-a")
        b (counterpart-tx! "c-rej-b" 100.00M)
        user :ureject]
    (commands/apply! conn user {:type :reject-match :tx-id a :partner b
                                :before false :after true :label "Rejected transfer"})
    (is (rejected? a b) "apply records the rejection")
    (is (rejected? b a) "symmetric")

    (commands/undo! conn user)
    (is (not (rejected? a b)) "undo retracts the rejection")
    (is (not (rejected? b a)))))

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

(deftest removed-split-part-ids-test
  (testing "ids in :before but missing from :after are the parts the edit just retracted"
    (is (= [10 20] (commands/removed-split-part-ids
                    [{:id 10 :amount "-1.00"} {:id 20 :amount "-2.00"} {:id 30 :amount "-3.00"}]
                    [{:id 30 :amount "-3.00"} {:amount "-4.00"}]))))

  (testing "nothing removed when every :before id survives in :after"
    (is (= [] (commands/removed-split-part-ids
               [{:id 1 :amount "-1.00"}] [{:id 1 :amount "-1.00"} {:amount "-2.00"}]))))

  (testing "un-splitting (empty :after) removes every prior part id"
    (is (= [1 2] (commands/removed-split-part-ids
                  [{:id 1 :amount "-1.00"} {:id 2 :amount "-2.00"}] []))))

  (testing "no prior split, nothing removed"
    (is (= [] (commands/removed-split-part-ids [] [])))))

(deftest forget-purges-commands-referencing-a-deleted-tx
  ;; A manual transaction that was matched (and reviewed) is deleted; forget! must drop
  ;; every command touching it so a later undo can't replay unmatch!/set-reviewed! against
  ;; the retracted row (which throws and would jam the stack forever).
  (let [conn setup/*test-conn*
        a (make-tx! "c-forget-a")             ; the "manual" row that gets deleted (-100)
        b (counterpart-tx! "c-forget-b" 100.00M)
        user :uforget]
    ;; The match command references A via :after (initiated from B); the reviewed command
    ;; references A via :tx-id — forget! must drop BOTH.
    (commands/apply! conn user {:type :set-match :tx-id b :before nil :after a :label "Matched transfer"})
    (commands/apply! conn user {:type :set-reviewed :tx-id a :before false :after true :label "Marked reviewed"})
    (is (= "Marked reviewed" (commands/undo-label user)))
    (is (contains? (commands/linger user) a))

    (commands/forget! user a)
    (is (nil? (commands/undo-label user)) "every command referencing A (by :tx-id or match :after) is gone")
    (is (nil? (commands/undo! conn user)) "nothing left to undo — no replay against the retracted row")
    (is (not (contains? (commands/linger user) a)) "A is no longer lingering")))

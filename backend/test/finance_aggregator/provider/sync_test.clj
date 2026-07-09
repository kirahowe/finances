(ns finance-aggregator.provider.sync-test
  "Tests the generic sync orchestrator against a fake in-memory `:test`
   provider (no network). Locks down account-before-transaction ordering,
   cross-page removed-transaction retraction, :more?/:next-opts looping, the
   terminal-status / on-complete hooks, and the no-mid-pagination-cursor guarantee."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datalevin.core :as d]
   [finance-aggregator.db.connections :as connections]
   [finance-aggregator.db.transactions :as transactions]
   [finance-aggregator.db.transfers :as db-transfers]
   [finance-aggregator.provider :as provider]
   [finance-aggregator.provider.sync :as sync]
   [finance-aggregator.test-utils.setup :as setup])
  (:import
   [java.util Date]))

(use-fixtures :each setup/with-empty-db)

;;; Fake `:test` provider --------------------------------------------------

(defn- canonical-txn [ext-id]
  {:transaction/external-id ext-id
   :transaction/account [:account/external-id "test-acc-1"]
   :transaction/date (Date.)
   :transaction/posted-date (Date.)
   :transaction/amount (bigdec "1.00")
   :transaction/payee "Test Payee"
   :transaction/provider :test
   :transaction/user [:user/id "test-user"]})

(defmethod provider/fetch-accounts :test
  [_ _opts]
  {:institutions #{{:institution/id "test-inst" :institution/name "Test Inst"}}
   :accounts #{{:account/external-id "test-acc-1"
                :account/external-name "Test Account"
                :account/currency "USD"
                :account/provider :test
                :account/institution [:institution/id "test-inst"]
                :account/user [:user/id "test-user"]}}})

;; Two pages: page 0 inserts t1+t2; page 1 inserts t3 and removes t1. Page 0
;; carries a mid-pagination :sync-state ("cur-0") that the orchestrator must NOT
;; persist; only the terminal page's cursor ("cur-1") is stored (when a
;; :connection-id is present).
(defmethod provider/fetch-transactions :test
  [_ {:keys [page] :or {page 0} :as opts}]
  (case (long page)
    0 {:transactions [(canonical-txn "t1") (canonical-txn "t2")]
       :removed []
       :more? true
       :sync-state "cur-0"
       :next-opts (assoc opts :page 1)}
    1 {:transactions [(canonical-txn "t3")]
       :removed ["t1"]
       :sync-state "cur-1"
       :more? false}))

;;; Fake `:test-crash` provider: page 0 paginates (mid cursor "crash-cur-0"),
;;; page 1 throws - to prove a crash mid-loop never persists the mid cursor.

(defmethod provider/fetch-accounts :test-crash
  [_ _opts]
  {:institutions #{{:institution/id "test-inst" :institution/name "Test Inst"}}
   :accounts #{{:account/external-id "test-acc-1"
                :account/external-name "Test Account"
                :account/currency "USD"
                :account/provider :test-crash
                :account/institution [:institution/id "test-inst"]
                :account/user [:user/id "test-user"]}}})

(defmethod provider/fetch-transactions :test-crash
  [_ {:keys [page] :or {page 0} :as opts}]
  (case (long page)
    0 {:transactions [(canonical-txn "tc1")]
       :removed []
       :more? true
       :sync-state "crash-cur-0"
       :next-opts (assoc opts :page 1)}
    1 (throw (ex-info "boom mid-pagination" {}))))

;;; Fake `:test-rich` provider: exercises the provider-driven terminal status,
;;; status-opts ws payload, and the post-persist :on-complete hook.

(def ^:private on-complete-calls (atom []))

(defmethod provider/fetch-accounts :test-rich
  [_ _opts]
  {:institutions #{{:institution/id "rich-inst" :institution/name "Rich Inst"}}
   :accounts #{{:account/external-id "rich-acc-1"
                :account/external-name "Rich Account"
                :account/currency "USD"
                :account/provider :test-rich
                :account/institution [:institution/id "rich-inst"]
                :account/user [:user/id "test-user"]}}})

(defmethod provider/fetch-transactions :test-rich
  [_ {:keys [db-conn]}]
  {:transactions [(assoc (canonical-txn "rt-1")
                         :transaction/account [:account/external-id "rich-acc-1"])]
   :removed []
   :more? false
   :status :syncing-historical
   ;; Records what the db looks like at on-complete time, to prove it runs
   ;; AFTER transactions are persisted.
   :on-complete (fn []
                  (swap! on-complete-calls conj
                         (set (d/q '[:find [?e ...]
                                     :where [?e :transaction/external-id "rt-1"]]
                                   (d/db db-conn)))))})

;;; Helpers ----------------------------------------------------------------

(defn- seed-user! [conn]
  (d/transact! conn [{:user/id "test-user" :user/created-at (Date.)}]))

(defn- tx-external-ids [conn]
  (set (d/q '[:find [?ext ...]
              :where [?e :transaction/external-id ?ext]]
            (d/db conn))))

(defn- eid-for [conn ext-id]
  (d/q '[:find ?e . :in $ ?ext :where [?e :transaction/external-id ?ext]] (d/db conn) ext-id))

(defn- split-part-ids [conn parent-id]
  (d/q '[:find [?p ...] :in $ ?parent :where [?p :transaction/split-parent ?parent]]
       (d/db conn) parent-id))

;;; Tests ------------------------------------------------------------------

(deftest sync-provider-persists-accounts-and-pages-transactions
  (let [conn setup/*test-conn*]
    (seed-user! conn)
    (testing "orchestrator returns :synced"
      (is (= :synced (sync/sync-provider! {:db-conn conn} :test))))

    (testing "institution and account are persisted (accounts before txns so refs resolve)"
      (is (= "Test Inst"
             (:institution/name (d/pull (d/db conn) '[:institution/name]
                                        [:institution/id "test-inst"]))))
      (is (= :test
             (:account/provider (d/pull (d/db conn) '[:account/provider]
                                        [:account/external-id "test-acc-1"])))))

    (testing "paged transactions land, and cross-page removed id is retracted"
      ;; t1 inserted on page 0 then removed on page 1; t2 and t3 remain.
      (is (= #{"t2" "t3"} (tx-external-ids conn))))))

(deftest sync-provider-persists-terminal-sync-state-not-mid-pagination
  (testing "With a :connection-id, only the terminal page's cursor is persisted -
            the mid-pagination cursor is never durably stored"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (connections/ensure-connection! conn {:id "test-conn-1" :provider :test})
      (is (= :synced (sync/sync-provider! {:db-conn conn :connection-id "test-conn-1"} :test)))
      ;; "cur-0" (page 0, has_more=true) is mid-pagination and must be skipped;
      ;; "cur-1" (terminal page) is the stored cursor.
      (is (= "cur-1" (connections/get-sync-state conn "test-conn-1"))))))

(deftest sync-provider-stamps-account-connection-when-connection-id-present
  (testing "Accounts are linked to the driving connection so the setup view can
            group + show per-connection freshness"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (connections/ensure-connection! conn {:id "test-conn-1" :provider :test})
      (is (= :synced (sync/sync-provider! {:db-conn conn :connection-id "test-conn-1"} :test)))
      (let [acct (d/pull (d/db conn) '[{:account/connection [:connection/id]}]
                         [:account/external-id "test-acc-1"])]
        (is (= "test-conn-1" (get-in acct [:account/connection :connection/id])))))))

(deftest sync-provider-skips-account-connection-without-connection-id
  (testing "A non-connection sync (no :connection-id) leaves accounts unstamped"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (is (= :synced (sync/sync-provider! {:db-conn conn} :test)))
      (is (nil? (:account/connection
                 (d/pull (d/db conn) '[:account/connection]
                         [:account/external-id "test-acc-1"])))))))

(deftest sync-provider-does-not-persist-mid-pagination-cursor-on-crash
  (testing "A crash mid-pagination leaves the durable cursor at the loop start
            (nil here), never the mid-page cursor - so the next pass restarts the
            whole loop cleanly instead of resuming an invalidated cursor"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (connections/ensure-connection! conn {:id "crash-conn" :provider :test-crash})
      (is (thrown? clojure.lang.ExceptionInfo
                   (sync/sync-provider! {:db-conn conn :connection-id "crash-conn"} :test-crash)))
      (is (nil? (connections/get-sync-state conn "crash-conn"))
          "mid-pagination cursor 'crash-cur-0' must NOT be persisted"))))

(deftest sync-provider-without-connection-id-skips-sync-state
  (testing "No :connection-id => no connection writes, sync still completes"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (is (= :synced (sync/sync-provider! {:db-conn conn} :test)))
      (is (empty? (connections/list-connections conn))))))

(deftest modified-upsert-preserves-user-overlays
  ;; The load-bearing invariant: a Plaid `modified` re-import (same external-id,
  ;; changed imported fields) updates provenance fields but never clobbers the
  ;; user's append-only overlays. persist-transactions! is the path it flows through.
  (let [conn setup/*test-conn*]
    (seed-user! conn)
    (d/transact! conn [{:account/external-id "test-acc-1" :account/provider :test
                        :account/user [:user/id "test-user"]}])
    (sync/persist-transactions! conn [(assoc (canonical-txn "tx-1")
                                             :transaction/description "Original")])
    (let [eid (d/q '[:find ?e . :where [?e :transaction/external-id "tx-1"]] (d/db conn))]
      (d/transact! conn [{:db/id eid
                          :transaction/reviewed true
                          :transaction/user-description "My label"}]))
    ;; Re-import the same transaction with bank-changed fields.
    (sync/persist-transactions! conn [(assoc (canonical-txn "tx-1")
                                             :transaction/description "Bank renamed"
                                             :transaction/amount (bigdec "12.00"))])
    (let [t (d/pull (d/db conn) '[*] [:transaction/external-id "tx-1"])]
      (testing "imported fields updated by the re-import"
        (is (= "Bank renamed" (:transaction/description t)))
        (is (= (bigdec "12.00") (:transaction/amount t))))
      (testing "user overlays preserved"
        (is (= true (:transaction/reviewed t)))
        (is (= "My label" (:transaction/user-description t)))))))

(deftest persist-transactions-rejects-overlay-keys
  (let [conn setup/*test-conn*]
    (seed-user! conn)
    (d/transact! conn [{:account/external-id "test-acc-1" :account/provider :test
                        :account/user [:user/id "test-user"]}])
    (is (thrown? clojure.lang.ExceptionInfo
                 (sync/persist-transactions!
                  conn [(assoc (canonical-txn "tx-x") :transaction/reviewed true)])))))

(deftest retract-removed-cascades-split-parts-test
  (testing "a removed transaction's live parts cascade with it — the bank says the
            money never happened, so a matched part's transfer link is unlinked and
            both the parent and its parts are retracted in the one transact!"
    (let [conn setup/*test-conn*]
      (seed-user! conn)
      (d/transact! conn [{:account/external-id "test-acc-1" :account/provider :test
                          :account/user [:user/id "test-user"]}])
      (d/transact! conn [{:account/external-id "test-acc-partner" :account/provider :test
                          :account/user [:user/id "test-user"]}])
      (d/transact! conn [{:transaction/external-id "tx-removed-partner"
                          :transaction/account [:account/external-id "test-acc-partner"]
                          :transaction/amount -0.50M
                          :transaction/posted-date (Date.)}])
      (sync/persist-transactions! conn [(canonical-txn "tx-removed-parent")])
      (let [parent-id (eid-for conn "tx-removed-parent")
            partner-id (eid-for conn "tx-removed-partner")]
        ;; Even split so either live part matches the partner's exact opposite amount
        ;; regardless of query ordering.
        (transactions/set-splits! conn parent-id [{:amount "0.50"} {:amount "0.50"}])
        (let [[p1-id p2-id] (split-part-ids conn parent-id)]
          (db-transfers/confirm-match! conn p1-id partner-id)
          (sync/retract-removed! conn ["tx-removed-parent"])
          (is (nil? (:transaction/external-id
                     (d/pull (d/db conn) '[:transaction/external-id] parent-id)))
              "the parent is retracted")
          (is (every? #(nil? (:transaction/external-id
                              (d/pull (d/db conn) '[:transaction/external-id] %)))
                      [p1-id p2-id])
              "both parts are retracted")
          (is (nil? (:transaction/transfer-pair
                     (d/pull (d/db conn) '[{:transaction/transfer-pair [:db/id]}] partner-id)))
              "the matched partner's back-ref is cleared, not left dangling"))))))

(deftest persist-transactions-propagates-inherited-fields-to-split-parts-test
  (let [conn setup/*test-conn*
        original-posted (Date. 1700000000000)
        original-date   (Date. 1699900000000)
        changed-posted  (Date. 1700100000000)]
    (seed-user! conn)
    (d/transact! conn [{:account/external-id "test-acc-1" :account/provider :test
                        :account/user [:user/id "test-user"]}])
    (sync/persist-transactions! conn [(assoc (canonical-txn "tx-inherit-parent")
                                             :transaction/date original-date
                                             :transaction/posted-date original-posted
                                             :transaction/payee "Original Payee")])
    (let [parent-id (eid-for conn "tx-inherit-parent")]
      (transactions/set-splits! conn parent-id [{:amount "0.60"} {:amount "0.40"}])
      (let [part-ids (split-part-ids conn parent-id)]

        (testing "a re-import that moves posted-date + payee propagates onto the live parts"
          (sync/persist-transactions! conn [(assoc (canonical-txn "tx-inherit-parent")
                                                   :transaction/date original-date
                                                   :transaction/posted-date changed-posted
                                                   :transaction/payee "Renamed Payee")])
          (doseq [pid part-ids]
            (let [part (d/pull (d/db conn) '[:transaction/posted-date :transaction/payee] pid)]
              (is (= changed-posted (:transaction/posted-date part)) "posted-date propagated")
              (is (= "Renamed Payee" (:transaction/payee part)) "payee propagated"))))

        (testing "an identical re-import changes nothing (idempotent)"
          (sync/persist-transactions! conn [(assoc (canonical-txn "tx-inherit-parent")
                                                   :transaction/date original-date
                                                   :transaction/posted-date changed-posted
                                                   :transaction/payee "Renamed Payee")])
          (doseq [pid part-ids]
            (let [part (d/pull (d/db conn) '[:transaction/posted-date :transaction/payee] pid)]
              (is (= changed-posted (:transaction/posted-date part)))
              (is (= "Renamed Payee" (:transaction/payee part))))))

        (testing "a changed parent amount is NOT propagated — it surfaces as split-drift instead"
          (sync/persist-transactions! conn [(assoc (canonical-txn "tx-inherit-parent")
                                                   :transaction/date original-date
                                                   :transaction/posted-date changed-posted
                                                   :transaction/payee "Renamed Payee"
                                                   :transaction/amount (bigdec "5.00"))])
          (let [amounts (map #(:transaction/amount (d/pull (d/db conn) '[:transaction/amount] %)) part-ids)]
            (is (== 1.00M (reduce + amounts))
                "part amounts still sum to the ORIGINAL parent total — never propagated"))
          ;; by-id is one of the annotated "list fns" (with-derived-fields) — this proves
          ;; the drift surfaces through the same path a client actually reads.
          (is (true? (:transaction/split-drift (transactions/by-id conn (first part-ids))))
              "drift flagged instead, since the parts no longer reconcile to the new amount"))))))

(deftest sync-provider-rethrows-on-error
  (let [conn setup/*test-conn*]
    (seed-user! conn)
    ;; No :boom methods registered -> dispatch throws IllegalArgumentException,
    ;; which propagates to the caller (resync's per-connection isolation records it).
    (is (thrown? IllegalArgumentException
                 (sync/sync-provider! {:db-conn conn} :boom)))))

(deftest sync-provider-honors-terminal-status-and-on-complete
  (let [conn setup/*test-conn*]
    (reset! on-complete-calls [])
    (seed-user! conn)
    (testing "returns the provider's terminal status, not a hardcoded :synced"
      (is (= :syncing-historical
             (sync/sync-provider! {:db-conn conn} :test-rich))))

    (testing ":on-complete runs after transactions are persisted"
      (is (= 1 (count @on-complete-calls)) "on-complete invoked exactly once")
      (is (seq (first @on-complete-calls))
          "rt-1 already in db when on-complete ran (post-persist hook)"))))

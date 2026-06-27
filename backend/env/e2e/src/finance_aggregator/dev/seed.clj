(ns finance-aggregator.dev.seed
  "Deterministic dataset for end-to-end tests. All transactions live in a fixed
   month (2025-01) so the UI shows the same data regardless of the run date — the
   e2e specs navigate to ?month=2025-01.

   Scenarios covered:
   - an uncategorized inverse pair that auto-match should propose (out-1/in-1)
   - a pre-matched pure transfer (credit-card payment) that the Hide toggle removes
   - a matched mortgage payment categorized Housing that stays visible under Hide
   - a transfer-typed transaction with no in-window counterpart (unmatched marker),
     plus an out-of-auto-window counterpart that manual Match can still find
   - some real income/expense noise"
  (:require [datalevin.core :as d])
  (:import [java.time LocalDate ZoneOffset]))

(defn- inst [s]
  (java.util.Date/from (.toInstant (.atStartOfDay (LocalDate/parse s) ZoneOffset/UTC))))

(def ^:private root-attrs
  [:transaction/external-id :account/external-id :institution/id :category/ident :user/id
   :connection/id])

(defn clear!
  "Retract every seeded entity (and its components / incoming refs)."
  [conn]
  (let [db (d/db conn)
        ids (distinct (mapcat (fn [a] (d/q '[:find [?e ...] :in $ ?a :where [?e ?a _]] db a))
                              root-attrs))]
    (when (seq ids)
      (d/transact! conn (mapv (fn [e] [:db/retractEntity e]) ids)))))

(defn seed!
  "Wipe and insert the deterministic e2e dataset. Idempotent."
  [conn]
  (clear! conn)
  (d/transact! conn [{:institution/id "inst-test" :institution/name "Test Bank"}])
  ;; A synced Plaid connection owns the four accounts (the realistic shape: the
  ;; /setup page groups accounts under the connection that syncs them).
  (d/transact! conn [{:user/id "test-user" :user/created-at (inst "2025-01-01")}])
  (d/transact! conn [{:connection/id "plaid:seed-item"
                      :connection/user [:user/id "test-user"]
                      :connection/provider :plaid
                      :connection/external-id "seed-item"
                      :connection/institution-name "Test Bank"
                      :connection/status :synced
                      :connection/last-success-at (inst "2025-01-20")
                      :connection/created-at (inst "2025-01-01")}])
  (d/transact! conn
               (mapv (fn [a] (assoc a :account/provider :plaid
                                    :account/connection [:connection/id "plaid:seed-item"]))
                     [{:account/external-id "acct-chequing" :account/external-name "Chequing"
                       :account/currency "CAD" :account/type :chequing :account/mask "1111"
                       :account/institution [:institution/id "inst-test"]}
                      {:account/external-id "acct-savings" :account/external-name "Savings"
                       :account/currency "CAD" :account/type :savings :account/mask "2222"
                       :account/institution [:institution/id "inst-test"]}
                      {:account/external-id "acct-visa" :account/external-name "Visa"
                       :account/currency "CAD" :account/type :credit :account/mask "3333"
                       :account/institution [:institution/id "inst-test"]}
                      {:account/external-id "acct-mortgage" :account/external-name "Mortgage"
                       :account/currency "CAD" :account/type :loan :account/mask "4444"
                       :account/institution [:institution/id "inst-test"]}]))
  (d/transact! conn
               [{:category/ident :category/housing :category/name "Housing" :category/type :expense}
                {:category/ident :category/groceries :category/name "Groceries" :category/type :expense}
                {:category/ident :category/salary :category/name "Salary" :category/type :income}
                {:category/ident :category/transfer :category/name "Transfer" :category/type :transfer}])
  (d/transact! conn
               [;; Real income/expense noise
                {:transaction/external-id "seed-salary" :transaction/account [:account/external-id "acct-chequing"]
                 :transaction/amount 4000.00M :transaction/payee "Acme Payroll"
                 :transaction/posted-date (inst "2025-01-01")
                 :transaction/category [:category/ident :category/salary]}
                {:transaction/external-id "seed-groceries" :transaction/account [:account/external-id "acct-visa"]
                 :transaction/amount -85.00M :transaction/payee "Superstore"
                 :transaction/posted-date (inst "2025-01-05")
                 :transaction/category [:category/ident :category/groceries]}
                ;; (A) Uncategorized inverse pair within the auto window -> 1 suggestion
                {:transaction/external-id "seed-out-1" :transaction/account [:account/external-id "acct-chequing"]
                 :transaction/amount -500.00M :transaction/payee "Transfer to Savings"
                 :transaction/posted-date (inst "2025-01-10")}
                {:transaction/external-id "seed-in-1" :transaction/account [:account/external-id "acct-savings"]
                 :transaction/amount 500.00M :transaction/payee "Transfer from Chequing"
                 :transaction/posted-date (inst "2025-01-11")}
                ;; (B) Pre-matched pure transfer (credit-card payment) -> hidden by toggle
                {:transaction/external-id "seed-out-2" :transaction/account [:account/external-id "acct-chequing"]
                 :transaction/amount -300.00M :transaction/payee "Visa Payment"
                 :transaction/posted-date (inst "2025-01-12")}
                {:transaction/external-id "seed-in-2" :transaction/account [:account/external-id "acct-visa"]
                 :transaction/amount 300.00M :transaction/payee "Payment Received"
                 :transaction/posted-date (inst "2025-01-12")}
                ;; (C) Matched mortgage payment categorized Housing -> stays visible under Hide
                {:transaction/external-id "seed-mortgage-out" :transaction/account [:account/external-id "acct-chequing"]
                 :transaction/amount -2000.00M :transaction/payee "Mortgage Payment"
                 :transaction/posted-date (inst "2025-01-15")
                 :transaction/category [:category/ident :category/housing]}
                {:transaction/external-id "seed-mortgage-in" :transaction/account [:account/external-id "acct-mortgage"]
                 :transaction/amount 2000.00M :transaction/payee "Mortgage Principal"
                 :transaction/posted-date (inst "2025-01-15")}
                ;; (D) Unmatched transfer (counterpart is outside the auto window, inside manual window)
                {:transaction/external-id "seed-unmatched" :transaction/account [:account/external-id "acct-chequing"]
                 :transaction/amount -750.00M :transaction/payee "Transfer Out"
                 :transaction/posted-date (inst "2025-01-18")
                 :transaction/category [:category/ident :category/transfer]}
                {:transaction/external-id "seed-unmatched-cp" :transaction/account [:account/external-id "acct-savings"]
                 :transaction/amount 750.00M :transaction/payee "Transfer In Later"
                 :transaction/posted-date (inst "2025-01-25")}])
  ;; Confirm the pre-matched pairs (bidirectional transfer-pair).
  (let [db (d/db conn)
        eid (fn [ext] (:db/id (d/pull db '[:db/id] [:transaction/external-id ext])))]
    (d/transact! conn
                 [{:db/id (eid "seed-out-2") :transaction/transfer-pair (eid "seed-in-2")}
                  {:db/id (eid "seed-in-2") :transaction/transfer-pair (eid "seed-out-2")}
                  {:db/id (eid "seed-mortgage-out") :transaction/transfer-pair (eid "seed-mortgage-in")}
                  {:db/id (eid "seed-mortgage-in") :transaction/transfer-pair (eid "seed-mortgage-out")}]))
  :seeded)

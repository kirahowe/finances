(ns finance-aggregator.db.statements
  "Statement periods — user-entered arbitrary-span reconciliations (a credit-card statement
   between two arbitrary dates, whose balance can't be read on a chosen day). A statement is
   self-contained: two dated balances the account's tracked activity in the span must explain
   (end-balance - start-balance = Σ txns in (start, end]). Distinct from the month-boundary
   reconciliation (which reads :snapshot/* at calendar bounds); a month reconciles for an
   account when its transactions are all covered by reconciled periods (month-boundary OR
   statement). Identified by :db/id — edited/deleted as a unit, no unique key (dates change).

   Data layer: single-user (auth/user-id); the :statement/user ref gives the eventual
   multi-user migration one place to change."
  (:require
   [datalevin.core :as d]
   [finance-aggregator.auth :as auth]
   [finance-aggregator.db.users :as db-users])
  (:import
   [java.util Date]))

(def ^:private pull-pattern
  [:db/id :statement/start-date :statement/start-balance
   :statement/end-date :statement/end-balance
   {:statement/account [:db/id :account/external-name]}])

(defn- ->display
  "A pulled statement as the display/reconcile map the panel + ledger consume."
  [s]
  {:id            (:db/id s)
   :account-eid   (get-in s [:statement/account :db/id])
   :account-name  (get-in s [:statement/account :account/external-name])
   :start-date    (:statement/start-date s)
   :start-balance (:statement/start-balance s)
   :end-date      (:statement/end-date s)
   :end-balance   (:statement/end-balance s)})

(defn create!
  "Create a statement for `account-eid` spanning [start-date, end-date] (java.util.Dates)
   with its two statement balances. Returns the new statement entity id."
  [conn {:keys [account-eid start-date start-balance end-date end-balance]}]
  (db-users/ensure-user! conn auth/user-id)
  (let [{:keys [tempids]}
        (d/transact! conn [{:db/id                   "new-statement"
                            :statement/account       account-eid
                            :statement/user          [:user/id auth/user-id]
                            :statement/start-date    start-date
                            :statement/start-balance (bigdec start-balance)
                            :statement/end-date      end-date
                            :statement/end-balance   (bigdec end-balance)}])]
    (get tempids "new-statement")))

(defn update!
  "Update a statement's dates/balances by entity id — only the keys supplied. Returns db-conn."
  [conn eid {:keys [start-date start-balance end-date end-balance]}]
  (d/transact! conn [(cond-> {:db/id eid}
                       start-date            (assoc :statement/start-date start-date)
                       (some? start-balance) (assoc :statement/start-balance (bigdec start-balance))
                       end-date              (assoc :statement/end-date end-date)
                       (some? end-balance)   (assoc :statement/end-balance (bigdec end-balance)))])
  conn)

(defn delete!
  "Retract the statement with entity id `eid`. Guards on it actually being a statement, so a
   stray id can't retract something else. No-op when absent. Returns db-conn."
  [conn eid]
  (let [s (d/pull (d/db conn) [:db/id :statement/start-date] eid)]
    (when (:statement/start-date s)
      (d/transact! conn [[:db/retractEntity eid]])))
  conn)

(defn by-id
  "The statement with entity id `eid` as a display map, or nil when it isn't a statement."
  [conn eid]
  (let [s (d/pull (d/db conn) pull-pattern eid)]
    (when (:statement/start-date s) (->display s))))

(defn list-overlapping
  "The statements for `account-eid` that overlap the span [from, to] (java.util.Dates) —
   start-date < to AND end-date > from — earliest start first, as display maps. The periods
   relevant to reconciling/covering a month (pass the month bounds)."
  [conn account-eid ^Date from ^Date to]
  (->> (d/q '[:find [(pull ?s pattern) ...]
              :in $ pattern ?acct
              :where [?s :statement/account ?acct]]
            (d/db conn) pull-pattern account-eid)
       (map ->display)
       (filter (fn [{:keys [start-date end-date]}]
                 (and (.before ^Date start-date to)
                      (.after ^Date end-date from))))
       (sort-by :start-date)
       vec))

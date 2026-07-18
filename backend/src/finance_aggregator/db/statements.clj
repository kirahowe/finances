(ns finance-aggregator.db.statements
  "Statement periods — user-entered arbitrary-span reconciliations (a credit-card statement
   between two arbitrary dates, whose balance can't be read on a chosen day). A statement is
   self-contained: two dated balances the account's tracked activity in the span must explain
   (end-balance - start-balance = Σ txns in [start-date, end-date] INCLUSIVE, as the period reads
   off the statement — the start-balance is the 'previous balance' carried in before start-date,
   so start-date's own activity belongs to the period). The shared reconcile/coverage math is
   half-open (start, end], so callers hand it data.ledger/statement-opening-boundary — the day
   before start-date — to make start-date the first included day. Distinct from the month-boundary
   reconciliation (which reads :snapshot/* at calendar bounds); a month reconciles for an
   account when its transactions are all covered by reconciled periods (month-boundary OR
   statement). Identified by :db/id — edited/deleted as a unit, no unique key (dates change).

   Data layer: single-user (auth/user-id); the :statement/user ref gives the eventual
   multi-user migration one place to change."
  (:require
   [clojure.string :as str]
   [datalevin.core :as d]
   [finance-aggregator.auth :as auth]
   [finance-aggregator.db.users :as db-users])
  (:import
   [java.util Date]))

(def ^:private pull-pattern
  [:db/id :statement/start-date :statement/start-balance
   :statement/end-date :statement/end-balance
   {:statement/account [:db/id :account/external-name :account/display-name
                        :account/type :account/statement-polarity]}])

(defn- ->display
  "A pulled statement as the display/reconcile map the panel + ledger consume.
   :account-name prefers the user's rename overlay (:account/display-name) over the
   provider's :account/external-name — a data-layer-local `(or …)`, the same
   preference web.accounts/account-label applies in the view layer, since this
   namespace can't reach up to it. Carries the account's RAW :account/type and
   :account/statement-polarity through too (NOT a resolved :polarity) — deciding which
   polarity applies is data.ledger/effective-statement-polarity's job (pure, Transformation
   layer); this Data-layer namespace mustn't reach up to call it, the same reason
   :account-name's own fallback is inlined above rather than calling web.accounts/
   account-label. Callers (web.view/reconcile-statement, via its caller
   web.pages.transactions/statement-models) resolve + thread the effective polarity."
  [s]
  {:id            (:db/id s)
   :account-eid   (get-in s [:statement/account :db/id])
   :account-name  (let [dn (get-in s [:statement/account :account/display-name])]
                    (or (when-not (str/blank? dn) dn)
                        (get-in s [:statement/account :account/external-name])))
   :start-date    (:statement/start-date s)
   :start-balance (:statement/start-balance s)
   :end-date      (:statement/end-date s)
   :end-balance   (:statement/end-balance s)
   :account/type               (get-in s [:statement/account :account/type])
   :account/statement-polarity (get-in s [:statement/account :account/statement-polarity])})

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

(defn list-for-account
  "ALL statements for `account-eid`, earliest start first, as display maps — the account's
   full statement history. `list-overlapping` builds on this (pulls everything, then filters
   to a span); the statement-lens stepper (web.statement-lens/adjacent-span, driven from
   web.pages.transactions/statement-step) needs the same full list to search for the real
   adjacent statement in either direction, unbounded by any one month."
  [conn account-eid]
  (->> (d/q '[:find [(pull ?s pattern) ...]
              :in $ pattern ?acct
              :where [?s :statement/account ?acct]]
            (d/db conn) pull-pattern account-eid)
       (map ->display)
       (sort-by :start-date)
       vec))

(defn list-overlapping
  "The statements for `account-eid` that overlap the span [from, to] (java.util.Dates) —
   start-date < to AND end-date > from — earliest start first, as display maps. The periods
   relevant to reconciling/covering a month (pass the month bounds)."
  [conn account-eid ^Date from ^Date to]
  (->> (list-for-account conn account-eid)
       (filter (fn [{:keys [start-date end-date]}]
                 (and (.before ^Date start-date to)
                      (.after ^Date end-date from))))
       vec))

(defn account-eids-overlapping
  "Account entity-ids with ANY statement overlapping [from, to] (java.util.Dates) — same
   start-date < to AND end-date > from predicate as list-overlapping, but the reverse
   direction: which accounts have a period here, not one account's own periods. Used to find
   'quiet' accounts — no transactions this month, but a statement overlaps it anyway (see
   web.pages.transactions/close-model-for) — so a drifting statement on them still gates the
   close even with nothing tracked to explain it."
  [conn ^Date from ^Date to]
  (->> (d/q '[:find ?acct ?start ?end
              :where
              [?s :statement/account ?acct]
              [?s :statement/start-date ?start]
              [?s :statement/end-date ?end]]
            (d/db conn))
       (filter (fn [[_ ^Date start ^Date end]]
                 (and (.before start to) (.after end from))))
       (map first)
       set))

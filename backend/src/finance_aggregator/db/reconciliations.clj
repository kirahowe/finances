(ns finance-aggregator.db.reconciliations
  "Monthly close events — the \"lock it in\" record of the monthly-close workflow.
   One event per user per calendar month.

   Being closed is a MONTH-level fact: whether a transaction is reconciled is
   derived from its month having a close event (see the schema note), so a
   transaction later imported or edited into a closed month surfaces as drift
   (against the frozen totals) rather than needing a per-row flag, and reopening is
   just retracting this one entity. The month's category totals are frozen here at
   close, so the cross-month tracking view reads immutable figures even when a
   closed month is later touched.

   Data layer: single-user (auth/user-id); the :reconciliation/user ref gives the
   eventual multi-user migration one place to change."
  (:require
   [datalevin.core :as d]
   [finance-aggregator.auth :as auth]
   [finance-aggregator.db.users :as db-users])
  (:import
   [java.util Date]))

(defn- close-id
  "Identity of the close event for `month` (user-scoped so months never collide
   across users once multi-user lands)."
  [month]
  (str auth/user-id ":" month))

(defn get-close
  "The close event for `month` (YYYY-MM), or nil when the month isn't closed."
  [conn month]
  (let [e (d/pull (d/db conn) '[*] [:reconciliation/id (close-id month)])]
    (when (:db/id e) e)))

(defn closed?
  "True when `month` has been closed."
  [conn month]
  (some? (get-close conn month)))

(defn close-month!
  "Create or overwrite the close event for `month` (YYYY-MM), freezing `totals`
   {:income :expenses :transfers :net} (bigdec) and stamping `closed-at`. Idempotent
   per month — re-closing overwrites the frozen totals and timestamp (e.g. closing
   again after resolving drift). `closed-at` is passed in so this stays
   deterministic under test. Returns the close entity."
  [conn month {:keys [income expenses transfers net]} ^Date closed-at]
  (db-users/ensure-user! conn auth/user-id)
  (d/transact! conn [{:reconciliation/id        (close-id month)
                      :reconciliation/user      [:user/id auth/user-id]
                      :reconciliation/month     month
                      :reconciliation/closed-at closed-at
                      :reconciliation/income    (bigdec income)
                      :reconciliation/expenses  (bigdec expenses)
                      :reconciliation/transfers (bigdec transfers)
                      :reconciliation/net       (bigdec net)}])
  (get-close conn month))

(defn reopen-month!
  "Retract the close event for `month` (unlock it). No-op when the month isn't
   closed. Returns db-conn."
  [conn month]
  (when-let [e (get-close conn month)]
    (d/transact! conn [[:db/retractEntity (:db/id e)]]))
  conn)

(defn list-closes
  "Every close event for the user, most-recent month first — the source for the
   cross-month tracking view. Months are yyyy-MM strings, so lexical order is
   chronological."
  [conn]
  (->> (d/q '[:find [(pull ?e [*]) ...]
              :in $ ?uid
              :where
              [?u :user/id ?uid]
              [?e :reconciliation/user ?u]]
            (d/db conn) auth/user-id)
       (sort-by :reconciliation/month)
       reverse
       vec))

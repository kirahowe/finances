(ns finance-aggregator.web.pages.transactions
  "Route handlers for the server-authoritative transactions workspace at `/` — the HTTP/SSE
   orchestration layer. Each handler loads a month's transactions, runs the pure view engine
   (web.view), applies an undo/redo command (web.commands), and SSE-patches the rendered
   fragments. All data logic lives in the pure, tested web.view + web.view-state; all hiccup
   rendering lives in web.pages.transactions-view. No business logic here — request → command
   → patch."
  (:require
   [clojure.string :as str]
   [finance-aggregator.auth :as auth]
   [finance-aggregator.db.accounts :as db-accounts]
   [finance-aggregator.db.categories :as db-categories]
   [finance-aggregator.db.reconciliations :as db-reconciliations]
   [finance-aggregator.db.snapshots :as db-snapshots]
   [finance-aggregator.db.stats :as db-stats]
   [finance-aggregator.db.transactions :as db-transactions]
   [finance-aggregator.db.transfers :as db-transfers]
   [finance-aggregator.utils :as u]
   [finance-aggregator.web.commands :as commands]
   [finance-aggregator.web.layout :as layout]
   [finance-aggregator.web.month :as month]
   [finance-aggregator.web.pages.transactions-view :as tv
    :refer [active-filters counts-fragment error-banner funnel-list match-modal page-body
            pagination-bar review-list review-modal review-status-message rollup-pane
            split-editor-modal sr-status tbody undo-redo-controls]]
   [finance-aggregator.web.render :as r]
   [finance-aggregator.web.view :as view]
   [finance-aggregator.web.view-state :as vs]
   [starfederation.datastar.clojure.adapter.http-kit :as hk]
   [starfederation.datastar.clojure.api :as d*]))

;; ---------------------------------------------------------------------------
;; Routes
;; ---------------------------------------------------------------------------

;; --- Request / SSE plumbing ------------------------------------------------
;; Small seams every route handler shares, so a handler reads as just its domain step
;; (load → command → patch) instead of re-spelling the Datastar SSE envelope each time.

(defn- path-id
  "Parse a tx-id path param (:id / :partner / :out / :in / :a / :b) as a long."
  [req k]
  (-> req :path-params k parse-long))

(defn- signals-month
  "The canonical YYYY-MM month string the Datastar signals carry."
  [signals]
  (month/serialize (month/parse (:month signals))))

(defn- patch!
  "Render a hiccup fragment and patch it into the live SSE response (morphed by id)."
  [sse hiccup]
  (d*/patch-elements! sse (r/render hiccup)))

(defn- sse-response
  "Open an SSE response, run `emit` (a fn of the sse channel) to patch fragments, then close.
   Collapses the hk/->sse-response + on-open + close-sse! envelope every handler shares."
  [req emit]
  (hk/->sse-response req {hk/on-open (fn [sse] (emit sse) (d*/close-sse! sse))}))

(defn- undo-labels
  "The current undo/redo command labels for `user` (nil = nothing) — the data the dumb
   undo-redo-controls view renders."
  [user]
  {:undo-label (commands/undo-label user)
   :redo-label (commands/redo-label user)})


(defn- patch-view!
  "Re-patch every filter-dependent fragment from a presented view-model: the faceted count
   badges, the three funnel option lists (faceted counts), and the active-filter chips."
  [sse {:keys [counts account-options institution-options category-options]} view-st]
  (patch! sse (counts-fragment counts))
  (patch! sse (funnel-list "account" account-options))
  (patch! sse (funnel-list "institution" institution-options))
  (patch! sse (funnel-list "category" category-options))
  (patch! sse (active-filters account-options institution-options category-options view-st)))


(defn page
  "GET / — full page. Seeds the view-state from the URL; a fresh load clears lingering."
  [{:keys [db-conn]}]
  (fn [req]
    (commands/clear-linger! auth/user-id)
    (let [m (month/parse (get-in req [:query-params "month"]))
          month-str (month/serialize m)
          txs (db-transactions/list-for-month db-conn month-str)
          categories (db-categories/list-all db-conn)
          ;; Reported balance deltas for the accounts active this month — the bank
          ;; side of the period-delta close readout. Computed once on full-page load
          ;; (computed deltas are edit-invariant: imported amounts are immutable and
          ;; splits/category/reviewed never move an account's total), so the panel
          ;; needs no SSE re-patching.
          account-eids (distinct (keep #(get-in % [:transaction/account :db/id]) txs))
          reported (db-snapshots/reported-deltas db-conn account-eids month-str)
          close (db-reconciliations/get-close db-conn month-str)
          view-st (vs/query->view-state (:query-params req))
          model (view/present txs view-st {:categories categories :reported reported :close close
                                           :manual-balances (db-snapshots/list-manual-balances db-conn)})]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body
       (layout/document
        {:title "Finance Aggregator"
         :islands ["combobox" "url" "grid-nav" "resize" "split-editor" "modal"]
         :signals (vs/client-signals view-st month-str (:result model) (:query-params req))}
        (page-body {:month m :stats (db-stats/entity-counts db-conn) :categories categories
                    :view-st view-st :model model :undo (undo-labels auth/user-id)
                    :empty? (empty? txs)}))})))

(defn rows
  "GET /transactions/rows — a pure view change: clear lingering, re-run the view, morph the tbody +
   pagination bar, patch $page back to the clamped value."
  [{:keys [db-conn]}]
  (fn [req]
    (commands/clear-linger! auth/user-id)
    (let [signals (r/read-signals req)
          txs (db-transactions/list-for-month db-conn (signals-month signals))
          view-st (vs/signals->view-state signals)
          model (view/present txs view-st {})
          result (:result model)]
      (sse-response req
       (fn [sse]
         ;; A view change (filter/sort/paginate) dismisses any error a prior action left up.
         (patch! sse (error-banner))
         ;; Announce the filtered result size to screen readers.
         (patch! sse (sr-status (str (:total result)
                                     (if (= 1 (:total result)) " transaction" " transactions"))))
         (patch! sse (tbody (:rows result)))
         (patch! sse (pagination-bar result))
         (patch-view! sse model view-st)
         (d*/patch-signals! sse (r/signals {:page (:page result)})))))))

(defn- edit-response
  "Shared SSE response after any edit/undo/redo: re-render the tbody (lingering the touched
   rows), the pagination bar, the server-authoritative counts, and the undo/redo controls.
   `:close-modal?` also re-patches #modal-root empty (a split save closes its modal);
   `:after-patch` (fn of sse) patches an extra fragment (the review modal refreshes its list
   in place instead of closing)."
  [db-conn req signals & {:keys [close-modal? after-patch]}]
  (let [user auth/user-id
        txs (db-transactions/list-for-month db-conn (signals-month signals))
        view-st (vs/signals->view-state signals)
        model (view/present txs view-st {:linger (commands/linger user)
                                         :categories (db-categories/list-all db-conn)})
        {:keys [stale-ids] :as result} (:result model)]
    (sse-response req
     (fn [sse]
       ;; A successful edit clears any error banner a prior failed action left up.
       (patch! sse (error-banner))
       ;; Announce the new counts to screen readers (the morphed badges are silent to them).
       (patch! sse (sr-status (review-status-message (:counts model))))
       (patch! sse (tbody (:rows result) stale-ids))
       (patch! sse (pagination-bar result))
       (patch-view! sse model view-st)
       (patch! sse (undo-redo-controls (undo-labels user)))
       ;; A recategorize/split moves money between rollup rows, so re-patch the whole-month pane.
       (patch! sse (rollup-pane (:rollup model)))
       (when close-modal? (patch! sse [:div {:id "modal-root"}]))
       (when after-patch (after-patch sse))
       (d*/patch-signals! sse (r/signals {:page (:page result)}))))))

(defn- error-response
  "SSE response for a failed mutation: surface the message in the dismissable error bar and
   close any open modal (so the bar isn't hidden behind a backdrop). The mutation threw
   before its command was recorded and before any datom was written (every db mutation
   validates up front), so the table already reflects the true state — nothing else to
   re-render."
  [req msg]
  (sse-response req
   (fn [sse]
     (patch! sse (error-banner msg))
     (patch! sse [:div {:id "modal-root"}]))))

(defn- handle-edit
  "Run an edit handler body (a thunk returning its SSE response); if a mutation throws an
   ex-info (validation / :conflict / :not-found), surface its message in the error bar
   instead of letting it escape to the JSON exception middleware — which a Datastar SSE
   client receives as a non-event-stream response, so no fragment patches and the user
   sees nothing change. Unexpected (non-ex-info) errors keep the default 500 + logging."
  [req thunk]
  (try (thunk)
       (catch clojure.lang.ExceptionInfo e
         (error-response req (ex-message e)))))

(defn toggle-reviewed
  "PUT /transactions/:id/reviewed/:v — record + apply a reviewed command, then re-render."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [tx-id (path-id req :id)
             after (= "true" (-> req :path-params :v))]
         (commands/apply! db-conn auth/user-id
                          {:type :set-reviewed :tx-id tx-id :before (not after) :after after
                           :label (if after "Marked reviewed" "Marked unreviewed")})
         (edit-response db-conn req (r/read-signals req)))))))

(defn set-description
  "PUT /transactions/:id/description — record + apply an inline-description-edit command (the new
   value rides in the $editValue courier signal), then re-render."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [tx-id (path-id req :id)
             signals (r/read-signals req)
             before (db-transactions/user-description db-conn tx-id)
             after (str/trim (or (:editValue signals) ""))]
         (commands/apply! db-conn auth/user-id
                          {:type :set-description :tx-id tx-id :before before :after after
                           :label "Edited description"})
         (edit-response db-conn req signals))))))

(defn set-category
  "PUT /transactions/:id/category — record + apply an :update-category command (the chosen id rides
   in the $catValue courier; empty = clear), then re-render (counts move)."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [tx-id (path-id req :id)
             signals (r/read-signals req)
             before (db-transactions/category-id db-conn tx-id)
             after (vs/parse-category-value (:catValue signals))]
         (commands/apply! db-conn auth/user-id
                          {:type :set-category :tx-id tx-id :before before :after after
                           :label "Recategorized"})
         (edit-response db-conn req signals))))))

(defn split-editor
  "GET /transactions/:id/split-editor — render the split-editor modal for one transaction into
   #modal-root. A pure read (no command, no lingering change)."
  [{:keys [db-conn]}]
  (fn [req]
    (let [tx (db-transactions/by-id db-conn (path-id req :id))]
      (sse-response req (fn [sse] (patch! sse (split-editor-modal tx (view/split-editor-seed tx))))))))

(defn set-splits
  "PUT /transactions/:id/splits — record + apply a :set-splits command (the new parts ride in
   the $splitValue courier as JSON; [] un-splits), then re-render and close the modal. Captures
   the prior parts as :before so undo restores them."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [tx-id (path-id req :id)
             signals (r/read-signals req)
             before (db-transactions/current-splits db-conn tx-id)
             after (vs/parse-splits-value (:splitValue signals))]
         (commands/apply! db-conn auth/user-id
                          {:type :set-splits :tx-id tx-id :before before :after after
                           :label (cond (empty? after)  "Un-split"
                                        (seq before)     "Edited split"
                                        :else            "Split transaction")})
         (edit-response db-conn req signals :close-modal? true))))))

(defn match-editor
  "GET /transactions/:id/match — render the transfer match/unmatch modal into #modal-root.
   A pure read (matched → partner + Unmatch; unmatched → match candidates)."
  [{:keys [db-conn]}]
  (fn [req]
    (let [tx-id (path-id req :id)
          tx (db-transactions/by-id db-conn tx-id)
          candidates (when-not (:transaction/transfer-pair tx)
                       (db-transfers/match-candidates db-conn tx-id))]
      (sse-response req (fn [sse] (patch! sse (match-modal tx candidates)))))))

(defn confirm-match
  "PUT /transactions/:id/match/:partner — link the two legs as a transfer (a :set-match command,
   so undo unlinks), then re-render and close the modal."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [tx-id (path-id req :id)
             partner (path-id req :partner)]
         (commands/apply! db-conn auth/user-id
                          {:type :set-match :tx-id tx-id :before nil :after partner :label "Matched transfer"})
         (edit-response db-conn req (r/read-signals req) :close-modal? true))))))

(defn unmatch
  "PUT /transactions/:id/unmatch — remove the transfer link (a :set-match command capturing the
   prior partner as :before, so undo relinks), then re-render and close the modal."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [tx-id (path-id req :id)
             before (get-in (db-transactions/by-id db-conn tx-id) [:transaction/transfer-pair :db/id])]
         (commands/apply! db-conn auth/user-id
                          {:type :set-match :tx-id tx-id :before before :after nil :label "Unmatched transfer"})
         (edit-response db-conn req (r/read-signals req) :close-modal? true))))))

(defn review-transfers
  "GET /transactions/review-transfers — render the bulk transfer-review modal (auto-suggested
   pairs) into #modal-root."
  [{:keys [db-conn]}]
  (fn [req]
    (let [suggestions (db-transfers/suggest-matches db-conn)]
      (sse-response req (fn [sse] (patch! sse (review-modal suggestions)))))))

(defn- refresh-review-list!
  "Re-patch #review-list with the recomputed suggestions (after a confirm/reject, the acted-on
   pair drops out and the modal stays open)."
  [db-conn sse]
  (patch! sse (review-list (db-transfers/suggest-matches db-conn))))

(defn review-confirm
  "PUT /transactions/review/:out/confirm/:in — confirm a suggested pair (:set-match command),
   then re-render the table + refresh the suggestion list in place."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [out (path-id req :out)
             in (path-id req :in)]
         (commands/apply! db-conn auth/user-id
                          {:type :set-match :tx-id out :before nil :after in :label "Matched transfer"})
         (edit-response db-conn req (r/read-signals req)
                        :after-patch (fn [sse] (refresh-review-list! db-conn sse))))))))

(defn review-reject
  "PUT /transactions/review/:a/reject/:b — reject a suggested pair (:reject-match command, so
   undo un-rejects), then re-render the table + refresh the suggestion list in place."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [a (path-id req :a)
             b (path-id req :b)]
         (commands/apply! db-conn auth/user-id
                          {:type :reject-match :tx-id a :partner b :before false :after true :label "Rejected transfer"})
         (edit-response db-conn req (r/read-signals req)
                        :after-patch (fn [sse] (refresh-review-list! db-conn sse))))))))

(defn undo
  "POST /transactions/undo — reverse the last edit (keeping the row lingering), then re-render."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (commands/undo! db-conn auth/user-id)
       (edit-response db-conn req (r/read-signals req))))))

(defn redo
  "POST /transactions/redo — re-apply the last undone edit, then re-render."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (commands/redo! db-conn auth/user-id)
       (edit-response db-conn req (r/read-signals req))))))

;; --- Monthly close ---------------------------------------------------------
;; The reconciliation panel (#reconciliation) is re-patched by its own actions —
;; recording a statement balance, closing, reopening — from a model rebuilt off
;; current db state. It lives outside #category-rollup, so edit re-patches don't
;; touch it and these don't touch the table.

(defn- parse-money
  "Parse a courier money string to bigdec, or nil when blank/unparseable."
  [s]
  (when-let [t (some-> s str str/trim not-empty)]
    (try (bigdec t) (catch NumberFormatException _ nil))))

(defn- close-model-for
  "Rebuild the monthly-close panel model for `month` from current db state (the
   month's txs, reported deltas, rollup net, and the persisted close event)."
  [db-conn month]
  (let [txs (db-transactions/list-for-month db-conn month)
        categories (db-categories/list-all db-conn)
        account-eids (distinct (keep #(get-in % [:transaction/account :db/id]) txs))
        reported (db-snapshots/reported-deltas db-conn account-eids month)]
    (view/month-close txs {:reconciliation (view/reconcile-month txs reported)
                           :close (db-reconciliations/get-close db-conn month)
                           :net-now (:grand-total (view/category-rollup txs categories))
                           :manual-balances (db-snapshots/list-manual-balances db-conn)})))

(defn- patch-close-panel!
  "Re-render the reconciliation panel for `month` and morph it into #reconciliation."
  [db-conn req month]
  (sse-response req (fn [sse] (patch! sse (tv/close-panel (close-model-for db-conn month))))))

(defn statement-editor
  "GET /transactions/statement-modal — render the statement-balance modal into
   #modal-root and seed its form signals. The date defaults to the viewed month's end
   (statements usually close there; the user picks the real closing date); the account
   preselects from ?account= (a drifting row's 'Set balance'), else the first account.
   A pure read — lists ALL accounts so one with no activity this month still reconciles."
  [{:keys [db-conn]}]
  (fn [req]
    (let [signals (r/read-signals req)
          month (signals-month signals)
          accounts (->> (db-accounts/list-with-institution db-conn)
                        (map (fn [a] {:eid (:db/id a) :name (:account/external-name a)}))
                        (sort-by :name))
          default-date (str (u/date->local-date (db-snapshots/month-end-date month)))
          selected (or (some-> (get-in req [:query-params "account"]) parse-long)
                       (:eid (first accounts)))]
      (sse-response req
       (fn [sse]
         (d*/patch-signals! sse (r/signals {:stmtAccount (str selected) :stmtDate default-date :stmtBalance ""}))
         (patch! sse (tv/statement-modal accounts default-date selected)))))))

(defn set-statement-balance
  "POST /transactions/statement — record a user-entered bank statement balance (the
   $stmtAccount / $stmtDate / $stmtBalance couriers) as a dated :manual snapshot, then
   re-patch the reconciliation panel. Surfaces an error when a field is missing/blank."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [signals (r/read-signals req)
             month (signals-month signals)
             account-eid (some-> (:stmtAccount signals) str not-empty parse-long)
             date (some-> (:stmtDate signals) str not-empty u/string->date)
             balance (parse-money (:stmtBalance signals))]
         (if (and account-eid date balance)
           (do (db-snapshots/record-manual-balance! db-conn account-eid date balance)
               (sse-response req
                (fn [sse]
                  (patch! sse (tv/close-panel (close-model-for db-conn month)))
                  (patch! sse [:div {:id "modal-root"}]))))
           (error-response req "Enter an account, date, and balance.")))))))

(defn delete-statement-balance
  "POST /transactions/statement/delete — remove a recorded :manual statement balance
   (its snapshot id rides in the $stmtDel courier), then re-patch the panel."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [signals (r/read-signals req)
             month (signals-month signals)]
         (when-let [id (some-> (:stmtDel signals) str not-empty)]
           (db-snapshots/delete-manual-balance! db-conn id))
         (patch-close-panel! db-conn req month))))))

(defn close-month
  "POST /transactions/close — freeze the current month's category totals and lock it.
   Refuses (surfaces an error, no write) unless the month is ready: everything
   reviewed and categorized and every account's balance reconciled. Re-patches the panel."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [month (signals-month (r/read-signals req))]
         (if-not (get-in (close-model-for db-conn month) [:gate :ready?])
           (error-response req "This month isn't ready to close yet.")
           (let [txs (db-transactions/list-for-month db-conn month)
                 categories (db-categories/list-all db-conn)
                 {:keys [income expenses transfers grand-total]} (view/category-rollup txs categories)]
             (db-reconciliations/close-month!
              db-conn month
              {:income (:total income) :expenses (:total expenses)
               :transfers (:total transfers) :net grand-total}
              (java.util.Date.))
             (patch-close-panel! db-conn req month))))))))

(defn reopen-month
  "POST /transactions/reopen — unlock the current month, then re-patch the panel."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [month (signals-month (r/read-signals req))]
         (db-reconciliations/reopen-month! db-conn month)
         (patch-close-panel! db-conn req month))))))

;; --- Manual transactions ---------------------------------------------------
;; Add a transaction the bank feed didn't import (cash, a missed charge). A modal
;; collects the fields; the created row is a first-class :manual transaction that then
;; behaves like any imported one. Non-undoable (delete is the reversal); like the
;; statement actions it re-patches #reconciliation, since a new/removed row changes the
;; month's computed deltas and completeness gate.

(defn- default-txn-date
  "yyyy-MM-dd the add-transaction modal seeds: today when viewing the current month
   (the common case), else the viewed month's last day — so a manual entry lands in the
   month being reconciled rather than on today's date."
  [month]
  (let [today (str (u/date->local-date (java.util.Date.)))]
    (if (= month (subs today 0 7))
      today
      (str (u/date->local-date (db-snapshots/month-end-date month))))))

(defn add-transaction-editor
  "GET /transactions/manual/new — render the add-transaction modal + seed its signals.
   A pure read: lists all accounts + categories; the date defaults per default-txn-date."
  [{:keys [db-conn]}]
  (fn [req]
    (let [signals (r/read-signals req)
          month (signals-month signals)
          accounts (->> (db-accounts/list-with-institution db-conn)
                        (map (fn [a] {:eid (:db/id a) :name (:account/external-name a)}))
                        (sort-by :name))
          categories (db-categories/list-all db-conn)
          default-date (default-txn-date month)
          selected (:eid (first accounts))]
      (sse-response req
       (fn [sse]
         (d*/patch-signals! sse (r/signals {:txAccount (str selected) :txDir "out" :txAmount ""
                                            :txDate default-date :txPayee "" :txDesc "" :txCategory ""}))
         (patch! sse (tv/add-transaction-modal accounts categories default-date selected)))))))

(defn create-manual
  "POST /transactions/manual — create a manual transaction from the modal signals. The
   amount is entered as a positive magnitude + a money-out/-in direction; the canonical
   sign is derived here (out → negative). Re-renders the table, re-patches the close
   panel, and closes the modal. Surfaces an error when a required field is missing."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [signals     (r/read-signals req)
             month       (signals-month signals)
             account-eid (some-> (:txAccount signals) str not-empty parse-long)
             date        (some-> (:txDate signals) str not-empty u/string->date)
             magnitude   (some-> (parse-money (:txAmount signals)) .abs)
             amount      (when magnitude (if (= "in" (:txDir signals)) magnitude (.negate magnitude)))
             category-id (vs/parse-category-value (:txCategory signals))]
         (if (and account-eid date amount)
           (do (db-transactions/create-manual! db-conn auth/user-id
                                               {:account-eid account-eid :amount amount :date date
                                                :payee (some-> (:txPayee signals) str)
                                                :description (some-> (:txDesc signals) str)
                                                :category-id category-id})
               (edit-response db-conn req signals :close-modal? true
                              :after-patch (fn [sse]
                                             (patch! sse (tv/close-panel (close-model-for db-conn month))))))
           (error-response req "Enter an account, amount, and date.")))))))

(defn delete-transaction-editor
  "GET /transactions/:id/manual/delete — render the delete-confirm dialog (a pure read)."
  [{:keys [db-conn]}]
  (fn [req]
    (let [tx (db-transactions/by-id db-conn (path-id req :id))]
      (sse-response req (fn [sse] (patch! sse (tv/delete-transaction-modal tx)))))))

(defn delete-manual
  "POST /transactions/:id/manual/delete — delete a manual transaction (the data layer
   guards on provider :manual), re-render the table, re-patch the close panel, and close
   the modal."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [tx-id   (path-id req :id)
             signals (r/read-signals req)
             month   (signals-month signals)]
         (db-transactions/delete-manual! db-conn tx-id)
         ;; The row is gone — drop any undo/redo command that would replay against it
         ;; (a matched/split manual row would otherwise jam the stack on undo).
         (commands/forget! auth/user-id tx-id)
         (edit-response db-conn req signals :close-modal? true
                        :after-patch (fn [sse]
                                       (patch! sse (tv/close-panel (close-model-for db-conn month))))))))))

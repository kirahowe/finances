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
   [finance-aggregator.db.categories :as db-categories]
   [finance-aggregator.db.stats :as db-stats]
   [finance-aggregator.db.transactions :as db-transactions]
   [finance-aggregator.db.transfers :as db-transfers]
   [finance-aggregator.web.commands :as commands]
   [finance-aggregator.web.layout :as layout]
   [finance-aggregator.web.month :as month]
   [finance-aggregator.web.pages.transactions-view :as tv
    :refer [active-filters category-options counts-fragment empty-state error-banner
            funnel-list funnel-popovers match-modal pagination-bar review-list review-modal
            review-status-message rollup-pane row-actions-menu split-editor-modal
            sr-status table tbody toolbar undo-key-js undo-redo-controls url-sync]]
   [finance-aggregator.web.render :as r]
   [finance-aggregator.web.shell :as shell]
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


(defn- patch-filter-feedback!
  "Re-patch every filter-dependent fragment after a view change or edit: the faceted count
   badges, the three funnel option lists (faceted counts), and the active-filter chips."
  [sse txs view-st]
  (let [acct (view/account-options txs view-st)
        inst (view/institution-options txs view-st)
        cat  (view/category-funnel-options txs view-st)]
    (patch! sse (counts-fragment (view/facet-counts txs view-st)))
    (patch! sse (funnel-list "account" acct))
    (patch! sse (funnel-list "institution" inst))
    (patch! sse (funnel-list "category" cat))
    (patch! sse (active-filters acct inst cat view-st))))


(defn page
  "GET / — full page. Seeds the view-state from the URL; a fresh load clears lingering."
  [{:keys [db-conn]}]
  (fn [req]
    (commands/clear-linger! auth/user-id)
    (let [m (month/parse (get-in req [:query-params "month"]))
          month-str (month/serialize m)
          txs (db-transactions/list-for-month db-conn month-str)
          stats (db-stats/entity-counts db-conn)
          categories (db-categories/list-all db-conn)
          view-st (vs/query->view-state (:query-params req))
          result (view/view txs view-st)
          counts (view/facet-counts txs view-st)
          acct (view/account-options txs view-st)
          inst (view/institution-options txs view-st)
          cat  (view/category-funnel-options txs view-st)]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body
       (layout/document
        {:title "Finance Aggregator"
         :islands ["combobox" "url" "grid-nav" "resize" "split-editor" "modal"]
         :signals (vs/client-signals view-st month-str result (:query-params req))}
        [:div.container.container--workspace {"data-on:keydown__window" undo-key-js}
         (shell/masthead {:active :transactions :stats stats})
         (error-banner)
         (sr-status)
         [:div.transactions-layout
          [:div.card
           (toolbar m counts (undo-labels auth/user-id))
           (active-filters acct inst cat view-st)
           (if (empty? txs)
             (empty-state)
             (list (table (:rows result)) (pagination-bar result)))]
          (rollup-pane (view/category-rollup txs categories))]
         (when (seq txs) (list (funnel-popovers acct inst cat) (row-actions-menu)))
         (category-options categories)
         (url-sync)
         ;; Patched by GET /transactions/:id/split-editor; emptied again on close/save.
         [:div {:id "modal-root"}]])})))

(defn rows
  "GET /transactions/rows — a pure view change: clear lingering, re-run the view, morph the tbody +
   pagination bar, patch $page back to the clamped value."
  [{:keys [db-conn]}]
  (fn [req]
    (commands/clear-linger! auth/user-id)
    (let [signals (r/read-signals req)
          txs (db-transactions/list-for-month db-conn (signals-month signals))
          view-st (vs/signals->view-state signals)
          result (view/view txs view-st)]
      (sse-response req
       (fn [sse]
         ;; A view change (filter/sort/paginate) dismisses any error a prior action left up.
         (patch! sse (error-banner))
         ;; Announce the filtered result size to screen readers.
         (patch! sse (sr-status (str (:total result)
                                     (if (= 1 (:total result)) " transaction" " transactions"))))
         (patch! sse (tbody (:rows result)))
         (patch! sse (pagination-bar result))
         (patch-filter-feedback! sse txs view-st)
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
        {:keys [stale-ids] :as result} (view/view-with-linger txs view-st (commands/linger user))
        counts (view/facet-counts txs view-st)]
    (sse-response req
     (fn [sse]
       ;; A successful edit clears any error banner a prior failed action left up.
       (patch! sse (error-banner))
       ;; Announce the new counts to screen readers (the morphed badges are silent to them).
       (patch! sse (sr-status (review-status-message counts)))
       (patch! sse (tbody (:rows result) stale-ids))
       (patch! sse (pagination-bar result))
       (patch-filter-feedback! sse txs view-st)
       (patch! sse (undo-redo-controls (undo-labels user)))
       ;; A recategorize/split moves money between rollup rows, so re-patch the whole-month pane.
       (patch! sse (rollup-pane (view/category-rollup txs (db-categories/list-all db-conn))))
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

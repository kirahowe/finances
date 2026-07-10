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
   [finance-aggregator.data.ledger :as ledger]
   [finance-aggregator.db.accounts :as db-accounts]
   [finance-aggregator.db.categories :as db-categories]
   [finance-aggregator.db.reconciliations :as db-reconciliations]
   [finance-aggregator.db.snapshots :as db-snapshots]
   [finance-aggregator.db.statements :as db-statements]
   [finance-aggregator.db.stats :as db-stats]
   [finance-aggregator.db.transactions :as db-transactions]
   [finance-aggregator.db.transfers :as db-transfers]
   [finance-aggregator.utils :as u]
   [finance-aggregator.web.accounts :as web-accounts]
   [finance-aggregator.web.commands :as commands]
   [finance-aggregator.web.layout :as layout]
   [finance-aggregator.web.month :as month]
   [finance-aggregator.web.period :as period]
   [finance-aggregator.web.pages.transactions-view :as tv
    :refer [active-filters counts-fragment error-banner funnel-list match-modal page-body
            pagination-bar posted-date-modal review-modal review-status-message
            rollup-pane split-editor-modal sr-status suggestion-row-stale tbody
            undo-redo-controls]]
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

(defn- signals-period
  "The PERIOD the live Datastar signals carry — $month/$from/$to, straight off the signals
   map read on every @get/@put (mirrors the URL's month-XOR-range shape; see web.period/parse)."
  [signals]
  (period/parse {:month (:month signals) :from (:from signals) :to (:to signals)}))

(defn- signals-month
  "The canonical YYYY-MM month string the Datastar signals carry — ALWAYS the containing
   month, even mid-range-view (period/containing-month), so the month-bound handlers below
   (reconcile/statement/close/posted-date/manual-add) keep working unchanged: in month view
   this is identical to today; in range view they're unreachable anyway (the panel that
   would trigger them renders as a quiet back-to-month note instead — see close-or-note)."
  [signals]
  (month/serialize (period/containing-month (signals-period signals))))

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
   badges, the three funnel option lists (faceted counts), and the active-filter chips.
   `lens?` (whether the statement lens is narrowing the table — the caller alone sees the live
   $reconFrom/$reconTo signals it rides on) feeds the chip row's Clear-all visibility."
  [sse {:keys [counts account-options institution-options category-options]} view-st lens?]
  (patch! sse (counts-fragment counts))
  (patch! sse (funnel-list "account" account-options))
  (patch! sse (funnel-list "institution" institution-options))
  (patch! sse (funnel-list "category" category-options))
  (patch! sse (active-filters account-options institution-options category-options view-st
                             (vs/clear-all-active? view-st lens?))))

;; --- Courier / money parsers (shared by the reconcile + modal handlers) ----

(defn- parse-money
  "Parse a courier money string to bigdec, or nil when blank/unparseable."
  [s]
  (when-let [t (some-> s str str/trim not-empty)]
    (try (bigdec t) (catch NumberFormatException _ nil))))

(defn- courier-eid
  "Parse a courier signal carrying an entity id (blank/nil → nil)."
  [v]
  (some-> v str not-empty parse-long))

(defn- courier-date
  "Parse a courier signal carrying a yyyy-MM-dd date (blank/nil → nil) to a Date.
   An unparseable value — e.g. a fat-fingered 5-digit year, which a native date
   input happily holds but ISO parsing rejects — surfaces as :bad-request (the
   dismissable error bar, via handle-edit) instead of a raw 500."
  [v]
  (when-let [s (some-> v str not-empty)]
    (try (u/string->date s)
         (catch Exception _
           (throw (ex-info "That date isn't valid." {:type :bad-request}))))))

;; --- Monthly-close panel model --------------------------------------------
;; Assembled in the handler (not web.view/present) because it needs the account filter + the
;; snapshot history: the all-accounts overview, or a focused single-account card when the
;; table is filtered to one account. Every panel re-patch (full load, filter/edit, reconcile,
;; close/reopen) rebuilds it from current db state.

(defn- focus-account-eid
  "The single account the panel drills into (from `view-st`), or nil — exactly one account
   funnel selection means the table is filtered to one account, so the panel focuses it."
  [view-st]
  (let [accts (:accounts view-st)]
    (when (= 1 (count accts)) (first accts))))

(defn- statement-models
  "The account's statements overlapping `month`, each annotated with its period-delta verdict
   (over the account's transactions in the statement's span). The statement half of the focused
   card's period list."
  [db-conn account-eid month]
  (let [{:keys [start-date end-date]} (u/month-date-range month)]
    (mapv (fn [s]
            (-> (view/reconcile-statement
                 s (db-transactions/list-for-account-range
                    db-conn account-eid
                    (ledger/statement-opening-boundary (:start-date s)) (:end-date s)))
                (assoc :start-iso (str (u/date->local-date (:start-date s)))
                       :end-iso   (str (u/date->local-date (:end-date s))))))
          (db-statements/list-overlapping db-conn account-eid start-date end-date))))

(defn- statements-by-account
  "{account-eid [statement…]} for every account in `account-eids` that has at least one
   statement overlapping `month` — the shape reconcile-month folds into its per-account
   coverage check. Accounts with no statements are simply absent (not an empty vector), so
   `(seq statements)`/`(get … eid [])` downstream reads as 'no statements' either way."
  [db-conn account-eids month]
  (into {} (for [eid account-eids
                 :let [ss (statement-models db-conn eid month)]
                 :when (seq ss)]
             [eid ss])))

(defn- close-model-for
  "Rebuild the monthly-close panel model for `month` from current db state (the month's txs,
   reported deltas, rollup net, and the persisted close event). Coverage-strict: an account's
   overview status folds in BOTH its month-boundary balance and its statements, so a credit
   card whose statements all tie out reconciles even without month-end balances entered. When
   the table is filtered to a single account (`view-st`), attach the :focus card — that
   account's month-boundary opening/closing card (period-delta verdict from the snapshot
   history) plus its statements overlapping the month (each with its own verdict), and the
   same coverage headline as the overview, scoped to the one account."
  [db-conn month view-st]
  (let [txs (db-transactions/list-for-month db-conn month)
        categories (db-categories/list-all db-conn)
        account-eids (distinct (keep #(get-in % [:transaction/account :db/id]) txs))
        reported (db-snapshots/reported-deltas db-conn account-eids month)
        month-span {:start (db-snapshots/opening-date month) :end (db-snapshots/month-end-date month)}
        stmts (statements-by-account db-conn account-eids month)
        base (view/month-close txs {:reconciliation (view/reconcile-month txs reported month-span stmts)
                                    :close (db-reconciliations/get-close db-conn month)
                                    :net-now (:grand-total (view/category-rollup txs categories))})
        focus-eid (focus-account-eid view-st)]
    (cond-> base
      focus-eid
      (assoc :focus
             (let [{:keys [opening closing]} (db-snapshots/boundary-balances db-conn focus-eid month)]
               (view/focus-close txs {:account-eid  focus-eid
                                      :opening      opening
                                      :closing      closing
                                      :opening-date (db-snapshots/opening-date month)
                                      :closing-date (db-snapshots/month-end-date month)
                                      :statements   (get stmts focus-eid [])}))))))

(defn- recon-signals
  "The focused card's opening/closing prefill signals for a panel `model` (blank in overview
   mode), plus the month-end disclosure's default open state (`:_reconMonthOpen`). Seeded
   whenever the panel is entered/saved, so a drill shows the balances on file. The disclosure
   collapses ONLY when the account is already fully reconciled by statements alone (no
   month-boundary balances on file needed) — otherwise it opens, since that's where the
   account still needs attention."
  [model]
  (let [f (:focus model)
        s #(if (some? %) (str %) "")
        covered-by-statements? (and f
                                    (= :reconciled (get-in f [:coverage :status]))
                                    (seq (filter #(= :reconciled (:status %)) (:statements f)))
                                    (nil? (:opening f)) (nil? (:closing f)))]
    {:reconOpen (s (:opening f)) :reconClose (s (:closing f))
     :_reconMonthOpen (not covered-by-statements?)}))

(defn- reconcile-range
  "The active reconcile span {:account :from :to} narrowing the table to a statement — both
   $reconFrom/$reconTo set AND exactly one account focused — else nil. The statement lens is a
   MONTH-VIEW-ONLY affordance (it opens from the monthly-close panel, which range view replaces
   with a quiet note — see close-or-note), so every caller guards this with `(period/month? p)`
   first; a range view always treats it as inactive, regardless of what's still sitting in the
   $reconFrom/$reconTo couriers from a prior month-view visit."
  [signals view-st]
  (let [from (courier-date (:reconFrom signals))
        to   (courier-date (:reconTo signals))
        acct (focus-account-eid view-st)]
    (when (and from to acct) {:account acct :from from :to to})))

(defn- table-and-facets
  "The presented view-model for a view change. The FUNNEL OPTION LISTS and the ROLLUP are
   always computed over the whole PERIOD (the calendar month, or the analysis-lens range) — so
   the account funnel never collapses to the focused account (which would clear the
   $filter.account binding that drives the focus) and those figures stay stable. The reconcile
   narrowing is a LENS, not a real filter, and applies in MONTH VIEW ONLY (a range period has no
   monthly-close panel to open it from): :result AND :counts (the toolbar badges —
   scope/uncategorized/hide-transfers) are swapped for the narrowed span slice when a reconcile
   range is active (a single account + $reconFrom/$reconTo, which may cross a calendar-month
   boundary), so the badges the user sees always describe the rows on screen, not the whole
   period behind the lens."
  [db-conn p signals view-st present-opts]
  (let [{:keys [start-date end-date]} (period/date-range p)
        period-txs (db-transactions/list-for-span db-conn start-date end-date)
        model (view/present period-txs view-st present-opts)]
    (if-let [{:keys [account from to]} (and (period/month? p) (reconcile-range signals view-st))]
      ;; The narrowing lens shows the statement's printed span [from, to] inclusive; shift the
      ;; exclusive lower boundary back a day so from's own activity is in the slice (the header
      ;; dateline keeps the unshifted printed `from`). Mirrors the reconcile sum in statement-models.
      (let [slice (db-transactions/list-for-account-range
                   db-conn account (ledger/statement-opening-boundary from) to)
            slice-model (view/present slice view-st (select-keys present-opts [:linger]))]
        (assoc model :result (:result slice-model) :counts (:counts slice-model)))
      model)))

(defn- close-or-note
  "The monthly-close panel model for the viewed period `p`: the real close-model-for a month
   view builds today, keyed by month; or, for a range (an analysis lens layered over calendar
   months — monthly close doesn't apply to an arbitrary span), a quiet {:range-back {:month-str
   :label}} note pointing back to the range's containing month, which close-panel renders as a
   Back link instead of the real panel."
  [db-conn p view-st]
  (if (period/month? p)
    (close-model-for db-conn (month/serialize p) view-st)
    (let [cm (period/containing-month p)]
      {:range-back {:month-str (month/serialize cm) :label (month/display cm)}})))


(defn page
  "GET / — full page. Seeds the view-state (and the viewed PERIOD — a month, or a from/to
   analysis range) from the URL; a fresh load clears lingering."
  [{:keys [db-conn]}]
  (fn [req]
    (commands/clear-linger! auth/user-id)
    (let [qp (:query-params req)
          p (period/parse {:month (get qp "month") :from (get qp "from") :to (get qp "to")})
          {:keys [start-date end-date]} (period/date-range p)
          txs (db-transactions/list-for-span db-conn start-date end-date)
          categories (db-categories/list-all db-conn)
          view-st (vs/query->view-state qp)
          ;; The reconciliation panel is assembled here (it needs the account filter + the
          ;; snapshot history): the all-accounts overview, or the focused card when the URL
          ;; filters to a single account — in range view, a quiet note instead (close-or-note).
          close-model (close-or-note db-conn p view-st)
          model (assoc (view/present txs view-st {:categories categories}) :close close-model)]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body
       (layout/document
        {:title "Finance Aggregator"
         :islands ["combobox" "url" "grid-nav" "resize" "split-editor" "modal"]
         :signals (merge (vs/client-signals view-st
                                            (merge (period/signal-seed p) (period/picker-seed p))
                                            (:result model) qp)
                         (recon-signals close-model))}
        ;; :today is the page's one clock read (UTC — the same convention as month/current),
        ;; passed down so the period picker's quick links + current-month ring stay pure views.
        (page-body {:period p :today (java.time.LocalDate/now java.time.ZoneOffset/UTC)
                    :stats (db-stats/entity-counts db-conn) :categories categories
                    :view-st view-st :model model :undo (undo-labels auth/user-id)
                    :empty? (empty? txs)}))})))

(defn picker-months
  "GET /transactions/period-picker/months — re-render the period picker's month-grid pane
   for the ?year the stepper buttons bake in server-side (fallback: the viewed period's
   containing year off the live signals). SSE-patches the #period-picker-months fragment;
   the fragment IS the year state machine — each response bakes year∓1 into the next
   steppers' @gets, so no year signal exists. No db access: pure calendar math over the
   viewed period + the current calendar month (month/current — the handler's clock read;
   the view stays dumb)."
  [_deps]
  (fn [req]
    (let [signals (r/read-signals req)
          p (signals-period signals)
          year (or (some-> (get-in req [:query-params "year"]) parse-long)
                   (:year (period/containing-month p)))]
      (sse-response req
       (fn [sse]
         (patch! sse (tv/period-picker-months year p (month/current))))))))

(defn rows
  "GET /transactions/rows — a pure view change: clear lingering, re-run the view, morph the tbody +
   pagination bar, patch $page back to the clamped value. Also re-renders #reconciliation so the
   panel stays in sync with the account filter: filtering to one account drills the panel into that
   account's focused card, clearing it returns to the overview (the drill/back actions ARE this)."
  [{:keys [db-conn]}]
  (fn [req]
    (commands/clear-linger! auth/user-id)
    (let [signals (r/read-signals req)
          p (signals-period signals)
          view-st (vs/signals->view-state signals)
          ;; :categories keeps the category funnel in user sort-order on every re-patch (see
          ;; category-funnel-options); present also computes a whole-period rollup this response
          ;; never reads — accepted at single-user scale rather than splitting present's shape.
          model (table-and-facets db-conn p signals view-st
                                  {:categories (db-categories/list-all db-conn)})
          close-model (close-or-note db-conn p view-st)
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
         ;; Keep the header dateline in sync with the narrowing lens: show the statement span
         ;; when narrowed to one (month view only — range view has no lens), else fall back to
         ;; the whole period's own label.
         (let [{:keys [from to]} (when (period/month? p) (reconcile-range signals view-st))]
           (patch! sse (tv/period-display (tv/period-label p from to))))
         (patch-view! sse model view-st (boolean (and (period/month? p) (reconcile-range signals view-st))))
         (patch! sse (tv/close-panel close-model))
         (d*/patch-signals! sse (r/signals (merge {:page (:page result)}
                                                  (recon-signals close-model)))))))))

(defn- edit-response
  "Shared SSE response after any edit/undo/redo: re-render the tbody (lingering the touched
   rows), the pagination bar, the server-authoritative counts, and the undo/redo controls.
   `:close-modal?` also re-patches #modal-root empty (a split save closes its modal);
   `:after-patch` (fn of sse) patches an extra fragment (the review modal refreshes its list
   in place instead of closing)."
  [db-conn req signals & {:keys [close-modal? after-patch]}]
  (let [user auth/user-id
        p (signals-period signals)
        view-st (vs/signals->view-state signals)
        model (table-and-facets db-conn p signals view-st
                                {:linger (commands/linger user)
                                 :categories (db-categories/list-all db-conn)})
        close-model (close-or-note db-conn p view-st)
        {:keys [stale-ids] :as result} (:result model)]
    (sse-response req
     (fn [sse]
       ;; A successful edit clears any error banner a prior failed action left up.
       (patch! sse (error-banner))
       ;; Announce the new counts to screen readers (the morphed badges are silent to them).
       (patch! sse (sr-status (review-status-message (:counts model))))
       (patch! sse (tbody (:rows result) stale-ids))
       (patch! sse (pagination-bar result))
       (patch-view! sse model view-st (boolean (and (period/month? p) (reconcile-range signals view-st))))
       (patch! sse (undo-redo-controls (undo-labels user)))
       ;; A recategorize/split moves money between rollup rows, so re-patch the whole-period pane.
       (patch! sse (rollup-pane (:rollup model)))
       ;; Keep the reconciliation panel live too: reconciling/categorizing moves the completeness
       ;; gate, and adding/removing a manual row moves an account's tracked delta + focused verdict.
       ;; (No recon prefill re-seed here — that would clobber unsaved typing in the balance fields.)
       (patch! sse (tv/close-panel close-model))
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

(defn toggle-reconciled
  "PUT /transactions/:id/reconciled/:v — record + apply a reconciled command, then re-render."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [tx-id (path-id req :id)
             after (= "true" (-> req :path-params :v))]
         (commands/apply! db-conn auth/user-id
                          {:type :set-reconciled :tx-id tx-id :before (not after) :after after
                           :label (if after "Marked reconciled" "Marked unreconciled")})
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
  "GET /transactions/:id/split-editor — render the split-editor modal into #modal-root.
   A pure read (no command, no lingering change). When :id names a split PART, the editor
   opens on its PARENT (db-transactions/split-editor-root) — defensive, so every path in
   lands on the family's parent."
  [{:keys [db-conn]}]
  (fn [req]
    (let [tx (db-transactions/split-editor-root db-conn (path-id req :id))]
      (sse-response req (fn [sse] (patch! sse (split-editor-modal tx (view/split-editor-seed tx))))))))

(defn set-splits
  "PUT /transactions/:id/splits — record + apply a :set-splits command (the new parts ride in
   the $splitValue courier as JSON; [] un-splits), then re-render and close the modal. Captures
   the prior parts as :before so undo restores them, and forgets any part the edit just
   retracted (present in :before, absent from :after) — a stale command against a
   retracted part would otherwise jam the undo/redo stack."
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
         (run! (partial commands/forget! auth/user-id) (commands/removed-split-part-ids before after))
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

(defn- match-command
  "The :set-match command linking `tx-id` to `partner`. When exactly one leg is categorized
   it carries a :category-effect copying that category onto the blank leg — the decision is
   commands/category-effect; this glue only pulls the two legs' current category ids — so a
   single undo reverses both the link and the copy."
  [db-conn tx-id partner]
  (let [effect (commands/category-effect
                {:tx-id tx-id :category-id (db-transactions/category-id db-conn tx-id)}
                {:tx-id partner :category-id (db-transactions/category-id db-conn partner)})]
    (cond-> {:type :set-match :tx-id tx-id :before nil :after partner :label "Matched transfer"}
      effect (assoc :category-effect effect))))

(defn confirm-match
  "PUT /transactions/:id/match/:partner — link the two legs as a transfer (a :set-match command
   — see match-command, which copies a lone category to the blank leg — so undo unlinks and
   reverts the copy), then re-render and close the modal."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [tx-id (path-id req :id)
             partner (path-id req :partner)]
         (commands/apply! db-conn auth/user-id (match-command db-conn tx-id partner))
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

(defn posted-date-editor
  "GET /transactions/:id/posted-date-editor — render the posted-date override modal into
   #modal-root. A split PART opens on its family ROOT (db-transactions/split-editor-root — the
   override is family-uniform, same as the split editor's own defensive resolve). The row's
   current EFFECTIVE date (yyyy-MM-dd) is computed here and travels BOTH ways: into
   patch-signals (so a no-edit Save submits the prefill) and into the view as the date input's
   server-rendered :value (the input is one-way — see posted-date-modal); the modal itself
   renders the imported date alongside for reference. A pure read (no command, no lingering
   change)."
  [{:keys [db-conn]}]
  (fn [req]
    (let [tx (db-transactions/split-editor-root db-conn (path-id req :id))
          effective (some-> (:transaction/effective-posted-date tx) u/date->local-date str)]
      (sse-response req
       (fn [sse]
         (d*/patch-signals! sse (r/signals {:postedDateValue (or effective "")}))
         (patch! sse (posted-date-modal tx effective)))))))

(defn set-posted-date
  "PUT /transactions/:id/posted-date — record + apply a :set-posted-date command (the new value
   rides in the $postedDateValue courier as yyyy-MM-dd; blank clears the override), then
   re-render and close the modal. Reuses the standard edit-response re-render — the effective
   date driving bucketing/coverage/transfer-matching may move a row across a month or
   statement-span boundary, exactly the kind of change that recomputes rows/counts/reconcile."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [tx-id (path-id req :id)
             signals (r/read-signals req)
             before (db-transactions/user-posted-date db-conn tx-id)
             after (courier-date (:postedDateValue signals))]
         (commands/apply! db-conn auth/user-id
                          {:type :set-posted-date :tx-id tx-id :before before :after after
                           :label (if after "Set posted date" "Cleared posted date")})
         (edit-response db-conn req signals :close-modal? true))))))

(defn- review-range
  "The span the review modal scopes its suggestions to (db.transfers/suggest-matches'
   :range — {:from Date :to Date}, inclusive calendar days). In month view: the statement
   lens's span when it's narrowing the table (reconcile-range), else the whole month being
   viewed. In range view: the lens never applies (see table-and-facets) — the range's own
   [from, to] (period/range-dates), which is already the same {:from :to} inclusive-Date
   shape this fn returns for a month."
  [signals view-st]
  (let [p (signals-period signals)]
    (if-not (period/month? p)
      (period/range-dates p)
      (if-let [{:keys [from to]} (reconcile-range signals view-st)]
        {:from from :to to}
        (let [month (signals-month signals)]
          {:from (:start-date (u/month-date-range month))
           :to (db-snapshots/month-end-date month)})))))

(defn review-transfers
  "GET /transactions/review-transfers — render the bulk transfer-review modal (auto-suggested
   pairs) into #modal-root, scoped to the period on screen (review-range: the statement lens
   when active in month view, else the whole viewed period — the GET carries the live
   signals, as rows does)."
  [{:keys [db-conn]}]
  (fn [req]
    (let [signals (r/read-signals req)
          view-st (vs/signals->view-state signals)
          suggestions (db-transfers/suggest-matches
                       db-conn {:range (review-range signals view-st)})]
      (sse-response req (fn [sse] (patch! sse (review-modal suggestions)))))))

(defn- patch-stale-suggestion!
  "Morph the acted-on suggestion row (by its stable id) into its stale variant — the pair
   stays visible under the cursor and the rest of the list never moves. A fresh GET
   recomputes the list, so the pair drops out naturally next open."
  [db-conn sse out in verdict]
  (patch! sse (suggestion-row-stale (db-transactions/by-id db-conn out)
                                    (db-transactions/by-id db-conn in)
                                    verdict)))

(defn review-confirm
  "PUT /transactions/review/:out/confirm/:in — confirm a suggested pair (a :set-match command
   via match-command, so undo unlinks — and reverts a copied category), then re-render the
   table + morph just that suggestion row stale in place."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [out (path-id req :out)
             in (path-id req :in)]
         (commands/apply! db-conn auth/user-id (match-command db-conn out in))
         (edit-response db-conn req (r/read-signals req)
                        :after-patch (fn [sse] (patch-stale-suggestion! db-conn sse out in :matched))))))))

(defn review-reject
  "PUT /transactions/review/:a/reject/:b — reject a suggested pair (:reject-match command, so
   undo un-rejects), then re-render the table + morph just that suggestion row stale in place."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [a (path-id req :a)
             b (path-id req :b)]
         (commands/apply! db-conn auth/user-id
                          {:type :reject-match :tx-id a :partner b :before false :after true :label "Rejected transfer"})
         (edit-response db-conn req (r/read-signals req)
                        :after-patch (fn [sse] (patch-stale-suggestion! db-conn sse a b :rejected))))))))

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

(defn- account-picker-options
  "All accounts as {:eid :name}, name-sorted — the option list the add-transaction modal
   shows. :name is the account's shown label (web.accounts/account-label — a rename
   overlay when set, else the provider's name)."
  [db-conn]
  (->> (db-accounts/list-with-institution db-conn)
       (map (fn [a] {:eid (:db/id a) :name (web-accounts/account-label a)}))
       (sort-by :name)))

(defn- patch-close-panel!
  "Re-render the reconciliation panel for `month`/`view-st` and morph it into #reconciliation,
   re-seeding the focused-card prefill signals."
  [db-conn req month view-st]
  (sse-response req
   (fn [sse]
     (let [m (close-model-for db-conn month view-st)]
       (patch! sse (tv/close-panel m))
       (d*/patch-signals! sse (r/signals (recon-signals m)))))))

(defn set-reconcile-balances
  "POST /transactions/reconcile — record the focused account's opening and/or closing balances
   (the $reconOpen / $reconClose couriers) as :manual snapshots at the app-owned month-boundary
   dates (prior-month-end for the opening, this-month-end for the closing), then re-patch the
   focused panel with the fresh verdict. The account is the single one the table is filtered to.
   Surfaces an error when no account is focused or both fields are blank."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [signals (r/read-signals req)
             month   (signals-month signals)
             view-st (vs/signals->view-state signals)
             eid     (focus-account-eid view-st)
             opening (parse-money (:reconOpen signals))
             closing (parse-money (:reconClose signals))]
         (if (and eid (or opening closing))
           (do
             (when opening
               (db-snapshots/record-manual-balance! db-conn eid (db-snapshots/opening-date month) opening))
             (when closing
               (db-snapshots/record-manual-balance! db-conn eid (db-snapshots/month-end-date month) closing))
             (patch-close-panel! db-conn req month view-st))
           (error-response req "Drill into an account and enter a balance.")))))))

;; --- Statements (arbitrary-span reconciliation) ----------------------------
;; A statement lands on the account you've drilled into (the focused account) — no picker.
;; Add/edit share one modal + POST; the panel re-patches so the new verdict shows immediately.

(defn statement-editor
  "GET /transactions/statement-modal — render the add/edit statement modal into #modal-root and
   seed its signals. With ?id=<eid> it edits that statement (prefilled); without, it adds a new
   one (blank). The statement lands on the focused account. The yyyy-MM-dd date strings are
   computed here and travel BOTH ways: into patch-signals (so a no-edit Save submits the
   prefill) and into the view as the date inputs' server-rendered :value (the inputs are
   one-way — see statement-modal)."
  [{:keys [db-conn]}]
  (fn [req]
    (let [eid   (some-> (get-in req [:query-params "id"]) parse-long)
          st    (when eid (db-statements/by-id db-conn eid))
          start (when st (str (u/date->local-date (:start-date st))))
          end   (when st (str (u/date->local-date (:end-date st))))]
      (sse-response req
       (fn [sse]
         (d*/patch-signals!
          sse (r/signals
               (if st
                 {:stId (str eid)
                  :stStart start :stStartBal (str (:start-balance st))
                  :stEnd end     :stEndBal (str (:end-balance st))}
                 {:stId "" :stStart "" :stStartBal "" :stEnd "" :stEndBal ""})))
         (patch! sse (tv/statement-modal (some? st) {:start start :end end})))))))

(defn- patch-panel+close-modal!
  "Re-patch the reconciliation panel (+ its prefill signals) for `month`/`view-st` and empty
   #modal-root — the shared tail of the statement create/update/delete handlers."
  [db-conn req month view-st]
  (sse-response req
   (fn [sse]
     (let [m (close-model-for db-conn month view-st)]
       (patch! sse (tv/close-panel m))
       (d*/patch-signals! sse (r/signals (recon-signals m)))
       (patch! sse [:div {:id "modal-root"}])))))

(defn save-statement
  "POST /transactions/statement — create or update a statement for the focused account from the
   $stStart/$stStartBal/$stEnd/$stEndBal couriers ($stId set = update, blank = create). Re-patches
   the panel + closes the modal. Errors on a missing field or no focused account (create)."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [signals    (r/read-signals req)
             month      (signals-month signals)
             view-st    (vs/signals->view-state signals)
             eid        (courier-eid (:stId signals))
             account    (focus-account-eid view-st)
             start-date (courier-date (:stStart signals))
             end-date   (courier-date (:stEnd signals))
             start-bal  (parse-money (:stStartBal signals))
             end-bal    (parse-money (:stEndBal signals))]
         (if (and start-date end-date start-bal end-bal (or eid account))
           (do
             (if eid
               (db-statements/update! db-conn eid {:start-date start-date :start-balance start-bal
                                                   :end-date end-date :end-balance end-bal})
               (db-statements/create! db-conn {:account-eid account :start-date start-date :start-balance start-bal
                                               :end-date end-date :end-balance end-bal}))
             (patch-panel+close-modal! db-conn req month view-st))
           (error-response req "Enter the statement's dates and balances.")))))))

(defn delete-statement
  "POST /transactions/statement/delete — delete the statement whose id rides in $stId, re-patch
   the panel, and close the modal."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [signals (r/read-signals req)
             month   (signals-month signals)
             view-st (vs/signals->view-state signals)]
         (when-let [eid (courier-eid (:stId signals))]
           (db-statements/delete! db-conn eid))
         (patch-panel+close-modal! db-conn req month view-st))))))

(defn close-month
  "POST /transactions/close — freeze the current month's category totals and lock it.
   Refuses (surfaces an error, no write) unless the month is ready: every transaction
   reconciled and categorized and every account's balance reconciled. Re-patches the panel."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [signals (r/read-signals req)
             month (signals-month signals)
             view-st (vs/signals->view-state signals)]
         (if-not (get-in (close-model-for db-conn month view-st) [:gate :ready?])
           (error-response req "This month isn't ready to close yet.")
           (let [txs (db-transactions/list-for-month db-conn month)
                 categories (db-categories/list-all db-conn)
                 {:keys [income expenses transfers grand-total]} (view/category-rollup txs categories)]
             (db-reconciliations/close-month!
              db-conn month
              {:income (:total income) :expenses (:total expenses)
               :transfers (:total transfers) :net grand-total}
              (java.util.Date.))
             (patch-close-panel! db-conn req month view-st))))))))

(defn reopen-month
  "POST /transactions/reopen — unlock the current month, then re-patch the panel."
  [{:keys [db-conn]}]
  (fn [req]
    (handle-edit req
     (fn []
       (let [signals (r/read-signals req)
             month (signals-month signals)
             view-st (vs/signals->view-state signals)]
         (db-reconciliations/reopen-month! db-conn month)
         (patch-close-panel! db-conn req month view-st))))))

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
   A pure read: lists all accounts (the category combobox reads the page-level
   #category-options model, so no category list rides the modal); the date defaults per
   default-txn-date and renders as the input's :value (the date input is one-way — see
   add-transaction-modal)."
  [{:keys [db-conn]}]
  (fn [req]
    (let [signals (r/read-signals req)
          month (signals-month signals)
          accounts (account-picker-options db-conn)
          default-date (default-txn-date month)
          selected (:eid (first accounts))]
      (sse-response req
       (fn [sse]
         (d*/patch-signals! sse (r/signals {:txAccount (str selected) :txDir "out" :txAmount ""
                                            :txDate default-date :txPayee "" :txDesc "" :txCategory ""}))
         (patch! sse (tv/add-transaction-modal accounts default-date selected)))))))

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
             account-eid (courier-eid (:txAccount signals))
             date        (courier-date (:txDate signals))
             magnitude   (some-> (parse-money (:txAmount signals)) .abs)
             amount      (when magnitude (if (= "in" (:txDir signals)) magnitude (.negate magnitude)))
             category-id (vs/parse-category-value (:txCategory signals))]
         (if (and account-eid date amount)
           (do (db-transactions/create-manual! db-conn auth/user-id
                                               {:account-eid account-eid :amount amount :date date
                                                :payee (some-> (:txPayee signals) str)
                                                :description (some-> (:txDesc signals) str)
                                                :category-id category-id})
               ;; edit-response re-patches #reconciliation (the new row moves the tracked delta).
               (edit-response db-conn req signals :close-modal? true))
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
       (let [tx-id    (path-id req :id)
             signals  (r/read-signals req)
             part-ids (db-transactions/delete-manual! db-conn tx-id)]
         ;; The row (and any cascaded split parts) is gone — drop any undo/redo command
         ;; that would replay against them (a matched/split manual row would otherwise
         ;; jam the stack on undo).
         (commands/forget! auth/user-id tx-id)
         (run! (partial commands/forget! auth/user-id) part-ids)
         ;; edit-response re-patches #reconciliation (the removed row moves the tracked delta).
         (edit-response db-conn req signals :close-modal? true))))))

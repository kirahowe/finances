(ns finance-aggregator.db.transfers
  "Persistence for transfer matching. A confirmed match sets
   :transaction/transfer-pair on both legs; a rejection records
   :transaction/transfer-rejected on both legs so it is not re-suggested. Only
   confirmed matches are persisted — suggestions are computed on demand and
   reviewed before anything is written.

   Conn is a datalevin connection (not an atom)."
  (:require [datalevin.core :as d]
            [finance-aggregator.data.ledger :as ledger]
            [finance-aggregator.transfers :as transfers]
            [finance-aggregator.utils :as utils]))

(def ^:private suggest-pull
  "Enough of each transaction to run the matcher and render the review UI. Pulls
   :transaction/date and :transaction/user-posted-date alongside :transaction/posted-date
   so day-matching (normalize, match-candidates) can resolve the full effective-posted-date
   chain (data.ledger/effective-posted-date) rather than the raw imported guess."
  '[:db/id :transaction/amount :transaction/posted-date :transaction/date
    :transaction/user-posted-date :transaction/payee
    {:transaction/account [:db/id :account/external-name :account/display-name
                           {:account/institution [:db/id :institution/name]}]}
    {:transaction/category [:db/id :category/name :category/type]}
    {:transaction/transfer-pair [:db/id]}
    {:transaction/transfer-rejected [:db/id]}])

(defn- all-transactions
  "Every transaction eligible to be a transfer leg. Excludes any transaction that
   HAS live split parts — a split parent is hidden from every list, so it must never
   be suggested or offered as a candidate; its parts are normal rows and flow through
   untouched (a part categorized as a transfer is matchable like any leg)."
  [db]
  (d/q '[:find [(pull ?e pattern) ...]
         :in $ pattern
         :where
         [?e :transaction/external-id _]
         (not [?p :transaction/split-parent ?e])]
       db suggest-pull))

(defn- normalize [tx]
  {:id (:db/id tx)
   :amount (:transaction/amount tx)
   :day (utils/date->epoch-day (ledger/effective-posted-date tx))
   :account-id (get-in tx [:transaction/account :db/id])
   :real? (transfers/real-activity? (get-in tx [:transaction/category :category/type]))
   :paired? (some? (:transaction/transfer-pair tx))
   :rejected (set (map :db/id (:transaction/transfer-rejected tx)))})

(defn suggest-matches
  "Compute suggested transfer pairs. opts:

     :window-days — max day gap between a pair's legs (the pure matcher's option,
                    default 3 — see transfers/suggest-matches).
     :range       — optional {:from Date :to Date}, INCLUSIVE calendar days: scope the
                    suggestions to the period on screen. Two rules:
                      1. the candidate POOL is transactions whose effective day falls in
                         [from − window, to + window] — a leg just outside the period can
                         still complete a pair whose other leg is inside;
                      2. a pair is KEPT only when AT LEAST ONE leg's effective day falls
                         inside [from, to] — a Jan-31 → Feb-2 transfer surfaces while
                         reviewing January, but a pair living entirely outside the period
                         never does. (Rule 1 never drops a pair rule 2 would keep: an
                         in-range leg's partner is within `window` of it, hence in the pool.)
                    Scoping also shrinks the O(n²) matcher's candidate pool from the whole
                    history to the period plus window slack.

   Days are effective posted days (data.ledger/effective-posted-date). Returns a vector of
   {:outflow <pulled tx> :inflow <pulled tx> :amount bigdec :day-diff long}."
  ([conn] (suggest-matches conn {}))
  ([conn {:keys [range window-days] :as opts}]
   (let [db (d/db conn)
         txns (all-transactions db)
         by-id (into {} (map (juxt :db/id identity)) txns)
         window (or window-days 3)
         from-day (some-> (:from range) utils/date->epoch-day)
         to-day (some-> (:to range) utils/date->epoch-day)
         pool? (fn [{:keys [day]}]
                 (and day (<= (- from-day window) day (+ to-day window))))
         normalized (cond->> (map normalize txns)
                      range (filter pool?))
         day-of (into {} (map (juxt :id :day)) normalized)
         in-range? (fn [id] (when-let [day (day-of id)] (<= from-day day to-day)))
         pairs (cond->> (transfers/suggest-matches normalized (dissoc opts :range))
                 range (filter (fn [{:keys [outflow-id inflow-id]}]
                                 (or (in-range? outflow-id) (in-range? inflow-id)))))]
     (mapv (fn [{:keys [outflow-id inflow-id amount day-diff]}]
             {:outflow (by-id outflow-id)
              :inflow (by-id inflow-id)
              :amount amount
              :day-diff day-diff})
           pairs))))

(def ^:private validate-pull
  '[:db/id :transaction/amount
    {:transaction/account [:db/id]}
    {:transaction/transfer-pair [:db/id]}
    {:transaction/transfer-rejected [:db/id]}
    {:transaction/_split-parent [:db/id]}])

(defn- validate-transfer!
  "Pull and validate the two legs of a proposed transfer, throwing ex-info for any
   violation shared by confirm and reject: same transaction, missing transaction, a
   leg with live split parts (a split parent is hidden from every list, so it must
   never become a matched leg — match one of its parts; defensive, since the PUT
   route takes raw ids), same account, or amounts that aren't equal-and-opposite.
   Returns [a b], the pulled entities. The not-already-paired rule is confirm-only
   and checked there."
  [db a-id b-id]
  (when (= a-id b-id)
    (throw (ex-info "Cannot match a transaction to itself" {:type :bad-request})))
  (let [a (d/pull db validate-pull a-id)
        b (d/pull db validate-pull b-id)]
    (when-not (and (:transaction/amount a) (:transaction/amount b))
      (throw (ex-info "Transaction not found" {:type :not-found})))
    (when (or (seq (:transaction/_split-parent a)) (seq (:transaction/_split-parent b)))
      (throw (ex-info "This transaction is split — match one of its parts instead"
                      {:type :bad-request})))
    (when (= (get-in a [:transaction/account :db/id])
             (get-in b [:transaction/account :db/id]))
      (throw (ex-info "A transfer must move money between two different accounts"
                      {:type :bad-request})))
    (when-not (transfers/opposite-amounts? (:transaction/amount a) (:transaction/amount b))
      (throw (ex-info "A transfer's two legs must have equal and opposite amounts"
                      {:type :bad-request})))
    [a b]))

(defn confirm-match!
  "Link two transactions as a transfer pair (bidirectional). Validates the pair
   (see validate-transfer!) and additionally that neither leg is already paired. Any
   prior rejection between the two legs is cleared, since an explicit link wins.
   Throws :bad-request on an invalid pair, :not-found if either is missing,
   :conflict if either is already paired. Returns {:linked true :a a-id :b b-id};
   callers re-fetch the affected rows rather than reading a snapshot back."
  [conn a-id b-id]
  (let [db (d/db conn)
        [a b] (validate-transfer! db a-id b-id)]
    (when (or (:transaction/transfer-pair a) (:transaction/transfer-pair b))
      (throw (ex-info "A transaction is already part of a transfer" {:type :conflict})))
    (let [a-rejected (set (map :db/id (:transaction/transfer-rejected a)))
          b-rejected (set (map :db/id (:transaction/transfer-rejected b)))]
      (d/transact! conn
                   (cond-> [{:db/id a-id :transaction/transfer-pair b-id}
                            {:db/id b-id :transaction/transfer-pair a-id}]
                     (a-rejected b-id) (conj [:db/retract a-id :transaction/transfer-rejected b-id])
                     (b-rejected a-id) (conj [:db/retract b-id :transaction/transfer-rejected a-id]))))
    {:linked true :a a-id :b b-id}))

(defn unmatch!
  "Remove the transfer link from a transaction and its partner (both sides). Throws
   :not-found if the transaction is missing. Returns {:unmatched bool :partner id-or-nil}."
  [conn tx-id]
  (let [db (d/db conn)
        tx (d/pull db '[:transaction/amount {:transaction/transfer-pair [:db/id]}] tx-id)]
    (when-not (:transaction/amount tx)
      (throw (ex-info "Transaction not found" {:type :not-found})))
    (let [partner (get-in tx [:transaction/transfer-pair :db/id])]
      (when partner
        (d/transact! conn [[:db/retract tx-id :transaction/transfer-pair partner]
                           [:db/retract partner :transaction/transfer-pair tx-id]]))
      {:unmatched (boolean partner) :partner partner})))

(defn reject-match!
  "Record that the user rejected pairing a-id with b-id (written symmetrically), so
   auto-match won't re-propose it. Validates the pair the same way confirm does (see
   validate-transfer!), so a rejection can only describe a real candidate. If the two
   are currently linked, the link is removed — a pair can't be simultaneously
   confirmed and rejected. Throws :bad-request on an invalid pair, :not-found if
   either is missing. Returns {:rejected true}."
  [conn a-id b-id]
  (let [db (d/db conn)
        [a b] (validate-transfer! db a-id b-id)]
    (d/transact! conn
                 (cond-> [{:db/id a-id :transaction/transfer-rejected b-id}
                          {:db/id b-id :transaction/transfer-rejected a-id}]
                   (= b-id (get-in a [:transaction/transfer-pair :db/id]))
                   (conj [:db/retract a-id :transaction/transfer-pair b-id])
                   (= a-id (get-in b [:transaction/transfer-pair :db/id]))
                   (conj [:db/retract b-id :transaction/transfer-pair a-id])))
    {:rejected true}))

(defn unreject!
  "Retract a symmetric transfer rejection between a-id and b-id — the inverse of reject-match!
   for undo. Idempotent; restores no link (a rejection from a suggestion had none). Returns
   {:unrejected true}."
  [conn a-id b-id]
  (d/transact! conn [[:db/retract a-id :transaction/transfer-rejected b-id]
                     [:db/retract b-id :transaction/transfer-rejected a-id]])
  {:unrejected true})

(defn match-candidates
  "Counterpart candidates for manually matching one transaction: inverse amount,
   different account, within a wider window (default 30 days), not already paired.
   Unlike auto-suggest this does NOT exclude real expense/income legs (manual
   matching is explicit, e.g. a mortgage payment categorized as Housing) and does
   NOT exclude previously-rejected pairs — explicitly searching for a counterpart
   is how the user recovers from a mistaken rejection. Returns pulled transactions
   sorted by date proximity (ties broken by id). Throws :not-found if the
   transaction is missing."
  ([conn tx-id] (match-candidates conn tx-id {:window-days 30}))
  ([conn tx-id {:keys [window-days] :or {window-days 30}}]
   (let [db (d/db conn)
         self (d/pull db suggest-pull tx-id)]
     (when-not (:transaction/amount self)
       (throw (ex-info "Transaction not found" {:type :not-found})))
     (let [self-amt (:transaction/amount self)
           self-day (utils/date->epoch-day (ledger/effective-posted-date self))
           self-acct (get-in self [:transaction/account :db/id])]
       (if (zero? self-amt)
         []  ; a $0 transaction has no meaningful transfer counterpart
         (->> (all-transactions db)
              (keep (fn [t]
                      (let [day (utils/date->epoch-day (ledger/effective-posted-date t))
                            ;; a missing date can't be windowed; keep it rather than
                            ;; silently dropping, and sort it last.
                            day-diff (when (and self-day day) (abs (- day self-day)))]
                        (when (and (not= (:db/id t) tx-id)
                                   (transfers/opposite-amounts? (:transaction/amount t) self-amt)
                                   (not= (get-in t [:transaction/account :db/id]) self-acct)
                                   (nil? (:transaction/transfer-pair t))
                                   (or (nil? day-diff) (<= day-diff window-days)))
                          {:tx t :day-diff day-diff}))))
              (sort-by (juxt #(or (:day-diff %) Long/MAX_VALUE) #(:db/id (:tx %))))
              (mapv :tx)))))))

(defn with-transfer-hidden
  "Annotate a pulled transaction with :transaction/transfer-hidden true when the
   Hide-transfers toggle should remove it (a matched pair whose legs are both
   non-real expense/income). Computed server-side so the client only reads the flag.
   Requires the pulled tx to carry its own and its partner's :category/type (as the
   transactions list pull does). Other transactions are returned unchanged."
  [tx]
  (let [pair (:transaction/transfer-pair tx)]
    (cond-> tx
      (transfers/hidden-transfer? (some? pair)
                                  (get-in tx [:transaction/category :category/type])
                                  (get-in pair [:transaction/category :category/type]))
      (assoc :transaction/transfer-hidden true))))

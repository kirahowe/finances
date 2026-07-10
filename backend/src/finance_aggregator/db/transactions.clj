(ns finance-aggregator.db.transactions
  (:require [clojure.string :as str]
            [datalevin.core :as d]
            [finance-aggregator.data.ledger :as ledger]
            [finance-aggregator.db.transfers :as db-transfers]
            [finance-aggregator.db.users :as db-users]
            [finance-aggregator.splits :as splits]
            [finance-aggregator.utils :as utils])
  (:import [java.util Date UUID]))

(def transaction-pull-pattern
  "Canonical pull pattern for a transaction returned to the API. Shared by the list
   fns and the single-transaction mutation endpoints so the shapes never drift. The
   wildcard `*` returns a ref attribute as a bare {:db/id}, so :transaction/transfer-pair
   is expanded explicitly: the server-side hide rule (db-transfers/with-transfer-hidden)
   reads the partner's category type, and the UI renders the partner's amount and posted
   date.

   :transaction/split-parent (present only on a PART) pulls its parent plus every
   sibling's amount — enough to compute :transaction/split-drift (with-split-drift)
   without a second query. :transaction/_split-parent (present only on a PARENT with
   live parts, e.g. via by-id for the editor) is the reverse pull of those parts.

   :transaction/transfer-pair also pulls the partner's :transaction/user-posted-date
   (alongside its posted-date) so the partner's display can be effective-capable, the
   same as this row's own override."
  ['* {:transaction/category [:db/id :category/name :category/type]
       :transaction/account [:db/id :account/external-name
                             {:account/institution [:db/id :institution/name]}]
       :transaction/split-parent [:db/id :transaction/amount :transaction/payee
                                  {:transaction/_split-parent [:db/id :transaction/amount]}]
       :transaction/_split-parent [:db/id :transaction/amount :transaction/split-order
                                   :transaction/description :transaction/reconciled
                                   {:transaction/category [:db/id :category/name]}]
       :transaction/transfer-pair [:db/id :transaction/amount :transaction/posted-date
                                   :transaction/user-posted-date
                                   {:transaction/category [:db/id :category/name :category/type]}
                                   {:transaction/account [:db/id :account/external-name]}]}])

(defn with-effective-description
  "Annotate a pulled transaction with :transaction/effective-description — the value
   clients display in the Description column: the user's override when present, else
   the imported description. The imported :transaction/description is never mutated and
   is returned alongside (so a 'view original' surface can show it), and
   :transaction/user-description signals whether an override exists."
  [tx]
  (assoc tx :transaction/effective-description
         (or (not-empty (:transaction/user-description tx))
             (:transaction/description tx))))

(defn with-effective-posted-date
  "Annotate a pulled transaction with :transaction/effective-posted-date — the date
   bucketing, coverage and transfer-matching go by (data.ledger/effective-posted-date):
   the user's manual override when present, else the provider's posted-date guess, else
   the plain transaction date. The imported :transaction/posted-date is never mutated
   and is returned alongside (so an 'Imported: <date>' hint can show it), and
   :transaction/user-posted-date signals whether an override exists."
  [tx]
  (assoc tx :transaction/effective-posted-date (ledger/effective-posted-date tx)))

(defn with-split-drift
  "Annotate a pulled PART transaction (one with :transaction/split-parent) with
   :transaction/split-drift true when the parts' amounts — pulled via the nested
   :transaction/split-parent -> :transaction/_split-parent join, so this needs no
   extra query — no longer sum to the parent's amount (bigdec-exact, via
   splits/reconciled?). This is how a re-sync that changes the parent's imported
   amount after the split was made surfaces, instead of silently drifting. Absent
   on a non-part transaction, or when the parts still reconcile."
  [tx]
  (if-let [parent (:transaction/split-parent tx)]
    (let [amounts (map :transaction/amount (:transaction/_split-parent parent))]
      (cond-> tx
        (not (splits/reconciled? (:transaction/amount parent) amounts))
        (assoc :transaction/split-drift true)))
    tx))

(defn with-derived-fields
  "Annotate a pulled transaction with the server-computed fields the API contract
   promises: :transaction/effective-description, :transaction/effective-posted-date,
   :transaction/split-drift and :transaction/transfer-hidden. (:transaction/reconciled is
   the row's own stored flag, nil-punned absent — a split part reconciles itself like any
   row, so there is no roll-up.) Applied uniformly by the list fns and the
   single-transaction mutation endpoints so the response shape never drifts."
  [tx]
  (-> tx with-effective-description with-effective-posted-date with-split-drift db-transfers/with-transfer-hidden))

(defn list-for-month
  "All transactions whose EFFECTIVE posted date (data.ledger/effective-posted-date — the
   user's manual override when present, else the provider's posted-date guess, else the
   transaction date) falls in `month` (a YYYY-MM string), pulled with the canonical
   pattern and annotated with the derived API fields. Excludes any transaction that has
   split parts — the parts replace it at the row grain (see
   doc/plans/splits-as-transactions.md). Shared by the JSON list endpoint and the
   server-rendered transactions page.

   Queries by external-id + not-split-parent only (the same shape as list-all) and
   month-filters in Clojure, AFTER annotating — the effective date isn't a datom
   Datalevin can bound a query on, since it may fall back through three attributes or be
   moved by a manual override. This is no real perf change: the OLD query already pulled
   every external-id'd transaction dated before end-of-month and post-filtered the start
   bound in Clojure at single-user scale. Filtering post-annotation (rather than
   pre-filtering on the stored posted-date) is what lets an override move a row across
   the month boundary in EITHER direction — into a month it wasn't imported into, or out
   of the one it was."
  [conn month]
  (let [{:keys [start-date end-date]} (utils/month-date-range month)
        raw (d/q '[:find [(pull ?e pattern) ...]
                   :in $ pattern
                   :where
                   [?e :transaction/external-id _]
                   (not [?p :transaction/split-parent ?e])]
                 (d/db conn) transaction-pull-pattern)]
    (->> raw
         (mapv with-derived-fields)
         (filterv (fn [tx]
                    (when-let [^Date d (:transaction/effective-posted-date tx)]
                      (and (not (.before d start-date)) (.before d end-date))))))))

(defn list-for-account-range
  "`account-eid`'s transactions in the half-open reconcile span (from, to] — strictly after
   `from`, up to and including `to` — bucketed by the EFFECTIVE posted date (data.ledger/
   effective-posted-date), not the raw imported one. `from`/`to` are Dates (UTC day granularity).
   `from` is the EXCLUSIVE lower boundary (a balance-reading day whose activity is already
   counted); the statement callers pass data.ledger/statement-opening-boundary so a statement's
   INCLUSIVE printed start day still lands in the span. Pulled + annotated first, then
   span-filtered and sorted on the effective date — so a manual override can move a row across a
   statement-span boundary just as it can a calendar-month one. Excludes any transaction that has
   split parts (see list-for-month). Used to reconcile + display a statement period, which may
   cross a calendar-month boundary."
  [conn account-eid ^Date from ^Date to]
  (let [to-exclusive (Date. (+ (.getTime to) 86400000))   ; include txns dated on `to`
        raw (d/q '[:find [(pull ?e pattern) ...]
                   :in $ pattern ?acct
                   :where
                   [?e :transaction/account ?acct]
                   (not [?p :transaction/split-parent ?e])]
                 (d/db conn) transaction-pull-pattern account-eid)]
    (->> raw
         (mapv with-derived-fields)
         (filter (fn [tx]
                   (when-let [^Date d (:transaction/effective-posted-date tx)]
                     (and (.after d from) (.before d to-exclusive)))))
         (sort-by :transaction/effective-posted-date)
         vec)))

(defn list-all
  "All transactions, pulled + annotated with the derived API fields, excluding any
   transaction that has split parts (see list-for-month)."
  [conn]
  (mapv with-derived-fields
        (d/q '[:find [(pull ?e pattern) ...]
               :in $ pattern
               :where
               [?e :transaction/external-id _]
               (not [?p :transaction/split-parent ?e])]
             (d/db conn) transaction-pull-pattern)))

(defn by-id
  "A single transaction pulled with the canonical pattern + derived fields, or nil when the
   id isn't a real transaction. Used by the split-editor modal (which needs the parent amount,
   payee, and current parts)."
  [conn tx-id]
  (let [tx (d/pull (d/db conn) transaction-pull-pattern tx-id)]
    (when (:transaction/external-id tx) (with-derived-fields tx))))

(defn split-editor-root
  "The transaction the split editor must open on for `tx-id`: the transaction itself,
   or — when tx-id names a split PART — its PARENT (depth is 1; the parent's editor is
   the only place amounts change, so every path into the editor lands on the family's
   parent). Pulled + annotated like by-id; nil when tx-id isn't a real transaction."
  [conn tx-id]
  (let [tx (by-id conn tx-id)]
    (if-let [parent-id (get-in tx [:transaction/split-parent :db/id])]
      (by-id conn parent-id)
      tx)))

(defn user-description
  "The transaction's current user-description override (\"\" when none) — for capturing the
   before-value of an inline-description-edit command so undo can restore it."
  [conn tx-id]
  (or (:transaction/user-description (d/pull (d/db conn) [:transaction/user-description] tx-id)) ""))

(defn user-posted-date
  "The FAMILY ROOT's current user-posted-date override, or nil when none — for
   capturing the before-value of a set-posted-date command so undo can restore it.
   Unlike user-description (a per-row overlay), the posted-date override is
   family-uniform (set-user-posted-date!), so a split PART resolves to its PARENT via
   split-editor-root — the root is the single source of truth every family member's
   override is asserted from."
  [conn tx-id]
  (:transaction/user-posted-date (split-editor-root conn tx-id)))

(defn category-id
  "The transaction's current category id (nil when uncategorized) — for capturing the
   before-value of a recategorize command so undo can restore it."
  [conn tx-id]
  (get-in (d/pull (d/db conn) [{:transaction/category [:db/id]}] tx-id) [:transaction/category :db/id]))

(def ^:private current-splits-pull
  [{:transaction/_split-parent
    [:db/id :transaction/amount :transaction/split-order :transaction/description
     {:transaction/category [:db/id]}]}])

(defn current-splits
  "The transaction's current live parts in set-splits! input shape
   ({:id long :amount string :category-id long? :memo string?}), ordered by
   :transaction/split-order, or [] when unsplit — for capturing the before-value of a
   split command so undo restores the prior parts (re-applying [] un-splits, and a
   part removed-and-undone comes back as a fresh entity via the stale-id create path)."
  [conn tx-id]
  (->> (:transaction/_split-parent (d/pull (d/db conn) current-splits-pull tx-id))
       (sort-by :transaction/split-order)
       (mapv (fn [{:keys [db/id] :transaction/keys [amount description category]}]
               (cond-> {:id id
                        :amount (.toPlainString (bigdec amount))
                        :category-id (:db/id category)}
                 description (assoc :memo description))))))

(def ^:private inherited-fields-pull
  "Pull pattern for propagate-inherited-fields!: a re-imported transaction's inherited-
   field attrs (splits/inherited-fields' recipe — account/user/date/posted-date/payee/
   user-posted-date) plus its live parts pulled with the SAME attrs (+ :db/id, to write
   the update) — one query gets both the new values and what to diff them against."
  [:transaction/account :transaction/user :transaction/date :transaction/posted-date :transaction/payee
   :transaction/user-posted-date
   {:transaction/_split-parent [:db/id :transaction/account :transaction/user :transaction/date
                                :transaction/posted-date :transaction/payee :transaction/user-posted-date]}])

(defn propagate-inherited-fields!
  "Re-assert each re-imported transaction's inherited fields (account/user/date/
   posted-date/payee/user-posted-date — splits/inherited-fields' recipe, the same one
   set-splits! and the migration use) onto its live split parts where they differ. A
   Plaid `modified` can move a parent's posted-date/payee/account after a split was
   made; parts carry COPIES, so a stale copy would bucket a part into the wrong
   month/statement span. :transaction/user-posted-date rides along so a manual override
   set before a part was created (or before a later re-split) still converges the whole
   family — sync itself never writes this overlay (contract-protected), but a part born
   out of step with its root must still catch up. Amount is deliberately excluded — a
   changed parent amount surfaces as :transaction/split-drift instead (with-split-drift),
   never silently propagated.

   Only queries parts for the external-ids actually in `transactions` (a batch just
   passed through persist-transactions!). Idempotent: a transaction with no live parts,
   or whose parts already match, contributes no tx-data — a no-op transact! is skipped
   entirely. Conn is a datalevin connection (not an atom)."
  [conn transactions]
  (when-let [ext-ids (seq (keep :transaction/external-id transactions))]
    (let [db (d/db conn)
          parents (d/q '[:find [(pull ?e pattern) ...]
                         :in $ pattern [?ext ...]
                         :where [?e :transaction/external-id ?ext]]
                       db inherited-fields-pull ext-ids)
          tx-data (into []
                        (mapcat (fn [parent]
                                 (let [inherited (splits/inherited-fields parent)]
                                   (keep (fn [part]
                                          (when (not= inherited (splits/inherited-fields part))
                                            (merge {:db/id (:db/id part)} inherited)))
                                        (:transaction/_split-parent parent)))))
                        parents)]
      (when (seq tx-data)
        (d/transact! conn tx-data)))))

(defn- set-reconciled-datom!
  "Assert (true) or clear (false) the boolean reconciled flag `attr` on entity `eid`.
   Clearing retracts the datom so its absence nil-puns to not-reconciled."
  [conn eid attr reconciled?]
  (d/transact! conn (if reconciled?
                      [{:db/id eid attr true}]
                      [[:db/retract eid attr]])))

(defn set-reconciled!
  "Mark a transaction reconciled (true) or clear it (false). Stored as an additive
   overlay on the imported transaction. Conn is a datalevin connection (not an atom)."
  [conn tx-id reconciled?]
  (set-reconciled-datom! conn tx-id :transaction/reconciled reconciled?)
  (with-derived-fields (d/pull (d/db conn) transaction-pull-pattern tx-id)))

(defn set-user-description!
  "Set (or clear) a transaction's user description — an additive overlay over the
   imported :transaction/description, which is never mutated. The value is trimmed; a
   blank/whitespace-only/nil description retracts the override so the row falls back to
   the imported description.
   Returns the refreshed transaction with the derived API fields.
   Conn is a datalevin connection (not an atom)."
  [conn tx-id description]
  (let [trimmed (some-> description str/trim not-empty)]
    (d/transact! conn (if trimmed
                        [{:db/id tx-id :transaction/user-description trimmed}]
                        [[:db/retract tx-id :transaction/user-description]])))
  (with-derived-fields (d/pull (d/db conn) transaction-pull-pattern tx-id)))

(defn set-user-posted-date!
  "Set (or clear) the manual posted-date override — an additive overlay over the
   imported :transaction/posted-date, which is never mutated. `date` nil clears the
   override, falling back to the next link in the effective chain (posted-date, then
   the plain transaction date — see data.ledger/effective-posted-date).

   Unlike set-user-description! (a per-row overlay), this is family-uniform: resolves
   tx-id to its split FAMILY ROOT (split-editor-root — a part resolves to its parent,
   same as the split editor), then asserts (or retracts) the override on the root AND
   every one of its live parts in ONE d/transact!, so calling this on any family member
   converges the whole family onto the same effective date. Modeled on
   set-reconciled-datom!'s assert/retract mechanics.

   Returns the refreshed ROOT with the derived API fields (see with-derived-fields).
   Conn is a datalevin connection (not an atom)."
  [conn tx-id date]
  (let [root (split-editor-root conn tx-id)
        root-id (:db/id root)
        ids (cons root-id (map :db/id (:transaction/_split-parent root)))]
    (d/transact! conn (if date
                        (mapv (fn [id] {:db/id id :transaction/user-posted-date date}) ids)
                        (mapv (fn [id] [:db/retract id :transaction/user-posted-date]) ids)))
    (with-derived-fields (d/pull (d/db conn) transaction-pull-pattern root-id))))

(defn update-category!
  "Update the category of a transaction.
   Pass nil for category-id to remove the category.
   Conn is a datalevin connection (not an atom)."
  [conn tx-id category-id]
  (let [tx-data (if category-id
                  [{:db/id tx-id :transaction/category category-id}]
                  ;; To remove, use retract operation
                  [[:db/retract tx-id :transaction/category]])]
    (d/transact! conn tx-data)
    (with-derived-fields (d/pull (d/db conn) transaction-pull-pattern tx-id))))

(defn- existing-category-ids
  "The subset of `ids` that are real category entities."
  [db ids]
  (set (d/q '[:find [?c ...]
              :in $ [?c ...]
              :where [?c :category/name _]]
            db ids)))

(defn- assert-splittable!
  "Validate that `tx-id` can accept `splits` against a freshly-read `db`: it exists,
   it isn't itself a split part (depth is 1 — edit the original transaction instead),
   it isn't an already-matched transfer when `splits` is non-empty (unmatch first, or
   a matched leg would be hidden from every list), and the amounts reconcile exactly
   to the parent's live amount. Throws ex-info (:type :not-found / :bad-request);
   returns nil. Called twice by set-splits! — once before the category check, once
   again immediately before writing — to narrow the read-modify-write window."
  [db tx-id splits]
  (let [parent (d/pull db '[:db/id :transaction/amount
                            {:transaction/split-parent [:db/id]}
                            {:transaction/transfer-pair [:db/id]}]
                       tx-id)]
    (when-not (:transaction/amount parent)
      (throw (ex-info "Transaction not found" {:type :not-found})))
    (when (:transaction/split-parent parent)
      (throw (ex-info "This row is part of a split — edit the split on the original transaction"
                      {:type :bad-request})))
    (when (and (seq splits) (:transaction/transfer-pair parent))
      (throw (ex-info "Unmatch this transfer before splitting" {:type :bad-request})))
    (when-let [err (splits/validate-splits (:transaction/amount parent) splits)]
      (throw (ex-info err {:type :bad-request})))))

(defn- new-part-tx-data
  "The transact! map for a brand-new split part (a row with no :id, or a stale :id
   that doesn't name a live part of this parent): the identity fields inherited from
   the parent (splits/inherited-fields, the same recipe the migration uses) plus this
   row's own amount/category/memo and its display order `i`."
  [parent i {:keys [amount category-id memo]}]
  (merge (splits/inherited-fields parent)
         (cond-> {:transaction/external-id (str "split-" (UUID/randomUUID))
                  :transaction/split-parent (:db/id parent)
                  :transaction/split-order i
                  :transaction/provider :split
                  :transaction/amount (bigdec amount)}
           category-id              (assoc :transaction/category category-id)
           (not (str/blank? memo))  (assoc :transaction/description memo))))

(defn- update-part-ops
  "Tx-data to update an existing live part in place: amount, display order `i`, category
   (nil clears it) and memo (blank/nil clears it). The part's reconciled flag,
   transfer-pair, and external-id are never mentioned here, so an editor edit can't
   clobber per-part state set elsewhere."
  [i {:keys [id amount category-id memo]}]
  (into [(cond-> {:db/id id
                  :transaction/amount (bigdec amount)
                  :transaction/split-order i}
           category-id              (assoc :transaction/category category-id)
           (not (str/blank? memo))  (assoc :transaction/description memo))]
        (cond-> []
          (nil? category-id)      (conj [:db/retract id :transaction/category])
          (str/blank? memo)       (conj [:db/retract id :transaction/description]))))

(defn- retract-part-ops
  "Tx-data to retract one live split part: unlink a transfer pair first — mirroring
   delete-manual!'s cascade — so retractEntity doesn't leave the partner's
   :transaction/transfer-pair dangling, then retract the part entity itself."
  [part]
  (let [id (:db/id part)
        partner (get-in part [:transaction/transfer-pair :db/id])]
    (cond-> []
      partner (conj [:db/retract partner :transaction/transfer-pair id])
      true    (conj [:db/retractEntity id]))))

(defn- split-parts-of
  "Live split parts of `parent-eids` (a collection of entity ids), pulled with just
   enough (:db/id + transfer-pair back-ref) to cascade-retract them via retract-part-ops."
  [db parent-eids]
  (d/q '[:find [(pull ?p [:db/id {:transaction/transfer-pair [:db/id]}]) ...]
         :in $ [?parent ...]
         :where [?p :transaction/split-parent ?parent]]
       db parent-eids))

(defn cascade-retract-parts-ops
  "Tx-data cascading every live part of `parent-eids` (a collection of entity ids):
   unlink each part's transfer pair first (mirroring set-splits!'s retract path), then
   retractEntity the part. [] when none of `parent-eids` has live parts. Shared with
   provider.sync/retract-removed! — a removed transaction's parts cascade the same way
   delete-manual!'s do (the bank says the money never happened, so its parts must go
   too). Callers append this to their own retraction of the parents so parent(s) +
   parts retract in one transact!."
  [db parent-eids]
  (mapcat retract-part-ops (split-parts-of db parent-eids)))

(defn set-splits!
  "Diff a transaction's splits against its current live parts and write only the
   delta, instead of full-replace. `splits` is a vector of {:amount string
   :category-id long? :memo string? :id long?} in display order; [] clears the
   splits (un-split).

   A row's :id, when it names a LIVE part of this parent, updates that part in place
   (amount/category/memo/order) — preserving its reconciled flag, transfer-pair link,
   and external-id. Any other row (no id, or a stale id — e.g. after undo re-creates
   a part that was previously retracted) creates a fresh part. A live part not named
   by any row is retracted, unlinking any transfer pair first.

   Always retracts the parent's own :transaction/reconciled overlay: a split row has no
   checkbox of its own once split (reconciling happens per-part), so the flag can't
   resurface on a later un-split — the user re-reconciles.

   See assert-splittable! for the :not-found / :bad-request guards (missing
   transaction, editing a part instead of its parent, an already-matched transfer,
   non-reconciling amounts, a category id that doesn't exist).

   Conn is a datalevin connection (not an atom). Returns the updated parent pulled
   with its parts and the derived API fields (see with-derived-fields)."
  [conn tx-id splits]
  (let [db1 (d/db conn)]
    (assert-splittable! db1 tx-id splits)
    (let [cat-ids (keep :category-id splits)]
      (when (and (seq cat-ids) (not (every? (existing-category-ids db1 cat-ids) cat-ids)))
        (throw (ex-info "Every split must reference an existing category" {:type :bad-request})))))
  ;; Re-read immediately before writing to narrow the read-modify-write window.
  ;; (Single-user app, so full serializability isn't worth a transaction fn.)
  (let [db (d/db conn)]
    (assert-splittable! db tx-id splits)
    (let [full-parent (d/pull db '[:db/id :transaction/account :transaction/user :transaction/date
                                   :transaction/posted-date :transaction/payee
                                   :transaction/user-posted-date] tx-id)
          live-parts (d/q '[:find [(pull ?p [:db/id {:transaction/transfer-pair [:db/id]}]) ...]
                            :in $ ?tx :where [?p :transaction/split-parent ?tx]]
                          db tx-id)
          live-ids (into #{} (map :db/id) live-parts)
          matched-ids (into #{} (filter live-ids) (keep :id splits))
          indexed (map-indexed vector splits)
          update-ops (mapcat (fn [[i row]] (when (live-ids (:id row)) (update-part-ops i row))) indexed)
          create-ops (keep (fn [[i row]] (when-not (live-ids (:id row)) (new-part-tx-data full-parent i row)))
                           indexed)
          retract-ops (mapcat retract-part-ops (remove #(matched-ids (:db/id %)) live-parts))
          ;; The parent has no reconciled checkbox of its own once split (each part
          ;; owns its reconcile mark), so drop any stored parent flag — otherwise it
          ;; would resurface if the splits are later cleared.
          reconciled-retract [[:db/retract tx-id :transaction/reconciled]]]
      (d/transact! conn (vec (concat reconciled-retract update-ops create-ops retract-ops)))
      (with-derived-fields (d/pull (d/db conn) transaction-pull-pattern tx-id)))))

;; --- Manual transactions ---------------------------------------------------
;; A user-entered transaction is a first-class :transaction/* row (provider :manual),
;; not an overlay: once created it categorizes, reconciles, splits and sorts
;; exactly like an imported one. Only its provenance and its direct create/delete
;; lifecycle differ.

(defn create-manual!
  "Insert a user-entered manual transaction and return its entity id.

   `tx` is {:account-eid long :amount bigdec :date Date :payee string?
            :description string? :category-id long?}. The amount is the canonical
   signed value (inflows +, outflows −) the caller derived from the money-out/-in
   choice, and is stored EXACTLY as given — a manual entry is deliberately NOT run
   through the provider invert-amount normalization (that flip is for imported data;
   re-applying it here would double-flip the sign on an inverted account).

   Stamped :transaction/provider :manual with a generated external-id (no dedup — each
   creation is a distinct row). Because the overlay contract forbids baking a category
   into an insert, an optional :category-id is applied as a separate update-category!
   after creation — exactly how an imported row gets categorized.
   Conn is a datalevin connection (not an atom)."
  [conn user-id {:keys [account-eid amount date payee description category-id]}]
  (when-not (and account-eid amount date)
    (throw (ex-info "A manual transaction needs an account, amount, and date"
                    {:type :bad-request})))
  (db-users/ensure-user! conn user-id)
  (let [ext (str "manual-" (UUID/randomUUID))]
    (d/transact! conn
                 [(cond-> {:transaction/external-id ext
                           :transaction/account     account-eid
                           :transaction/user        [:user/id user-id]
                           :transaction/date        date
                           :transaction/posted-date date
                           :transaction/amount      (bigdec amount)
                           :transaction/provider    :manual}
                    (not-empty (some-> payee str/trim))       (assoc :transaction/payee (str/trim payee))
                    (not-empty (some-> description str/trim)) (assoc :transaction/description (str/trim description)))])
    (let [eid (:db/id (d/pull (d/db conn) [:db/id] [:transaction/external-id ext]))]
      (when category-id (update-category! conn eid category-id))
      eid)))

(defn delete-manual!
  "Delete a manually-created transaction (entity id `tx-id`). Guards on
   :transaction/provider :manual so an imported/synced row — or a split part itself,
   which is :transaction/provider :split — can never be deleted this way: throws
   ex-info :bad-request otherwise (and :not-found for a missing id). Unlinks any
   transfer pair first (retracting the partner's back-reference, which retractEntity
   would otherwise leave dangling), then retracts the transaction. Parts are additive
   rows now, not :db/isComponent sub-entities, so a split MANUAL parent's live parts
   don't cascade for free — this cascades them explicitly the same way (unlink each
   part's transfer pair, then retract it), in the same transact!. Returns the vector of
   cascaded part ids (possibly empty), so the caller can purge them from the undo/redo
   command log (commands/forget!)."
  [conn tx-id]
  (let [db (d/db conn)
        tx (d/pull db [:db/id :transaction/provider
                       {:transaction/transfer-pair [:db/id]}] tx-id)]
    (when-not (:db/id tx)
      (throw (ex-info "Transaction not found" {:type :not-found})))
    (when-not (= :manual (:transaction/provider tx))
      (throw (ex-info "Only manually-added transactions can be deleted" {:type :bad-request})))
    (let [partner (get-in tx [:transaction/transfer-pair :db/id])
          parts (split-parts-of db [tx-id])
          part-ids (mapv :db/id parts)]
      (d/transact! conn (into (cond-> [[:db/retractEntity tx-id]]
                                partner (conj [:db/retract partner :transaction/transfer-pair tx-id]))
                              (mapcat retract-part-ops parts)))
      part-ids)))

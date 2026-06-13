(ns finance-aggregator.db.transactions
  (:require [clojure.string :as str]
            [datalevin.core :as d]
            [finance-aggregator.db.transfers :as db-transfers]
            [finance-aggregator.splits :as splits]))

(def split-pull
  "Pull sub-pattern for a transaction's split parts. Shared with the list endpoint
   (handlers.entities) so the two views never drift."
  [:db/id :split/amount :split/order :split/memo :split/reviewed
   {:split/category [:db/id :category/name]}])

(def transaction-pull-pattern
  "Canonical pull pattern for a transaction returned to the API. Shared by the list
   endpoint (handlers.entities) and the single-transaction mutation endpoints so the
   shapes never drift. The wildcard `*` returns a ref attribute as a bare {:db/id},
   so :transaction/transfer-pair is expanded explicitly: the server-side hide rule
   (db-transfers/with-transfer-hidden) reads the partner's category type, and the UI
   renders the partner's amount and posted date."
  ['* {:transaction/category [:db/id :category/name :category/type]
       :transaction/account [:db/id :account/external-name
                             {:account/institution [:db/id :institution/name]}]
       :transaction/splits split-pull
       :transaction/transfer-pair [:db/id :transaction/amount :transaction/posted-date
                                   {:transaction/category [:db/id :category/name :category/type]}
                                   {:transaction/account [:db/id :account/external-name]}]}])

(defn with-split-balance
  "Annotate a pulled transaction with :transaction/splits-balanced — the bigdec-exact
   reconciliation verdict — when it has splits, so clients never re-derive drift from
   lossy doubles. Transactions without splits are returned unchanged."
  [tx]
  (if-let [parts (seq (:transaction/splits tx))]
    (assoc tx :transaction/splits-balanced
           (splits/reconciled? (:transaction/amount tx) (map :split/amount parts)))
    tx))

(defn with-reviewed
  "Normalize :transaction/reviewed to the row's effective reviewed status. A split
   transaction has no checkbox of its own — it counts as reviewed only when every
   split part is reviewed — so the parent's stored flag is overridden by that roll-up
   (this is also what the reviewed filter keys off). Unsplit transactions are returned
   unchanged: their stored flag stands, absent meaning not reviewed."
  [tx]
  (if-let [parts (seq (:transaction/splits tx))]
    (assoc tx :transaction/reviewed (every? :split/reviewed parts))
    tx))

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

(defn with-derived-fields
  "Annotate a pulled transaction with the server-computed fields the API contract
   promises: :transaction/splits-balanced, :transaction/reviewed (effective),
   :transaction/effective-description and :transaction/transfer-hidden. Applied
   uniformly by the list endpoint and the single-transaction mutation endpoints so
   the response shape never drifts."
  [tx]
  (-> tx with-split-balance with-reviewed with-effective-description db-transfers/with-transfer-hidden))

(defn- set-reviewed-datom!
  "Assert (true) or clear (false) the boolean reviewed flag `attr` on entity `eid`.
   Clearing retracts the datom so its absence nil-puns to not-reviewed."
  [conn eid attr reviewed?]
  (d/transact! conn (if reviewed?
                      [{:db/id eid attr true}]
                      [[:db/retract eid attr]])))

(defn set-reviewed!
  "Mark a transaction reviewed (true) or clear it (false). Stored as an additive
   overlay on the imported transaction. Conn is a datalevin connection (not an atom)."
  [conn tx-id reviewed?]
  (set-reviewed-datom! conn tx-id :transaction/reviewed reviewed?)
  (with-derived-fields (d/pull (d/db conn) transaction-pull-pattern tx-id)))

(defn set-split-reviewed!
  "Mark a single split part reviewed (true) or clear it (false), independently of the
   parent and its siblings. Returns the parent transaction (tx-id) pulled with its
   parts and the derived API fields, so the caller can refresh the whole row —
   including the parent's now-recomputed effective reviewed roll-up.
   Conn is a datalevin connection (not an atom)."
  [conn tx-id split-id reviewed?]
  (set-reviewed-datom! conn split-id :split/reviewed reviewed?)
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

(defn set-split-memo!
  "Set (or clear) one split part's memo (its description), independently of the parent
   and its siblings. The value is trimmed; a blank/whitespace-only/nil memo retracts it.
   Returns the parent transaction (tx-id) refreshed with its parts and the derived API
   fields, so the caller can refresh the whole row.
   Conn is a datalevin connection (not an atom)."
  [conn tx-id split-id memo]
  (let [trimmed (some-> memo str/trim not-empty)]
    (d/transact! conn (if trimmed
                        [{:db/id split-id :split/memo trimmed}]
                        [[:db/retract split-id :split/memo]])))
  (with-derived-fields (d/pull (d/db conn) transaction-pull-pattern tx-id)))

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

(defn set-splits!
  "Replace a transaction's splits atomically (full-replace).
   `splits` is a vector of {:amount string :category-id long :memo string?} in
   display order; an empty vector clears the splits (un-split).

   The original transaction's own datoms are never touched. Validates that the
   parts reconcile exactly to the parent amount (bigdec) and that every category
   exists before writing; throws ex-info with :type :bad-request or :not-found
   otherwise.

   Conn is a datalevin connection (not an atom).
   Returns the updated transaction pulled with its parts and the derived API fields
   (see with-derived-fields)."
  [conn tx-id splits]
  (let [db (d/db conn)
        parent (d/pull db '[:db/id :transaction/amount] tx-id)]
    (when-not (:transaction/amount parent)
      (throw (ex-info "Transaction not found" {:type :not-found})))
    (when-let [err (splits/validate-splits (:transaction/amount parent) splits)]
      (throw (ex-info err {:type :bad-request})))
    (let [cat-ids (map :category-id splits)]
      (when (and (seq cat-ids)
                 (not (every? (existing-category-ids db cat-ids) cat-ids)))
        (throw (ex-info "Every split must reference an existing category"
                        {:type :bad-request}))))
    ;; Re-read immediately before writing to narrow the read-modify-write window.
    ;; (Single-user app, so full serializability isn't worth a transaction fn.)
    ;; Confirm the transaction still exists and its parts still reconcile to the
    ;; live amount before we touch anything.
    (let [db (d/db conn)
          fresh-amount (:transaction/amount (d/pull db '[:transaction/amount] tx-id))]
      (when-not fresh-amount
        (throw (ex-info "Transaction not found" {:type :not-found})))
      (when-let [err (splits/validate-splits fresh-amount splits)]
        (throw (ex-info err {:type :bad-request})))
      (let [old-eids (d/q '[:find [?s ...] :in $ ?tx
                            :where [?tx :transaction/splits ?s]]
                          db tx-id)
            ;; Component re-assert does NOT auto-retract prior parts; retract each explicitly.
            retract-ops (mapv (fn [eid] [:db/retractEntity eid]) old-eids)
            assert-ops (when (seq splits)
                         [{:db/id tx-id
                           :transaction/splits
                           (vec (map-indexed
                                 (fn [i s]
                                   (cond-> {:split/amount (bigdec (:amount s))
                                            :split/category (:category-id s)
                                            :split/order i}
                                     (:memo s) (assoc :split/memo (:memo s))))
                                 splits))}])
            ;; The parent has no reviewed checkbox of its own once split (each part
            ;; owns its review), so drop any stored parent flag — otherwise it would
            ;; resurface if the splits are later cleared.
            reviewed-retract [[:db/retract tx-id :transaction/reviewed]]]
        (d/transact! conn (into reviewed-retract (into retract-ops (or assert-ops []))))
        (with-derived-fields (d/pull (d/db conn) transaction-pull-pattern tx-id))))))

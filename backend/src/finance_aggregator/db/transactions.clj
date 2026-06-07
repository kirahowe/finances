(ns finance-aggregator.db.transactions
  (:require [datalevin.core :as d]
            [finance-aggregator.splits :as splits]))

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
    ;; Return the updated transaction with category info (minimal payload)
    (let [db (d/db conn)]
      (d/pull db '[* {:transaction/category [:db/id :category/name]
                      :transaction/account [:db/id :account/external-name]}] tx-id))))

(def split-pull
  "Pull sub-pattern for a transaction's split parts. Shared with the list endpoint
   (handlers.entities) so the two views never drift."
  [:db/id :split/amount :split/order :split/memo
   {:split/category [:db/id :category/name]}])

(def splits-pull-pattern
  ['* {:transaction/category [:db/id :category/name]
       :transaction/account [:db/id :account/external-name]
       :transaction/splits split-pull}])

(defn with-split-balance
  "Annotate a pulled transaction with :transaction/splits-balanced — the bigdec-exact
   reconciliation verdict — when it has splits, so clients never re-derive drift from
   lossy doubles. Transactions without splits are returned unchanged."
  [tx]
  (if-let [parts (seq (:transaction/splits tx))]
    (assoc tx :transaction/splits-balanced
           (splits/reconciled? (:transaction/amount tx) (map :split/amount parts)))
    tx))

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
   Returns the updated transaction pulled with its parts, annotated with
   :transaction/splits-balanced."
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
                                 splits))}])]
        (d/transact! conn (into retract-ops (or assert-ops [])))
        (with-split-balance (d/pull (d/db conn) splits-pull-pattern tx-id))))))

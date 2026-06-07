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

(def splits-pull-pattern
  '[* {:transaction/category [:db/id :category/name]
       :transaction/account [:db/id :account/external-name]
       :transaction/splits [:db/id :split/amount :split/order :split/memo
                            {:split/category [:db/id :category/name]}]}])

(defn set-splits!
  "Replace a transaction's splits atomically (full-replace).
   `splits` is a vector of {:amount string :category-id long :memo string?} in
   display order; an empty vector clears the splits (un-split).

   The original transaction's own datoms are never touched. Validates that the
   parts reconcile exactly to the parent amount (bigdec) before writing; throws
   ex-info with :type :bad-request or :not-found otherwise.

   Conn is a datalevin connection (not an atom).
   Returns the updated transaction pulled with its parts."
  [conn tx-id splits]
  (let [db (d/db conn)
        parent (d/pull db '[:db/id :transaction/amount] tx-id)]
    (when-not (:transaction/amount parent)
      (throw (ex-info "Transaction not found" {:type :not-found})))
    (when-let [err (splits/validate-splits (:transaction/amount parent) splits)]
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
      (d/pull (d/db conn) splits-pull-pattern tx-id))))

(ns finance-aggregator.db.transactions
  (:require [datalevin.core :as d]))

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

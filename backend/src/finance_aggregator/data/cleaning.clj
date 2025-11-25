(ns finance-aggregator.data.cleaning
  (:require [tablecloth.api :as tc]))

(defn find-likely-dupes
  "Returns a tablecloth dataset of transactions that appear to be duplicates.
   Duplicates are defined as transactions with the same posted-date, amount,
   description, payee, and memo, but different external-ids and accounts.
   Returns full transaction entities."
  [transactions]
  (when (seq transactions)
    (-> transactions
        tc/dataset
        (tc/group-by [:transaction/posted-date
                      :transaction/amount
                      :transaction/description
                      :transaction/payee
                      :transaction/memo])
        (tc/add-column :group-size tc/row-count)
        (tc/ungroup)
        (tc/select-rows #(> (:group-size %) 1))
        (tc/order-by [:transaction/transaction-date :transaction/amount]))))

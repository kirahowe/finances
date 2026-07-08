(ns finance-aggregator.provider.contract
  "The provider->persistence contract, made explicit and enforced.

   Imported transactions are append-only source-of-truth; user edits live in
   *overlay* attributes. db/insert! upserts by :transaction/external-id and only
   asserts the keys present in each map - it never retracts attributes a map
   omits - so re-importing a transaction (including applying a Plaid `modified`)
   leaves the user's overlay attributes untouched, as long as no provider parser
   ever emits one. That safety is currently implicit; this namespace makes it a
   checked invariant and fails loudly the day a parser regresses.

   Pure: data in, assert, data out.")

(def overlay-keys
  "Transaction attributes owned by the user. Sync must never write them, so they
   survive every re-import. (Provenance/imported fields - description, amount,
   payee, dates - are fair game for `modified` to update.)"
  #{:transaction/user-description
    :transaction/reviewed
    :transaction/category
    :transaction/splits
    :transaction/split-parent
    :transaction/split-order
    :transaction/transfer-pair
    :transaction/transfer-rejected})

(defn assert-no-overlay-keys!
  "Throw if any transaction carries a user-overlay key (a provider parser leaking
   into user-owned state). Returns the transactions unchanged so it can sit inline
   in the persist path."
  [transactions]
  (doseq [txn transactions]
    (when-let [bad (seq (filter (set (keys txn)) overlay-keys))]
      (throw (ex-info "Provider transaction carries user-overlay keys; sync must never write them"
                      {:overlay-keys (vec bad)
                       :transaction/external-id (:transaction/external-id txn)}))))
  transactions)

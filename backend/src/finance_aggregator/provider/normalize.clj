(ns finance-aggregator.provider.normalize
  "The one canonical amount-normalization point for provider ingest.

   Each provider's parser already translates that provider's native sign
   convention to the app canonical (inflows positive, outflows negative): Plaid
   negates, Lunchflow passes through, CSV/manual take the file's sign. On top of
   that, an account may carry :account/invert-amount when even the canonical sign
   is backwards for how the user wants that account read. This namespace applies
   that account-scoped flip - exactly once, at import, uniformly for every
   provider - so no read path has to re-derive it.

   Pure transform: data in, data out. No I/O. (The set of inverted accounts is a
   Data-layer query - db.accounts/inverted-account-ids - supplied by the caller.)")

(defn- account-external-id
  "The account external-id a transaction is keyed to, or nil if it isn't shaped
   as the canonical [:account/external-id <id>] lookup ref."
  [txn]
  (let [acct (:transaction/account txn)]
    (when (and (vector? acct) (= :account/external-id (first acct)))
      (second acct))))

(defn normalize-amounts
  "Flip :transaction/amount for transactions whose account is in
   `inverted-external-ids` (the accounts with :account/invert-amount true).
   Transactions for other accounts - or any whose account ref isn't a canonical
   lookup ref, or that carry no amount - pass through untouched. Idempotent per
   import because each import re-parses the provider's raw canonical amount."
  [transactions inverted-external-ids]
  (let [inverted (set inverted-external-ids)]
    (if (empty? inverted)
      (vec transactions)
      (mapv (fn [txn]
              (if (and (contains? inverted (account-external-id txn))
                       (contains? txn :transaction/amount))
                (update txn :transaction/amount -)
                txn))
            transactions))))

(ns finance-aggregator.splits
  "Pure split domain logic. No I/O.

   A split divides one transaction into >=2 parts. The parts must reconcile exactly
   to the parent amount. Amounts arrive as strings to preserve bigdec precision
   (JSON numbers round-trip through doubles).")

(defn- parse-amount [s]
  (try (when (string? s) (bigdec s)) (catch Exception _ nil)))

(defn reconciled?
  "Whether `amounts` (BigDecimals) sum exactly to `parent-amount` (BigDecimal).
   Uses == so it is scale-insensitive (100M and 100.00M are equal)."
  [parent-amount amounts]
  (== parent-amount (reduce + 0M amounts)))

(defn inherited-fields
  "The identity fields a split part inherits from its parent transaction, copied only
   when the parent actually has them (e.g. a manual transaction may lack a payee).
   `parent` is a wildcard/bare-attribute-pulled transaction map, so its ref attributes
   are already {:db/id x} maps — the returned map assocs the bare eid, ready to nest
   into a part's transact! map. Shared by the migration and set-splits! so a part's
   inherited fields never drift between the two places that create one.

   :transaction/user-posted-date rides along with the rest: a manual posted-date
   override is family-uniform (db.transactions/set-user-posted-date! asserts it on the
   root and every live part in one transact!), so a part created AFTER the override was
   set — via set-splits! — is born already carrying it, instead of the family
   momentarily diverging until the next propagate-inherited-fields! pass."
  [parent]
  (cond-> {}
    (:transaction/account parent)     (assoc :transaction/account (get-in parent [:transaction/account :db/id]))
    (:transaction/user parent)        (assoc :transaction/user (get-in parent [:transaction/user :db/id]))
    (:transaction/date parent)        (assoc :transaction/date (:transaction/date parent))
    (:transaction/posted-date parent) (assoc :transaction/posted-date (:transaction/posted-date parent))
    (:transaction/payee parent)       (assoc :transaction/payee (:transaction/payee parent))
    (:transaction/user-posted-date parent) (assoc :transaction/user-posted-date (:transaction/user-posted-date parent))))

(defn validate-splits
  "Validate a full set of splits for a transaction. Pure.

   parent-amount - BigDecimal, the authoritative transaction amount (from the DB).
   splits        - vector of {:amount string :category-id long? :memo string? :id long?}.
                   A part may be uncategorized (nil :category-id — the Uncategorized
                   chip owns it); :id, when present, names an existing part to update.

   Returns an error string, or nil when valid. An empty vector is valid and means
   \"clear the splits\" (un-split)."
  [parent-amount splits]
  (cond
    (empty? splits) nil
    (< (count splits) 2) "A split must have at least 2 parts"
    :else
    (let [amounts (map (comp parse-amount :amount) splits)]
      (cond
        (some nil? amounts) "Every split amount must be a valid number"
        (some zero? amounts) "Split amounts must be non-zero"
        (not (reconciled? parent-amount amounts)) "Splits must add up to the transaction amount"))))

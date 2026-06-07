(ns finance-aggregator.splits
  "Pure split domain logic. No I/O.

   A split divides one transaction into >=2 parts, each with its own category. The
   parts must reconcile exactly to the parent amount. Amounts arrive as strings to
   preserve bigdec precision (JSON numbers round-trip through doubles).")

(defn- parse-amount [s]
  (try (when (string? s) (bigdec s)) (catch Exception _ nil)))

(defn reconciled?
  "Whether `amounts` (BigDecimals) sum exactly to `parent-amount` (BigDecimal).
   Uses == so it is scale-insensitive (100M and 100.00M are equal)."
  [parent-amount amounts]
  (== parent-amount (reduce + 0M amounts)))

(defn validate-splits
  "Validate a full set of splits for a transaction. Pure.

   parent-amount - BigDecimal, the authoritative transaction amount (from the DB).
   splits        - vector of {:amount string :category-id long :memo string?}.

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
        (some #(nil? (:category-id %)) splits) "Every split needs a category"
        (not (reconciled? parent-amount amounts)) "Splits must add up to the transaction amount"))))

(ns finance-aggregator.transfers
  "Pure transfer-matching domain logic. No I/O.

   A transfer link pairs two real transactions that represent the same money
   moving between two of the user's accounts: an outflow from one (amount < 0) and
   an inflow to another (amount > 0). Linking is purely a de-noising relationship;
   it never changes a transaction's category. Auto-suggestion proposes pairs that
   the user reviews and confirms.

   The matcher operates on normalized maps so it stays independent of storage:
     {:id long :amount bigdec :day long(epoch-day) :account-id long
      :real? bool :paired? bool :rejected #{partner-ids}}")

(defn real-activity?
  "True when a category type marks a transaction as real spending or income
   (:expense / :income), as opposed to a transfer or uncategorized. Such legs are
   never auto-suggested for matching, and keep a matched pair visible."
  [category-type]
  (contains? #{:expense :income} category-type))

(defn opposite-amounts?
  "True when two amounts are equal in magnitude and opposite in sign (and therefore
   both non-zero) — the core of what makes two legs a transfer. nil-safe: a missing
   amount is never a valid leg, so returns false rather than throwing."
  [a b]
  (boolean (and a b (not (zero? a)) (== a (- b)))))

(defn- within-window?
  "Whether two epoch-days are within `window-days` of each other. nil-safe: an
   undated leg can't be windowed, so returns false — auto-match needs both dates."
  [day-a day-b window-days]
  (boolean (and day-a day-b (<= (abs (- day-a day-b)) window-days))))

(defn hidden-transfer?
  "Whether a transaction is removed by the Hide-transfers toggle: it is part of a
   matched pair AND neither leg is real expense/income. `self-type`/`partner-type`
   are category types (:expense / :income / :transfer / nil)."
  [matched? self-type partner-type]
  (and matched?
       (not (real-activity? self-type))
       (not (real-activity? partner-type))))

(defn- candidate-pair?
  "Whether outflow `a` and inflow `b` are a valid auto-match candidate. Direction
   (a outflow, b inflow) is guaranteed by the caller's partitioning."
  [a b window-days]
  (and (not= (:account-id a) (:account-id b))
       (opposite-amounts? (:amount a) (:amount b))
       (within-window? (:day a) (:day b) window-days)
       (not (contains? (:rejected a) (:id b)))
       (not (contains? (:rejected b) (:id a)))))

(defn suggest-matches
  "Propose transfer pairs from a collection of normalized transaction maps.

   opts: {:window-days int} (default 3) — max absolute day difference.

   Legs that are already paired or are real expense/income are excluded up front.
   All valid candidate pairs are ordered by date proximity (closest first, then by
   id for determinism) and assigned greedily so each transaction is used at most
   once. Returns a vector of {:outflow-id :inflow-id :amount :day-diff}."
  [txns {:keys [window-days] :or {window-days 3}}]
  (let [eligible (->> txns
                      (remove #(or (:paired? %) (:real? %)))
                      (filter :amount))
        outflows (filter #(neg? (:amount %)) eligible)
        inflows  (filter #(pos? (:amount %)) eligible)
        candidates (for [a outflows
                         b inflows
                         :when (candidate-pair? a b window-days)]
                     {:outflow-id (:id a)
                      :inflow-id (:id b)
                      :amount (:amount b)
                      :day-diff (abs (- (:day a) (:day b)))})
        ordered (sort-by (juxt :day-diff :outflow-id :inflow-id) candidates)]
    (loop [[pair & more] ordered
           used #{}
           acc []]
      (cond
        (nil? pair) acc
        (or (used (:outflow-id pair)) (used (:inflow-id pair))) (recur more used acc)
        :else (recur more
                     (conj used (:outflow-id pair) (:inflow-id pair))
                     (conj acc pair))))))

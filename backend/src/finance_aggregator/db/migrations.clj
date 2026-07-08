(ns finance-aggregator.db.migrations
  "One-time, idempotent data migrations, run at every db.core/start-db! so every
   entry point (the app, a REPL, a test that opens its own connection) opens onto a
   migrated database. Each migration gates on its own precondition, so re-running it
   is always a no-op — there's no separate migration-ran bookkeeping to maintain."
  (:require [datalevin.core :as d]
            [finance-aggregator.splits :as splits])
  (:import [java.util UUID]))

;; --- Split parts: :transaction/splits sub-entities → first-class :transaction/* rows ---
;; See doc/plans/splits-as-transactions.md. A part used to be a :split/* component
;; sub-entity hanging off its parent; it's now a real :transaction/* row linked back to
;; the parent via :transaction/split-parent, so every transaction-grain feature (filters,
;; review, categorize, transfer matching…) covers splits for free.

(def ^:private old-split-pull
  [:db/id :split/amount :split/order :split/memo :split/reviewed
   {:split/category [:db/id]}])

(def ^:private parent-pull
  ['* {:transaction/splits old-split-pull}])

(defn- part-tx-data
  "The transact! map for one migrated split part: the identity fields inherited from
   the parent (splits/inherited-fields) plus the sub-entity's own amount/memo/category/
   reviewed, carried across to the new :transaction/* shape. A fresh external-id and
   :transaction/provider :split mark it as generated, user-authored, no-dedup — the
   same provenance convention as a manual transaction."
  [parent {:split/keys [amount order memo category reviewed]}]
  (merge (splits/inherited-fields parent)
         (cond-> {:transaction/external-id (str "split-" (UUID/randomUUID))
                  :transaction/split-parent (:db/id parent)
                  :transaction/split-order order
                  :transaction/provider :split
                  :transaction/amount amount}
           memo             (assoc :transaction/description memo)
           category         (assoc :transaction/category (:db/id category))
           (true? reviewed) (assoc :transaction/reviewed true))))

(defn migrate-splits!
  "Promote every old-model split sub-entity (:transaction/splits / :split/*) to a
   first-class :transaction/* part row, then retract the sub-entities. Idempotent:
   only parents that still carry :transaction/splits are touched, so once every
   parent is migrated a second run finds nothing and is a no-op.

   Throws ex-info when a parent-with-splits lacks :transaction/posted-date — a part
   without one vanishes from every month/range query, so this fails loudly instead
   of silently orphaning data. Conn is a datalevin connection (not an atom)."
  [conn]
  (let [db (d/db conn)
        parent-ids (d/q '[:find [?tx ...] :where [?tx :transaction/splits _]] db)]
    (when (seq parent-ids)
      (let [parents (mapv #(d/pull db parent-pull %) parent-ids)]
        (doseq [p parents]
          (when-not (:transaction/posted-date p)
            (throw (ex-info "Cannot migrate a split parent with no posted-date"
                            {:type :migration-error :parent (:db/id p)}))))
        (let [creates (mapcat (fn [p] (map #(part-tx-data p %) (:transaction/splits p))) parents)
              retracts (mapcat (fn [p] (map (fn [s] [:db/retractEntity (:db/id s)]) (:transaction/splits p)))
                               parents)]
          (d/transact! conn (vec (concat creates retracts))))))))

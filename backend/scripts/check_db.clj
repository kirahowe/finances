(ns check-db
  (:require
   [datalevin.core :as d]
   [finance-aggregator.data.schema :as schema]))

(def db-path "data/finance.db")

(let [conn (d/get-conn db-path schema/schema)
      db (d/db conn)]

  (println "\n=== Database Contents ===\n")

  ;; Check for institutions
  (println "Institutions with :institution/id:")
  (let [results (d/q '[:find [(pull ?e [*]) ...]
                       :where [?e :institution/id _]]
                     db)]
    (println "  Count:" (count results))
    (when (seq results)
      (println "  Sample:" (first results))))

  ;; Check for accounts with :account/external-id
  (println "\nAccounts with :account/external-id:")
  (let [results (d/q '[:find [(pull ?e [*]) ...]
                       :where [?e :account/external-id _]]
                     db)]
    (println "  Count:" (count results))
    (when (seq results)
      (println "  Sample:" (first results))))

  ;; Check for transactions with :transaction/external-id
  (println "\nTransactions with :transaction/external-id:")
  (let [results (d/q '[:find [(pull ?e [*]) ...]
                       :where [?e :transaction/external-id _]]
                     db)]
    (println "  Count:" (count results))
    (when (seq results)
      (println "  Sample:" (first results))))

  ;; Check for categories
  (println "\nCategories:")
  (let [results (d/q '[:find [(pull ?e [*]) ...]
                       :where [?e :category/name _]]
                     db)]
    (println "  Count:" (count results))
    (when (seq results)
      (println "  Sample:" (first results))))

  ;; Check all entities to see what's actually there
  (println "\nAll entities (first 5):")
  (let [results (d/q '[:find [(pull ?e [*]) ...]
                       :where [?e :db/id _]]
                     db)]
    (println "  Total count:" (count results))
    (doseq [entity (take 5 results)]
      (println "  -" entity)))

  (d/close conn))

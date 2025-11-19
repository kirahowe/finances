(ns finance-aggregator.db.categories
  (:require [datalevin.core :as d]))

(defn create!
  "Create a new category in the database.
   Category map should contain :category/name, :category/type, and :category/ident.
   Returns the created entity as a map with :db/id.
   Conn is a datalevin connection (not an atom)."
  [conn category-data]
  ;; If the ident already exists, this will fail with an exception
  ;; which is what we want for the uniqueness test
  (d/transact! conn [category-data])
  ;; Use pull to get the entity with :db/id included
  (let [ident (:category/ident category-data)
        db (d/db conn)]
    (d/pull db '[*] [:category/ident ident])))

(defn list-all
  "Return all categories from the database.
   Conn is a datalevin connection (not an atom)."
  [conn]
  (d/q '[:find [(pull ?e [*]) ...]
         :where [?e :category/name _]]
       (d/db conn)))

(defn get-by-id
  "Get a category by its db/id. Returns nil if not found.
   Conn is a datalevin connection (not an atom)."
  [conn db-id]
  (let [db (d/db conn)
        result (d/pull db '[*] db-id)]
    (when (:category/name result)
      result)))

(defn get-by-ident
  "Get a category by its :category/ident. Returns nil if not found.
   Conn is a datalevin connection (not an atom)."
  [conn ident]
  (let [db (d/db conn)
        result (d/pull db '[*] [:category/ident ident])]
    (when (:category/name result)
      result)))

(defn update!
  "Update a category. Takes db/id and a map of attributes to update.
   Returns the updated entity.
   Conn is a datalevin connection (not an atom)."
  [conn db-id updates]
  (let [tx-data (assoc updates :db/id db-id)]
    (d/transact! conn [tx-data])
    (let [db (d/db conn)]
      (d/pull db '[*] db-id))))

(defn delete!
  "Delete a category by db/id.
   TODO: Add check to prevent deletion if category has assigned transactions.
   Conn is a datalevin connection (not an atom)."
  [conn db-id]
  (d/transact! conn [[:db/retractEntity db-id]]))

(defn has-transactions?
  "Check if a category has any transactions assigned to it.
   Conn is a datalevin connection (not an atom)."
  [conn category-id]
  (let [db (d/db conn)]
    (not (empty? (d/q '[:find ?tx
                        :in $ ?cat-id
                        :where [?tx :transaction/category ?cat-id]]
                      db
                      category-id)))))

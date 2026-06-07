(ns finance-aggregator.db
  (:require
   [datalevin.core :as d])
  (:import
   [java.util Date]))

(defn- referenced-user-ids
  "User ids referenced by accounts via the :account/user lookup ref
   ([:user/id id]). Returns a set."
  [accounts]
  (into #{}
        (comp (keep :account/user)
              (filter #(and (vector? %) (= :user/id (first %))))
              (map second))
        accounts))

(defn- ensure-users!
  "Upsert user entities referenced by accounts so the :account/user lookup refs
   resolve. New users get a :user/created-at; existing users are left untouched
   (the upsert would only re-assert the identity attr). Without this, inserting
   an account that references a not-yet-created user fails with entity-id/missing."
  [db-conn accounts]
  (let [db (d/db db-conn)
        new-ids (remove #(d/entity db [:user/id %]) (referenced-user-ids accounts))]
    (when (seq new-ids)
      (d/transact! db-conn (mapv (fn [id] {:user/id id :user/created-at (Date.)}) new-ids)))))

(defn insert!
  "Insert entities into the datalevin database.
   Entities must be a map with :institutions, :accounts, and :transactions keys.
   All entities should already be transformed into database-ready format.
   Inserts in order to satisfy referential integrity.

   Connection must be provided via dependency injection (integrant system).

   entities: {:institutions set-of-institution-maps
              :accounts set-of-account-maps
              :transactions vector-of-transaction-maps}
   db-conn: Datalevin connection from integrant system

   Returns: the connection for convenience"
  [{:keys [institutions accounts transactions]} db-conn]
  ;; Insert in order: users (referenced by accounts), institutions, accounts,
  ;; then transactions - so every lookup ref resolves to an existing entity.
  (ensure-users! db-conn accounts)
  (d/transact! db-conn institutions)
  (d/transact! db-conn accounts)
  (d/transact! db-conn transactions)
  ;; Return the connection for convenience
  db-conn)

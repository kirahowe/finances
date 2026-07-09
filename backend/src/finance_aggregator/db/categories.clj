(ns finance-aggregator.db.categories
  (:require [datalevin.core :as d]
            [finance-aggregator.categories :as cat]))

(defn- pull-category
  "Pull a category by db/id from a db snapshot, or nil if it doesn't exist."
  [db db-id]
  (let [result (d/pull db '[*] db-id)]
    (when (:category/name result)
      result)))

(defn- has-children?
  "Whether any category names db-id as its parent, in the given db snapshot."
  [db db-id]
  (boolean (seq (d/q '[:find [?c ...]
                       :in $ ?parent
                       :where [?c :category/parent ?parent]]
                     db db-id))))

(defn- reject-bad-request! [message]
  (when message
    (throw (ex-info message {:type :bad-request}))))

(defn create!
  "Create a new category in the database.
   Category map should contain :category/name, :category/type, and :category/ident.
   An optional :category/parent (db/id) must reference an existing top-level
   category.
   Returns the created entity as a map with :db/id.
   Conn is a datalevin connection (not an atom)."
  [conn category-data]
  (when-let [parent-id (:category/parent category-data)]
    (let [db (d/db conn)]
      (reject-bad-request!
       (cat/validate-parent parent-id (pull-category db parent-id) nil false))))
  ;; If the ident already exists, this will fail with an exception
  ;; which is what we want for the uniqueness test
  (d/transact! conn [category-data])
  ;; Use pull to get the entity with :db/id included
  (let [ident (:category/ident category-data)
        db (d/db conn)]
    (d/pull db '[*] [:category/ident ident])))

(defn create-many!
  "Create multiple categories in a single atomic transaction.

   Each item is a map containing :category/name, :category/type, :category/ident,
   a :tempid (any value unique within the batch), and an optional :parent-tempid
   that references another item's :tempid. Within-batch parent references are
   resolved via Datalevin tempids, so a parent and its children can be created
   together in one call.

   Returns the created categories pulled by ident, in input order.
   Conn is a datalevin connection (not an atom)."
  [conn items]
  (reject-bad-request! (cat/validate-batch items))
  (let [tempid->eid (into {} (map-indexed (fn [i item] [(:tempid item) (- (inc i))]) items))
        tx-data (mapv (fn [{:keys [tempid parent-tempid] :as item}]
                        (let [parent-eid (when parent-tempid (tempid->eid parent-tempid))]
                          (cond-> {:db/id (tempid->eid tempid)
                                   :category/name (:category/name item)
                                   :category/type (:category/type item)
                                   :category/ident (:category/ident item)}
                            parent-eid (assoc :category/parent parent-eid))))
                      items)]
    (d/transact! conn tx-data)
    (let [db (d/db conn)]
      (mapv (fn [item] (d/pull db '[*] [:category/ident (:category/ident item)])) items))))

(defn list-all
  "Return all categories from the database, sorted by sort-order.
   Categories without sort-order appear last.
   Conn is a datalevin connection (not an atom)."
  [conn]
  (let [categories (d/q '[:find [(pull ?e [*]) ...]
                          :where [?e :category/name _]]
                        (d/db conn))]
    ;; Sort by :category/sort-order, with nil values at the end
    (sort-by (fn [cat]
               (if-let [order (:category/sort-order cat)]
                 order
                 Long/MAX_VALUE))
             categories)))

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
   A :category/parent of nil clears the parent (retracts the ref); a non-nil
   :category/parent must reference an existing top-level category, and the
   category being updated must not itself have children.
   Returns the updated entity.
   Conn is a datalevin connection (not an atom)."
  [conn db-id updates]
  (let [clear-parent? (and (contains? updates :category/parent)
                           (nil? (:category/parent updates)))
        set-parent-id (when-not clear-parent? (:category/parent updates))]
    (when set-parent-id
      (let [db (d/db conn)]
        (reject-bad-request!
         (cat/validate-parent set-parent-id (pull-category db set-parent-id)
                              db-id (has-children? db db-id)))))
    (let [set-updates (cond-> updates clear-parent? (dissoc :category/parent))
          tx-data (cond-> []
                    (seq set-updates) (conj (assoc set-updates :db/id db-id))
                    clear-parent? (conj [:db/retract db-id :category/parent]))]
      (when (seq tx-data)
        (d/transact! conn tx-data)))
    (let [db (d/db conn)]
      (d/pull db '[*] db-id))))

(defn delete!
  "Delete a category by db/id.
   TODO: Add check to prevent deletion if category has assigned transactions.
   Conn is a datalevin connection (not an atom)."
  [conn db-id]
  (d/transact! conn [[:db/retractEntity db-id]]))

(defn in-use?
  "Whether a category is referenced by any transaction. A split part is a normal
   :transaction/* row, so its :transaction/category ref is covered like any other.
   Deleting an in-use category would orphan those refs, so callers should block on
   this. Conn is a datalevin connection (not an atom)."
  [conn category-id]
  (let [db (d/db conn)]
    (boolean (seq (d/q '[:find ?e
                         :in $ ?cat-id
                         :where [?e :transaction/category ?cat-id]]
                       db
                       category-id)))))

(defn batch-update-sort-orders!
  "Batch update sort orders for multiple categories.
   Takes a sequence of maps with :db/id and :category/sort-order.
   Returns the updated categories.
   Conn is a datalevin connection (not an atom)."
  [conn updates]
  (let [tx-data (mapv (fn [{:keys [db/id category/sort-order]}]
                        {:db/id id
                         :category/sort-order sort-order})
                      updates)]
    (d/transact! conn tx-data)
    ;; Return all updated categories
    (let [db (d/db conn)]
      (mapv #(d/pull db '[*] (:db/id %)) updates))))

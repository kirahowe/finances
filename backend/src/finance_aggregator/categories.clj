(ns finance-aggregator.categories
  "Pure category domain logic. No I/O - callers (db.categories) do the reads and
   translate a returned error string into a thrown bad-request.

   Categories form a single-level hierarchy: a top-level category may have
   children, but a child may not itself have children. These validators enforce
   that invariant so it holds regardless of which client wrote the data.")

(defn validate-parent
  "Validate assigning `parent-id` as the parent of a category. Pure: the caller
   passes already-resolved facts. Returns an error string, or nil when valid.

   - parent-id           the eid being set as parent (non-nil)
   - parent              the parent's pulled record, or nil if it doesn't exist
   - child-id            the eid of the category being updated, or nil on create
   - child-has-children? whether the category being updated already has children"
  [parent-id parent child-id child-has-children?]
  (cond
    (nil? parent)
    "Parent category does not exist"

    (and child-id (= parent-id child-id))
    "A category cannot be its own parent"

    (:category/parent parent)
    "Parent must be a top-level category"

    child-has-children?
    "A category with sub-categories cannot become a child"))

(defn validate-assignable
  "Validate assigning a category directly to a transaction or split part. Pure: the
   caller passes the resolved fact. A category that has sub-categories is a group
   header, not an assignable category. Returns an error string, or nil when valid."
  [has-children?]
  (when has-children?
    "Cannot assign a category that has sub-categories"))

(defn validate-batch
  "Validate a create-many! batch for the single-level invariant. Pure - parent
   links are by within-batch :tempid. Returns an error string, or nil when valid.
   Each item: {:tempid .. :parent-tempid .. :category/name ..}."
  [items]
  (let [by-tempid (into {} (map (juxt :tempid identity)) items)]
    (some (fn [{:keys [parent-tempid] :as item}]
            (when parent-tempid
              (let [parent (by-tempid parent-tempid)
                    label (or (:category/name item) (:tempid item))]
                (cond
                  (nil? parent)
                  (str "Unknown parent reference for \"" label "\"")

                  (:parent-tempid parent)
                  (str "\"" label "\" nests more than one level deep")))))
          items)))

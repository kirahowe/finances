(ns finance-aggregator.web.commands
  "Per-user undo/redo command log for the server-authoritative workspace (Phase R2 cp2).

   Every user edit is a Command — {:type :tx-id :before :after :label} — applied by running
   the existing db mutation to :after, undone by running it to :before. The database stays
   the source of truth; this log only records *how to reverse* each action (it rides on the
   append-only-overlay edit model). State is per-user (single-user for now, auth/user-id) and
   in-memory (a defonce atom): undo is a UI affordance, not durable state. The Command is plain
   data, so persisting the log later (audit trail / cross-session redo) needs no caller change.

   `:linger` accumulates the tx ids touched since the last pure view change, so a row edited
   out of an active filter stays visible (is-stale) instead of vanishing; the /v2/rows view
   handler clears it."
  (:require
   [finance-aggregator.db.transactions :as db]))

(defonce ^:private log (atom {}))

(def ^:private empty-state {:undo [] :redo [] :linger #{}})

(defn- state [user] (get @log user empty-state))

(defn- mutate!
  "Apply command `cmd`'s mutation, setting the field to `value` (the :after on apply, the
   :before on undo)."
  [conn {:keys [type tx-id]} value]
  (case type
    :set-reviewed    (db/set-reviewed! conn tx-id (boolean value))
    :set-category    (db/update-category! conn tx-id value)
    :set-description (db/set-user-description! conn tx-id (or value ""))
    ;; value is the full split vector ({:amount :category-id :memo?}); [] un-splits. Undo
    ;; re-applies :before (the prior parts, or [] when the row wasn't split). Full-replace.
    :set-splits      (db/set-splits! conn tx-id (vec value))))

(defn apply!
  "Run a command (to its :after), push it onto the undo stack, mark its tx lingering, and
   clear the redo stack. Returns the command."
  [conn user {:keys [tx-id after] :as cmd}]
  (mutate! conn cmd after)
  (swap! log update user (fn [s]
                           (-> (or s empty-state)
                               (update :undo conj cmd)
                               (assoc :redo [])
                               (update :linger conj tx-id))))
  cmd)

(defn undo!
  "Reverse the last command (run it to :before), move it to the redo stack, and keep its tx
   lingering. Returns the reversed command, or nil if there's nothing to undo."
  [conn user]
  (when-let [cmd (peek (:undo (state user)))]
    (mutate! conn cmd (:before cmd))
    (swap! log update user (fn [s]
                             (-> s
                                 (update :undo pop)
                                 (update :redo conj cmd)
                                 (update :linger conj (:tx-id cmd)))))
    cmd))

(defn redo!
  "Re-apply the last undone command (run it to :after). Returns it, or nil if nothing to redo."
  [conn user]
  (when-let [cmd (peek (:redo (state user)))]
    (mutate! conn cmd (:after cmd))
    (swap! log update user (fn [s]
                             (-> s
                                 (update :redo pop)
                                 (update :undo conj cmd)
                                 (update :linger conj (:tx-id cmd)))))
    cmd))

(defn linger
  "The set of tx ids touched since the last view change (rows to keep visible/stale)."
  [user]
  (:linger (state user)))

(defn clear-linger!
  "Drop all lingering pins — called on any pure view change (filter/sort/paginate)."
  [user]
  (swap! log update user #(assoc (or % empty-state) :linger #{})))

(defn undo-label
  "Label of the action a press of Undo would reverse (drives the undo button's enabled
   state + tooltip), or nil when there's nothing to undo."
  [user]
  (:label (peek (:undo (state user)))))

(defn redo-label
  "Label of the action a press of Redo would re-apply, or nil when there's nothing to redo."
  [user]
  (:label (peek (:redo (state user)))))

(defn clear-all!
  "Drop the entire log (every user's undo/redo/linger). For test isolation — the e2e
   /e2e/reset re-seeds the DB and calls this so the in-memory log doesn't outlive it."
  []
  (reset! log {}))

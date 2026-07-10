(ns finance-aggregator.web.commands
  "Per-user undo/redo command log for the server-authoritative workspace.

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
   [finance-aggregator.db.transactions :as db]
   [finance-aggregator.db.transfers :as db-transfers]))

(defonce ^:private log (atom {}))

(def ^:private empty-state {:undo [] :redo [] :linger #{}})

(defn- state [user] (get @log user empty-state))

(defn category-effect
  "The optional :category-effect a :set-match command carries when exactly ONE of the two
   legs is categorized: {:tx-id <the blank leg> :before nil :after <the other leg's category
   id>} — linking copies the category onto the blank leg, and undo/redo of the match
   reverse/re-apply the copy in the same step (see mutate!). Returns nil when both or
   neither leg is categorized: a match never overwrites an existing category, and with
   nothing to copy the command stays plain. `a`/`b` are {:tx-id long :category-id long?}
   (the handler pulls the current ids — db.transactions/category-id). Pure, plain data, so
   the effect log-persists like any other command field."
  [{a-id :tx-id a-cat :category-id} {b-id :tx-id b-cat :category-id}]
  (cond
    (and a-cat (nil? b-cat)) {:tx-id b-id :before nil :after a-cat}
    (and b-cat (nil? a-cat)) {:tx-id a-id :before nil :after b-cat}
    :else nil))

(defn- mutate!
  "Apply command `cmd`'s mutation, setting the field to `value` (the :after on apply, the
   :before on undo)."
  [conn {:keys [type tx-id partner category-effect]} value]
  (case type
    :set-reconciled  (db/set-reconciled! conn tx-id (boolean value))
    :set-category    (db/update-category! conn tx-id value)
    :set-description (db/set-user-description! conn tx-id (or value ""))
    ;; value is a java.util.Date (the manual override) or nil (clear it, falling back through
    ;; the effective chain — data.ledger/effective-posted-date: posted-date, then the plain
    ;; date). Family-uniform: set-user-posted-date! resolves tx-id to its split family root and
    ;; writes/retracts on every live member, same as undo re-applying :before.
    :set-posted-date (db/set-user-posted-date! conn tx-id value)
    ;; value is the full split vector ({:amount :category-id :memo?}); [] un-splits. Undo
    ;; re-applies :before (the prior parts, or [] when the row wasn't split). Full-replace.
    :set-splits      (db/set-splits! conn tx-id (vec value))
    ;; value is the partner tx-id (link this leg) or nil (unlink). A match touches both legs;
    ;; undo flips it (after=partner ⇄ before=nil for a match, the reverse for an unmatch).
    ;; A MATCH command may also carry :category-effect (see category-effect): applying the
    ;; link (value truthy) copies the category onto the blank leg (:after); undoing it
    ;; (value nil) restores :before — always nil, since the effect only ever fills a blank —
    ;; so one undo reverses both the link and the copy, and redo re-applies both. Plain
    ;; unmatch commands never carry the key. Runs after confirm-match!/unmatch! so a
    ;; validation throw leaves the category untouched.
    :set-match       (do (if value
                           (db-transfers/confirm-match! conn tx-id value)
                           (db-transfers/unmatch! conn tx-id))
                         (when category-effect
                           (db/update-category! conn (:tx-id category-effect)
                                                (if value
                                                  (:after category-effect)
                                                  (:before category-effect)))))
    ;; "Not a transfer" for a suggested pair: value true = reject (don't re-suggest), false =
    ;; un-reject (undo). The partner id rides in :partner (value is just the direction).
    :reject-match    (if value
                       (db-transfers/reject-match! conn tx-id partner)
                       (db-transfers/unreject! conn tx-id partner))))

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

(defn- references?
  "Does command `cmd` act on or point at transaction `id`? Its :tx-id, its :partner
   (reject-match), or — for a transfer match — its before/after partner leg (only
   :set-match's before/after hold tx-ids; other commands' hold category ids / strings /
   split vectors, so they're not matched). A :set-match's :category-effect needs no
   clause of its own: its target is by construction one of the match's two legs
   (category-effect fills the blank leg of the pair being linked), which :tx-id or
   before/after already cover."
  [id {:keys [type tx-id partner before after]}]
  (or (= tx-id id)
      (= partner id)
      (and (= type :set-match) (or (= before id) (= after id)))))

(defn removed-split-part-ids
  "The part ids present in `before` (a db.transactions/current-splits row vector) but
   absent from `after` (a web.view-state/parse-splits-value payload row vector) — the
   live parts a set-splits edit just retracted. Pure. Used by the set-splits handler to
   forget! them from the undo/redo log: replaying a stale command against a retracted
   part would throw and jam the stack, same as an unrelated deleted transaction."
  [before after]
  (let [after-ids (into #{} (keep :id) after)]
    (into [] (remove after-ids) (map :id before))))

(defn forget!
  "Drop every undo/redo command (and any linger pin) that references transaction `tx-id`.
   Called when a manual transaction is deleted: the row is gone, so replaying a command
   against it would throw (e.g. unmatch! / set-splits! on a retracted entity) and jam the
   stack. Purging keeps the log replay-safe."
  [user tx-id]
  (swap! log update user
         (fn [s]
           (let [s (or s empty-state)]
             (-> s
                 (update :undo #(vec (remove (partial references? tx-id) %)))
                 (update :redo #(vec (remove (partial references? tx-id) %)))
                 (update :linger disj tx-id))))))

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

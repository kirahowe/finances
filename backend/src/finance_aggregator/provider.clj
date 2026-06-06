(ns finance-aggregator.provider
  "Provider seam: polymorphism a la carte via multimethods dispatching on a
   provider-key keyword (:plaid, :lunchflow, :manual, ...).

   Each provider lives in its own namespace tree and registers its methods by
   being loaded. The multimethod dispatch tables ARE the registry - adding a
   provider is `(defmethod fetch-accounts :foo ...)`, not new wiring.

   Every method produces/consumes the canonical, schema-ready data contract
   transacted by `finance-aggregator.db/insert!`; there are no provider types,
   just data.")

(defmulti fetch-accounts
  "(provider-key, opts) -> {:institutions #{canonical-institution-maps}
                            :accounts     #{canonical-account-maps}}

   opts is the deps map assembled by the sync orchestrator (e.g.
   {:db-conn .. :secrets ..}). Pure-ish: performs provider I/O and returns
   already-normalized maps ready for `db/insert!`."
  (fn [provider-key _opts] provider-key))

(defmulti fetch-transactions
  "(provider-key, opts) -> {:transactions [canonical-transaction-maps]
                            :removed      [external-ids-to-retract]
                            :more?        boolean
                            :next-opts    opts-for-next-page}

   :more?/:next-opts let cursor-paged providers (Plaid) drive the orchestrator's
   loop; single-pass providers (Lunchflow) return :more? false after one call."
  (fn [provider-key _opts] provider-key))

(defn registered-providers
  "Set of provider-keys that have a fetch-accounts method registered."
  []
  (set (keys (methods fetch-accounts))))

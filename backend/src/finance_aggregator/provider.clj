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

(defmulti available-accounts
  "(provider-key, opts) -> [{:external-id :name :institution-id
                            :institution-name :currency}]

   Display-friendly list of every account the provider exposes, for the
   selection UI. Unlike fetch-accounts (which returns only the connected/
   selected set to persist), this lists everything and persists nothing. Only
   secrets-based, selectable providers implement it - polymorphism a la carte."
  (fn [provider-key _opts] provider-key))

(defmulti classify-sync-error
  "(provider-key, deps, exception) -> {:action .. :error-code .. :error-message ..}

   :action is the generic vocabulary the resync core acts on:
     :retry     - transient; back off and try again
     :reconnect - user must re-auth; never auto-retried
     :fail      - unknown/other; surface, don't auto-retry
     :resolved  - the failure already self-healed (e.g. Plaid LOGIN_REPAIRED)

   Each provider owns its own error vocabulary; the core never names a provider
   error code. deps is the per-connection deps the orchestrator assembled, so the
   provider can reach its client/secrets to resolve the code."
  (fn [provider-key _deps _e] provider-key))

(defn registered-providers
  "Set of provider-keys that have a fetch-accounts method registered."
  []
  (set (keys (methods fetch-accounts))))

(defn selectable-providers
  "Set of provider-keys that implement available-accounts (the selection UI
   seam). A subset of registered-providers - e.g. Plaid syncs but isn't listed
   here, so it must not be routed to the available-accounts endpoint."
  []
  (set (keys (methods available-accounts))))

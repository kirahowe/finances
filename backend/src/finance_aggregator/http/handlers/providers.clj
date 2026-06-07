(ns finance-aggregator.http.handlers.providers
  "Generic provider HTTP handlers. Dispatches to the multimethod provider seam
   via the sync orchestrator, so one handler serves every secrets-based
   provider. Requiring the provider implementation namespaces here registers
   their `defmethod`s on the seam."
  (:require
   [finance-aggregator.http.responses :as responses]
   [finance-aggregator.provider :as provider]
   [finance-aggregator.provider.sync :as sync]
   ;; Side-effecting requires: loading these registers provider methods.
   [finance-aggregator.lunchflow.provider]))

(defn- provider-key-from
  "Resolve the :provider path param into a key, validating it is in valid-keys.
   Different endpoints accept different sets (all registered providers for sync,
   only selectable ones for available-accounts)."
  [request valid-keys]
  (let [p (get-in request [:path-params :provider])]
    (when-not p
      (throw (ex-info "provider is required" {:type :bad-request})))
    (let [k (keyword p)]
      (when-not (contains? valid-keys k)
        (throw (ex-info (str "Unknown provider: " p)
                        {:type :bad-request
                         :provider p
                         :available (mapv name valid-keys)})))
      k)))

(defn sync-handler
  "Factory for POST /api/providers/:provider/sync.
   Runs a full account+transaction sync for the provider and returns the
   terminal status.

   Optional body {:accountIds [..]} adds those provider external-ids to the
   connected set before syncing (the connect step). With no body, the sync
   refreshes only already-connected accounts."
  [deps]
  (fn [request]
    (let [provider-key (provider-key-from request (provider/registered-providers))
          account-ids (:accountIds (:body-params request))
          deps (cond-> deps
                 (seq account-ids) (assoc :selected-account-ids (set account-ids)))
          result (sync/sync-provider! deps provider-key)]
      (responses/success-response {:provider (name provider-key)
                                   :status (name result)}))))

(defn available-accounts-handler
  "Factory for GET /api/providers/:provider/available-accounts.
   Returns every account the provider exposes (for the selection UI). Persists
   nothing."
  [deps]
  (fn [request]
    (let [provider-key (provider-key-from request (provider/selectable-providers))]
      (responses/success-response
       (provider/available-accounts provider-key deps)))))

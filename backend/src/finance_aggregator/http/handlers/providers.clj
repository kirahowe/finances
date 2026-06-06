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
  "Resolve and validate the :provider path param into a registered key."
  [request]
  (let [p (get-in request [:path-params :provider])]
    (when-not p
      (throw (ex-info "provider is required" {:type :bad-request})))
    (let [k (keyword p)]
      (when-not (contains? (provider/registered-providers) k)
        (throw (ex-info (str "Unknown provider: " p)
                        {:type :bad-request
                         :provider p
                         :available (mapv name (provider/registered-providers))})))
      k)))

(defn sync-handler
  "Factory for POST /api/providers/:provider/sync.
   Runs a full account+transaction sync for the provider and returns the
   terminal status."
  [deps]
  (fn [request]
    (let [provider-key (provider-key-from request)
          result (sync/sync-provider! deps provider-key)]
      (responses/success-response {:provider (name provider-key)
                                   :status (name result)}))))

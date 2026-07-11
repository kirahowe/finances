(ns finance-aggregator.web.pages.setup
  "Handler (glue) for /setup: fetch → present → render → respond. Business rules
   live in the data layer (db.*) and the presenter (web.accounts/present); the
   hiccup lives in web.pages.setup-view. The sync actions fire a background resync
   (statuses persist; refreshing /setup shows progress) and redirect back."
  (:require
   [clojure.set :as set]
   [finance-aggregator.auth :as auth]
   [finance-aggregator.db.accounts :as db-accounts]
   [finance-aggregator.db.connections :as db-connections]
   [finance-aggregator.db.credentials :as creds]
   [finance-aggregator.db.stats :as db-stats]
   [finance-aggregator.lib.log :as log]
   ;; Load-only: registers the :lunchflow provider methods (available-accounts /
   ;; fetch-* / classify-sync-error) used by the connect page + the resync drive.
   [finance-aggregator.lunchflow.provider]
   [finance-aggregator.plaid.client :as plaid-client]
   [finance-aggregator.provider :as provider]
   [finance-aggregator.resync :as resync]
   [finance-aggregator.web.accounts :as accounts]
   [finance-aggregator.web.layout :as layout]
   [finance-aggregator.web.pages.setup-view :as view]
   [finance-aggregator.web.render :as r]
   [starfederation.datastar.clojure.adapter.http-kit :as hk]
   [starfederation.datastar.clojure.api :as d*])
  (:import
   [java.util Date]))

(defn- redirect-to-setup []
  {:status 303 :headers {"Location" "/setup"}})

(defn- form-set
  "Coerce a wrap-params field (absent / single string / repeated -> vector) into a
   set of strings."
  [v]
  (set (cond (nil? v) nil (string? v) [v] :else v)))

(defn- background
  "Run an action in the background, logging (never propagating) failures outside
   the engine's per-connection isolation. The response never waits on it."
  [label thunk]
  (future
    (try (thunk)
         (catch Throwable t
           (log/error label {:error (.getMessage t)})))))

(defn- sse
  "Open an SSE response, run `emit` (a fn of the sse channel), then close — the
   hk/->sse-response + on-open + close-sse! envelope."
  [req emit]
  (hk/->sse-response req {hk/on-open (fn [s] (emit s) (d*/close-sse! s))}))

(defn- connections-model
  "Present model for the #connections fragment, overriding the status of any
   connection in `syncing-ids` to :syncing with its error cleared — the optimistic
   in-flight render shown while a sync runs (so the stale error banner disappears
   immediately and the pill reads Syncing…)."
  [db-conn now syncing-ids]
  (let [conns (mapv (fn [c]
                      (if (contains? syncing-ids (:connection/id c))
                        (-> c (assoc :connection/status :syncing)
                            (dissoc :connection/error-message))
                        c))
                    (db-connections/list-connections db-conn))]
    (accounts/present {:stats (db-stats/entity-counts db-conn)
                       :connections conns
                       :accounts (db-accounts/list-with-institution db-conn)
                       :now now})))

(defn- patch-connections!
  "Morph the #connections fragment into the live SSE response from fresh DB state,
   showing the connections in `syncing-ids` as in-flight."
  [sse-chan db-conn syncing-ids]
  (d*/patch-elements! sse-chan
                      (r/render (view/connections-section
                                 (connections-model db-conn (Date.) syncing-ids)))))

(defn sync-now
  "Factory: POST /setup/sync — live-sync every connection over SSE: flip all cards
   to Syncing…, run one resilient-sync pass, then patch the final state. No reload."
  [{:keys [db-conn] :as deps}]
  (fn [req]
    (sse req
         (fn [sse-chan]
           (let [ids (set (map :connection/id (db-connections/list-connections db-conn)))]
             (patch-connections! sse-chan db-conn ids)
             (resync/resync-all! deps)
             (patch-connections! sse-chan db-conn #{}))))))

(defn resync-connection
  "Factory: POST /setup/resync?connection-id=… — live-resync one connection over
   SSE: flip its card to Syncing… (clearing any error), run the resync, patch the
   result. Unknown/blank ids close the stream with no change."
  [{:keys [db-conn] :as deps}]
  (fn [req]
    (let [id (not-empty (get-in req [:params "connection-id"]))]
      (sse req
           (fn [sse-chan]
             (when (and id (db-connections/get-connection db-conn id))
               (patch-connections! sse-chan db-conn #{id})
               (resync/resync-connection! deps {:connection/id id})
               (patch-connections! sse-chan db-conn #{})))))))

(defn sync-account
  "Factory: POST /setup/sync-account?external-id=…&external-id=… — live-resync one or
   more Lunchflow accounts over SSE: flip the shared \"lunchflow\" connection's cards to
   Syncing… (clearing any error), run a resync scoped to just those accounts
   (:extra-opts :only-account-ids — lunchflow.provider restricts fetch-accounts/
   fetch-transactions to them and computes each account's OWN `from` window, so a stale
   account's pull isn't clamped by a fresher sibling's date), then patch the result. The
   per-row Sync button sends one id; a per-institution card's Resync sends that card's
   ids (Lunchflow is one shared connection spanning institutions, so its card-level
   resync scopes by account where Plaid's is item-level/cursor-based). Ids that aren't
   already-connected Lunchflow accounts are dropped; none left — or no lunchflow
   connection — closes the stream with no change."
  [{:keys [db-conn] :as deps}]
  (fn [req]
    (let [ext-ids (form-set (get-in req [:params "external-id"]))]
      (sse req
           (fn [sse-chan]
             (let [target (set/intersection ext-ids (db-accounts/external-ids-for-provider db-conn :lunchflow))]
               (when (and (seq target)
                          (db-connections/get-connection db-conn "lunchflow"))
                 (patch-connections! sse-chan db-conn #{"lunchflow"})
                 (resync/resync-connection! (assoc deps :extra-opts {:only-account-ids target})
                                            {:connection/id "lunchflow"})
                 (patch-connections! sse-chan db-conn #{}))))))))

(defn page
  "Factory: GET /setup — render the stats strip + connection cards."
  [{:keys [db-conn]}]
  (fn [_req]
    (let [model (accounts/present
                 {:stats       (db-stats/entity-counts db-conn)
                  :connections (db-connections/list-connections db-conn)
                  :accounts    (db-accounts/list-with-institution db-conn)
                  :now         (Date.)})]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (layout/document {:title "Setup · Finance Aggregator" :islands ["plaid-link"]
                               ;; The account-rename cell's courier signal (web.inline-edit,
                               ;; via setup-view/account-name-cell) — Datastar needs it seeded
                               ;; before the first $nameValue = ... assignment.
                               :signals {:nameValue ""}}
                              (view/body model))})))

(defn plaid-link-token
  "Factory: GET /setup/plaid/link-token — mint a Plaid Link token for the island
   (JSON {:link_token ...}). Network errors propagate to the JSON error handler."
  [{:keys [plaid-config]}]
  (fn [_req]
    {:status 200 :body {:link_token (plaid-client/create-link-token plaid-config auth/user-id)}}))

(defn plaid-exchange
  "Factory: POST /setup/plaid/exchange — exchange the public_token, store the Item
   credential (with the user's selected account ids), ensure its connection, and
   fire the initial sync in the background. JSON body
   {:public_token .. :institution {:name ..} :accounts [{:id ..} ..]}."
  [{:keys [db-conn secrets plaid-config] :as deps}]
  (fn [req]
    (let [{:keys [public_token institution accounts]} (:body-params req)
          {:keys [access_token item_id]} (plaid-client/exchange-public-token plaid-config public_token)
          inst-name (or (:name institution) "Bank")
          conn-id (str "plaid:" item_id)]
      (creds/store-plaid-item-credential! db-conn secrets access_token item_id inst-name (mapv :id accounts))
      (db-connections/ensure-connection! db-conn {:id conn-id :provider :plaid
                                                  :external-id item_id :institution-name inst-name})
      (background "Background Plaid initial sync failed"
                  #(resync/resync-connection! deps {:connection/id conn-id}))
      {:status 200 :body {:ok true :connection conn-id}})))

(defn lunchflow-page
  "Factory: GET /setup/lunchflow — list Lunchflow's available accounts grouped by
   institution, pre-marking the ones already imported. A failure to reach
   Lunchflow (missing key / network) renders as an inline error, not a 500."
  [{:keys [db-conn secrets]}]
  (fn [_req]
    (let [stats (db-stats/entity-counts db-conn)
          model (try
                  {:stats stats
                   :groups (accounts/provider-selection
                            (provider/available-accounts :lunchflow {:secrets secrets})
                            (db-accounts/external-ids-for-provider db-conn :lunchflow))}
                  (catch Exception e
                    (log/warn "Lunchflow available-accounts failed" {:error (.getMessage e)})
                    {:stats stats
                     :error (str "Couldn't load Lunchflow accounts: " (.getMessage e))}))]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (layout/document {:title "Connect Lunchflow · Finance Aggregator"}
                              (view/lunchflow-body model))})))

(defn lunchflow-connect
  "Factory: POST /setup/lunchflow — ensure the single Lunchflow connection and
   sync the newly-selected accounts (form field account-id, repeated) in the
   background. The selection is remembered as the imported accounts themselves, so
   later resyncs need no stored selection. No selection is a no-op redirect."
  [{:keys [db-conn] :as deps}]
  (fn [req]
    (let [selected (form-set (get-in req [:params "account-id"]))]
      (when (seq selected)
        (db-connections/ensure-connection! db-conn {:id "lunchflow" :provider :lunchflow})
        (background "Background Lunchflow connect failed"
                    #(resync/resync-connection!
                      (assoc deps :extra-opts {:selected-account-ids selected})
                      {:connection/id "lunchflow"}))))
    (redirect-to-setup)))

(defn set-account-name
  "Factory: PUT /setup/account/:external-id/name — the account-rename inline-edit commit
   (web.inline-edit, via setup-view/account-name-cell): read the new name off the
   $nameValue courier, set or clear the display-name overlay (db.accounts/set-display-name!
   does the trim + blank-check — a blank commit retracts the override, falling back to the
   provider name), then SSE-patch just that account's name cell back in with a transient
   saved-confirmation (the smallest fragment a save needs to re-render, not the whole card).
   An external-id that doesn't resolve to an account patches nothing."
  [{:keys [db-conn]}]
  (fn [req]
    (let [external-id (get-in req [:path-params :external-id])
          display-name (:nameValue (r/read-signals req))]
      (db-accounts/set-display-name! db-conn external-id display-name)
      (sse req
           (fn [sse-chan]
             (when-let [account (db-accounts/by-external-id db-conn external-id)]
               (d*/patch-elements!
                sse-chan
                (r/render (view/account-name-td
                           (accounts/account-display false account) :saved? true)))))))))

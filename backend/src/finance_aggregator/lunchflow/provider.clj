(ns finance-aggregator.lunchflow.provider
  "Registers the :lunchflow methods on the provider seam. Loading this namespace
   wires Lunchflow into `finance-aggregator.provider`.

   Credential: static API key in secrets.edn.age, read via the secrets
   component. Sync window is stateless - `from` is derived as the latest
   existing Lunchflow transaction date (nil => full backfill), `to` = today.

   Account selection is stateless too: the accounts already imported (queried
   via `connected-external-ids`) ARE the remembered selection. fetch-accounts
   persists (connected ∪ the request's :selected-account-ids), so a connect adds
   accounts and a plain re-sync refreshes only connected ones; fetch-transactions
   pulls only for connected accounts."
  (:require
   [datalevin.core :as d]
   [finance-aggregator.auth :as auth]
   [finance-aggregator.lib.secrets :as secrets]
   [finance-aggregator.lunchflow.client :as client]
   [finance-aggregator.lunchflow.data :as data]
   [finance-aggregator.provider :as provider])
  (:import
   [java.time LocalDate ZoneId]
   [java.util Date]))

(defn- api-key [secrets]
  (or (secrets/get-secret secrets :lunchflow)
      (throw (ex-info "Lunchflow API key not found in secrets"
                      {:hint "Run 'bb secrets edit' and add a :lunchflow key"}))))

(defn- latest-transaction-date
  "Most recent :transaction/date among Lunchflow accounts, or nil if none."
  [db-conn]
  (d/q '[:find (max ?date) .
         :where
         [?a :account/provider :lunchflow]
         [?t :transaction/account ?a]
         [?t :transaction/date ?date]]
       (d/db db-conn)))

(defn- connected-external-ids
  "Set of :account/external-id for Lunchflow accounts already imported. This set
   IS the remembered selection - syncs refresh these and never re-add others."
  [db-conn]
  (set (d/q '[:find [?ext ...]
              :where
              [?a :account/provider :lunchflow]
              [?a :account/external-id ?ext]]
            (d/db db-conn))))

(defn- ->date-str [^Date d]
  (str (.toLocalDate (.atZone (.toInstant d) (ZoneId/of "UTC")))))

(defmethod provider/available-accounts :lunchflow
  [_ {:keys [secrets]}]
  (mapv (fn [account]
          (let [acct (data/parse-account account auth/user-id)
                inst (data/parse-institution account)]
            {:external-id (:account/external-id acct)
             :name (:account/external-name acct)
             :institution-id (:institution/id inst)
             :institution-name (:institution/name inst)
             :institution-logo (:institution/logo inst)}))
        (client/list-accounts (api-key secrets))))

(defmethod provider/fetch-accounts :lunchflow
  [_ {:keys [secrets db-conn selected-account-ids]}]
  (let [target (into (connected-external-ids db-conn) (or selected-account-ids #{}))
        wanted (->> (client/list-accounts (api-key secrets))
                    (filter #(contains? target (data/account-external-id %))))]
    {:institutions (set (map data/parse-institution wanted))
     :accounts (set (map #(data/parse-account % auth/user-id) wanted))}))

(defmethod provider/fetch-transactions :lunchflow
  [_ {:keys [secrets db-conn]}]
  (let [key (api-key secrets)
        connected (connected-external-ids db-conn)
        from (some-> (latest-transaction-date db-conn) ->date-str)
        to (str (LocalDate/now))
        accounts (filter #(contains? connected (data/account-external-id %))
                         (client/list-accounts key))
        transactions (mapcat
                      (fn [account]
                        (->> (client/fetch-account-transactions
                              key (:id account)
                              {:from from :to to :include-pending false})
                             (keep #(data/parse-transaction % auth/user-id))))
                      accounts)]
    {:transactions (vec transactions)
     :removed []
     :more? false}))

(ns finance-aggregator.lunchflow.provider
  "Registers the :lunchflow methods on the provider seam. Loading this namespace
   wires Lunchflow into `finance-aggregator.provider`.

   Credential: static API key in secrets.edn.age, read via the secrets
   component. Sync window is stateless - `from` is derived as the latest
   existing Lunchflow transaction date (nil => full backfill), `to` = today."
  (:require
   [datalevin.core :as d]
   [finance-aggregator.lib.secrets :as secrets]
   [finance-aggregator.lunchflow.client :as client]
   [finance-aggregator.lunchflow.data :as data]
   [finance-aggregator.provider :as provider])
  (:import
   [java.time LocalDate ZoneId]
   [java.util Date]))

;; Single-user app for now; matches the hardcoded user used by other providers.
(def ^:private user-id "test-user")

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

(defn- ->date-str [^Date d]
  (str (.toLocalDate (.atZone (.toInstant d) (ZoneId/of "UTC")))))

(defmethod provider/fetch-accounts :lunchflow
  [_ {:keys [secrets]}]
  (let [accounts (client/list-accounts (api-key secrets))]
    {:institutions (set (map data/parse-institution accounts))
     :accounts (set (map #(data/parse-account % user-id) accounts))}))

(defmethod provider/fetch-transactions :lunchflow
  [_ {:keys [secrets db-conn]}]
  (let [key (api-key secrets)
        from (some-> (latest-transaction-date db-conn) ->date-str)
        to (str (LocalDate/now))
        accounts (client/list-accounts key)
        transactions (mapcat
                      (fn [account]
                        (->> (client/fetch-account-transactions
                              key (:id account)
                              {:from from :to to :include-pending false})
                             (keep #(data/parse-transaction % user-id))))
                      accounts)]
    {:transactions (vec transactions)
     :removed []
     :more? false}))

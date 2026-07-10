(ns finance-aggregator.lunchflow.provider
  "Registers the :lunchflow methods on the provider seam. Loading this namespace
   wires Lunchflow into `finance-aggregator.provider`.

   Credential: static API key in secrets.edn.age, read via the secrets
   component. Sync window is stateless - `from` is derived PER ACCOUNT as the
   latest existing transaction date for THAT account (nil => full backfill for
   just that account) - never a single global date across every Lunchflow
   account, which would clamp a stale account's pull window to a fresher
   sibling's date. `to` = today for every account.

   Account selection is stateless too: the accounts already imported (queried
   via `connected-external-ids`) ARE the remembered selection. fetch-accounts
   persists (connected ∪ the request's :selected-account-ids), so a connect adds
   accounts and a plain re-sync refreshes only connected ones; fetch-transactions
   pulls only for connected accounts.

   A per-account resync (deps' :only-account-ids, a set of external-ids) further
   restricts BOTH to just those accounts - see `scope-target`. It only ever
   narrows an already-connected account's own refresh, never adds one: unlike
   :selected-account-ids (a connect-time addition), :only-account-ids ∩
   connected wins even when accounts also carries :selected-account-ids."
  (:require
   [clojure.set :as set]
   [datalevin.core :as d]
   [finance-aggregator.auth :as auth]
   [finance-aggregator.lib.secrets :as secrets]
   [finance-aggregator.lunchflow.client :as client]
   [finance-aggregator.lunchflow.data :as data]
   [finance-aggregator.provider :as provider]
   [finance-aggregator.utils :as utils]
   [tick.core :as t])
  (:import
   [java.util Date]))

(defn- api-key [secrets]
  (or (secrets/get-secret secrets :lunchflow)
      (throw (ex-info "Lunchflow API key not found in secrets"
                      {:hint "Run 'bb secrets edit' and add a :lunchflow key"}))))

(defn- latest-transaction-date-by-account
  "{account external-id -> most recent :transaction/date} across every Lunchflow
   account that has at least one transaction — the per-account backfill window.
   An account absent from this map (no transactions yet) gets a full backfill
   (fetch-transactions reads it via a plain `get`, nil => no `from`)."
  [db-conn]
  (into {}
        (d/q '[:find ?ext (max ?date)
               :where
               [?a :account/provider :lunchflow]
               [?a :account/external-id ?ext]
               [?t :transaction/account ?a]
               [?t :transaction/date ?date]]
             (d/db db-conn))))

(defn- connected-external-ids
  "Set of :account/external-id for Lunchflow accounts already imported. This set
   IS the remembered selection - syncs refresh these and never re-add others."
  [db-conn]
  (set (d/q '[:find [?ext ...]
              :where
              [?a :account/provider :lunchflow]
              [?a :account/external-id ?ext]]
            (d/db db-conn))))

(defn- scope-target
  "The set of external-ids a sync should touch. With :only-account-ids present
   (a per-account resync) the target is `only ∩ connected` — it can only
   refresh an account already imported, never add one, so :selected-account-ids
   is ignored in this branch. Without it, the ordinary rule: `connected ∪
   selected` (fetch-accounts, so a connect can add) or plain `connected`
   (fetch-transactions, which never adds)."
  [connected {:keys [only-account-ids selected-account-ids]}]
  (if only-account-ids
    (set/intersection connected only-account-ids)
    (into connected (or selected-account-ids #{}))))

(defn- ->date-str [^Date d]
  (str (utils/date->local-date d)))

(defmethod provider/classify-sync-error :lunchflow
  [_ _deps ^Exception e]
  ;; Lunchflow has no item-health / error-code vocabulary; a failed pull is
  ;; surfaced (:fail) and retried on the next manual/scheduled pass rather than
  ;; auto-backed-off. The cause (network, bad key) rides in the message.
  {:action :fail :error-code nil :error-message (.getMessage e)})

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
  [_ {:keys [secrets db-conn] :as opts}]
  (let [target (scope-target (connected-external-ids db-conn) opts)
        wanted (->> (client/list-accounts (api-key secrets))
                    (filter #(contains? target (data/account-external-id %))))]
    {:institutions (set (map data/parse-institution wanted))
     :accounts (set (map #(data/parse-account % auth/user-id) wanted))}))

(defmethod provider/fetch-transactions :lunchflow
  [_ {:keys [secrets db-conn] :as opts}]
  (let [key (api-key secrets)
        target (scope-target (connected-external-ids db-conn) opts)
        latest-by-account (latest-transaction-date-by-account db-conn)
        to (str (t/today))
        accounts (filter #(contains? target (data/account-external-id %))
                         (client/list-accounts key))
        transactions (mapcat
                      (fn [account]
                        (let [ext (data/account-external-id account)
                              from (some-> (get latest-by-account ext) ->date-str)]
                          (->> (client/fetch-account-transactions
                                key (:id account)
                                {:from from :to to :include-pending false})
                               (keep #(data/parse-transaction % auth/user-id)))))
                      accounts)]
    {:transactions (vec transactions)
     :removed []
     :more? false}))

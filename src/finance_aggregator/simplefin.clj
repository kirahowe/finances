(ns finance-aggregator.simplefin
  "SimpleFin data processing utilities to extract modeled data
  from raw accounts responses."
  (:require
   [clojure.set :as set]
   [finance-aggregator.utils :as u])
  (:import
   [java.util Base64]))

(defn zero-balance? [account]
  (-> account :balance parse-double zero?))

(defn rename-keys [account]
  (set/rename-keys account
                   {:name :account/name
                    :id :account/id
                    :balance-date :balance/date
                    :balance :balance/amount}))

(defn get-accounts [account]
  (-> account
      (select-keys [:balance-date :name :balance :org :id])
      (rename-keys)
      (update :balance/date u/epoch->date)
      (assoc :institution/name (get-in account [:org :name]))
      (dissoc :org)))

(ns finance-aggregator.simplefin.data
  (:require
   [clojure.set :as set]
   [finance-aggregator.db :as db]
   [finance-aggregator.utils :as u]))

(defn zero-balance? [account]
  (-> account :balance parse-double zero?))

(defn parse-account [{:keys [org] :as account}]
  (-> account
      (select-keys [:name :id :org :currency])
      (set/rename-keys {:name :account/external-name
                        :id :account/external-id
                        :currency :account/currency})
      (assoc :account/institution [:institution/id (:id org)])
      (dissoc :org)))

(defn parse-institution [{:keys [org]}]
  (-> org
      (select-keys [:id :name :domain :url])
      (set/rename-keys {:id :institution/id
                        :name :institution/name
                        :domain :institution/domain
                        :url :institution/url})))

(defn parse-transaction [account-id transaction]
  (-> transaction
      (set/rename-keys {:id :transaction/external-id
                        :posted :transaction/posted-date
                        :amount :transaction/amount
                        :description :transaction/description
                        :payee :transaction/payee
                        :memo :transaction/memo
                        :transacted_at :transaction/transaction-date})
      (assoc :transaction/account [:account/external-id account-id])
      (update :transaction/amount bigdec)
      (update :transaction/posted-date u/epoch->date)
      (update :transaction/transaction-date u/epoch->date)))

(defn parse-transactions [{:keys [id transactions]}]
  (->> transactions
       (map (partial parse-transaction id))))

(defn parse-accounts [entities account]
  (-> entities
      (update :institutions conj (parse-institution account))
      (update :accounts conj (parse-account account))
      (update :transactions into (parse-transactions account))))

(defn parse-entities [accounts]
  (->> accounts
       (remove zero-balance?)
       (reduce parse-accounts {:institutions #{}
                               :accounts #{}
                               :transactions []})))

(defn import-simplefin-data! [accounts]
  (-> accounts
      parse-entities
      db/insert!))

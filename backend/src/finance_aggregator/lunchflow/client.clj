(ns finance-aggregator.lunchflow.client
  "Effectful Lunchflow REST client. Pull-based API authenticated with an
   `x-api-key` header. Returns parsed Clojure data (keyword keys, decimals as
   BigDecimal); all transformation to schema shape happens in lunchflow.data."
  (:require
   [clj-http.client :as http]
   [clojure.data.json :as json]
   [finance-aggregator.lib.log :as log]))

(def ^:private base-url "https://www.lunchflow.app/api/v1")

(defn- get-json
  "GET base-url+path with the api key, returning the parsed JSON body."
  [api-key path query-params]
  (try
    (let [resp (http/get (str base-url path)
                         {:headers {"x-api-key" api-key}
                          :query-params query-params
                          :accept :json
                          :as :string})]
      (json/read-str (:body resp) :key-fn keyword :bigdec true))
    (catch Exception e
      (throw (ex-info "Lunchflow API request failed"
                      {:path path :query-params query-params :error (.getMessage e)}
                      e)))))

(defn list-accounts
  "GET /accounts -> vector of account maps."
  [api-key]
  (log/info "Lunchflow: listing accounts")
  (:accounts (get-json api-key "/accounts" nil)))

(defn fetch-account-transactions
  "GET /accounts/{id}/transactions -> vector of transaction maps.

   opts: {:from \"yyyy-MM-dd\"|nil :to \"yyyy-MM-dd\"|nil :include-pending bool}
   Omits :from when nil to request a full backfill."
  [api-key account-id {:keys [from to include-pending]}]
  (let [query (cond-> {:include_pending (boolean include-pending)}
                from (assoc :from from)
                to (assoc :to to))]
    (:transactions (get-json api-key
                             (str "/accounts/" account-id "/transactions")
                             query))))

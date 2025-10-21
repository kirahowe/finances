(ns finance-aggregator.simplefin
  "SimpleFin Bridge API client for fetching account and transaction data."
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [tick.core :as t]
            [finance-aggregator.schema :as schema]))

(defn parse-access-url
  "Parses SimpleFin access URL (sfin://token:secret@bridge.simplefin.org/simplefin)
   into components for authentication."
  [access-url]
  (when access-url
    (let [pattern #"sfin://([^:]+):([^@]+)@([^/]+)(.*)"]
      (when-let [[_ token secret host path] (re-matches pattern access-url)]
        {:token token
         :secret secret
         :host host
         :path path
         :base-url (str "https://" host path)}))))

(defn setup-token->access-url
  "Exchanges a setup token for an access URL.
   The setup token can only be used once, so save the access URL!
   Returns the access URL string on success, or error map on failure."
  [setup-token]
  (try
    (let [response (http/post setup-token
                              {:content-type :json
                               :accept :json
                               :as :json})]
      (when (= 200 (:status response))
        (get-in response [:body :accounts])))
    (catch Exception e
      {:error (.getMessage e)})))

(defn fetch-accounts
  "Fetches all accounts and their transactions from SimpleFin Bridge.
   access-url: The sfin:// URL obtained from setup token exchange.
   Returns response map with :accounts key, or error map."
  [access-url]
  (let [{:keys [token secret base-url]} (parse-access-url access-url)]
    (when (and token secret base-url)
      (try
        (let [response (http/get (str base-url "/accounts")
                                 {:basic-auth [token secret]
                                  :accept :json
                                  :as :json})]
          (when (= 200 (:status response))
            (:body response)))
        (catch Exception e
          {:error (.getMessage e)})))))

(defn fetch-account
  "Fetches a specific account by ID.
   access-url: The sfin:// URL
   account-id: The account ID to fetch
   Returns account map or error map."
  [access-url account-id]
  (let [{:keys [token secret base-url]} (parse-access-url access-url)]
    (when (and token secret base-url)
      (try
        (let [response (http/get (str base-url "/accounts/" account-id)
                                 {:basic-auth [token secret]
                                  :accept :json
                                  :as :json})]
          (when (= 200 (:status response))
            (:body response)))
        (catch Exception e
          {:error (.getMessage e)})))))

(defn unix-timestamp->date
  "Converts Unix timestamp to ISO date string (YYYY-MM-DD).
   Uses UTC to ensure consistent date conversion."
  [timestamp]
  (when timestamp
    (-> timestamp
        (* 1000)
        t/instant
        (t/in "UTC")
        t/date
        str)))

(defn simplefin-tx->normalized
  "Converts a SimpleFin transaction to normalized schema.
   SimpleFin transaction format:
   {:id string
    :posted int (unix timestamp)
    :amount string
    :description string (optional)
    :payee string (optional)
    :memo string (optional)}"
  [tx account-name institution]
  {:id (random-uuid)
   :date (unix-timestamp->date (:posted tx))
   :amount (schema/normalize-amount (str (:amount tx "0")))
   :description (or (:description tx)
                   (:payee tx)
                   (:memo tx)
                   "Unknown")
   :institution institution
   :account-name account-name
   :account-type (schema/infer-account-type account-name)
   :category :uncategorized
   :source :simplefin
   :raw-data tx})

(defn simplefin-account->transactions
  "Extracts and normalizes all transactions from a SimpleFin account.
   Returns a sequence of normalized transactions."
  [account institution]
  (let [account-name (:name account)
        transactions (get account :transactions [])]
    (map #(simplefin-tx->normalized % account-name institution)
         transactions)))

(defn fetch-all-transactions
  "Fetches all transactions from all accounts via SimpleFin Bridge.
   Returns a sequence of normalized transactions.

   config: {:access-url \"sfin://...\"
            :institution-mapping {#\"pattern\" :institution-keyword}}"
  [config]
  (let [access-url (:access-url config)
        inst-mapping (:institution-mapping config {})
        response (fetch-accounts access-url)]
    (if (:error response)
      response
      (let [accounts (get response :accounts [])]
        (mapcat (fn [account]
                  (let [account-name (:name account)
                        ;; Try to determine institution from account name using patterns
                        institution (or (some (fn [[pattern inst]]
                                               (when (re-find pattern account-name)
                                                 inst))
                                             inst-mapping)
                                       :wealthsimple)] ; default assumption for SimpleFin
                    (simplefin-account->transactions account institution)))
                accounts)))))

(comment
  ;; Example usage

  ;; 1. First time setup - exchange setup token for access URL
  (def setup-token "https://bridge.simplefin.org/simplefin/claim/...")
  (def access-url (setup-token->access-url setup-token))
  ;; IMPORTANT: Save this access-url in your config.edn! You can't get it again.

  ;; 2. Fetch all accounts
  (def accounts-response (fetch-accounts access-url))

  ;; 3. Fetch all transactions with institution mapping
  (def config {:access-url access-url
               :institution-mapping {#"(?i)wealthsimple" :wealthsimple
                                    #"(?i)scotiabank" :scotiabank
                                    #"(?i)canadian.?tire" :canadian-tire}})
  (def transactions (fetch-all-transactions config))

  ;; 4. Fetch specific account
  (def account (fetch-account access-url "account-id-123")))

(ns finance-aggregator.simplefin.client
  "SimpleFin Bridge API client for fetching account and transaction data."
  (:require
   [clj-http.client :as http]
   [finance-aggregator.utils :as u])
  (:import
   [java.util Base64]))

(defn- decode-setup-token
  "Decodes a base64-encoded SimpleFin setup token to reveal the claim URL.
   The setup token is a base64-encoded URL like:
   aHR0cHM6Ly9icmlkZ2Uuc2ltcGxlZmluLm9yZy9zaW1wbGVmaW4vY2xhaW0vZGVtbw==
   which decodes to: https://bridge.simplefin.org/simplefin/claim/demo"
  [setup-token]
  (try
    (let [decoder (Base64/getDecoder)
          decoded-bytes (.decode decoder setup-token)]
      (String. decoded-bytes "UTF-8"))
    (catch Exception e
      (throw (ex-info "Failed to decode setup token"
                      {:error (.getMessage e)
                       :token setup-token})))))

(defn- parse-access-url
  "Parses SimpleFin access URL: https://username:password@bridge.simplefin.org/simplefin
   Extracts credentials and base URL for secure storage and API calls."
  [access-url]
  (when access-url
    (let [pattern #"https://([^:]+):([^@]+)@([^/]+)(.*)"]
      (when-let [[_ username password host path] (re-matches pattern access-url)]
        {:username username
         :password password
         :host host
         :path path
         :base-url (str "https://" host path)}))))

(defn claim-setup-token*
  "Core logic for claiming a setup token. Separated for testability.
   http-post-fn: function that takes a URL and returns {:status n :body s}
   setup-token: Base64-encoded token string"
  [http-post-fn setup-token]
  (let [claim-url (decode-setup-token setup-token)]
    (try
      (let [response (http-post-fn claim-url)]
        (when (= 200 (:status response))
          (:body response)))
      (catch Exception e
        (throw (ex-info "Failed to claim setup token"
                        {:error (.getMessage e)
                         :claim-url claim-url}))))))

(defn- claim-setup-token
  "Exchanges a setup token (base64-encoded) for an access URL.
   The setup token can only be used once, so save the access URL!
   Returns the access URL string on success, or throws on failure.

   setup-token: Base64-encoded token string, e.g.:
   aHR0cHM6Ly9icmlkZ2Uuc2ltcGxlZmluLm9yZy9zaW1wbGVmaW4vY2xhaW0vZGVtbw=="
  [setup-token]
  (claim-setup-token* http/post setup-token))

(defn build-fetch-request
  "Builds the request parameters for fetching transactions. Pure function.
   Returns nil if access-url is invalid, otherwise returns request map."
  [access-url year month]
  (when-let [{:keys [username password base-url]} (parse-access-url access-url)]
    (let [{:keys [start end]} (u/month-epoch-bounds year month)]
      {:url (str base-url "/accounts")
       :options {:basic-auth [username password]
                 :accept :json
                 :query-params {:start-date start :end-date end}
                 :as :json}})))

(defn fetch-transactions*
  "Core logic for fetching transactions. Separated for testability.
   http-get-fn: function that takes URL and options, returns {:status n :body m}
   access-url: The https:// URL with embedded credentials
   year, month: Time range for transactions"
  [http-get-fn access-url year month]
  (when-let [{:keys [url options]} (build-fetch-request access-url year month)]
    (try
      (let [response (http-get-fn url options)]
        (when (= 200 (:status response))
          (:body response)))
      (catch Exception e
        {:error (.getMessage e)}))))

(defn fetch-transactions
  "Fetches all accounts and their transactions from SimpleFin Bridge.
   access-url: The https:// URL with embedded credentials from claim endpoint.
   Returns response map with :accounts key, or error map."
  [access-url year month]
  (fetch-transactions* http/get access-url year month))


(comment
  ;; Example usage

  ;; 1. First time setup - decode and claim setup token
  (def setup-token "aHR0cHM6Ly9icmlkZ2Uuc2ltcGxlZmluLm9yZy9zaW1wbGVmaW4vY2xhaW0vZGVtbw==")

  ;; Decode to see the claim URL
  (decode-setup-token setup-token)
  ;; => "https://bridge.simplefin.org/simplefin/claim/demo"

  ;; Claim it to get access URL
  (def access-url (claim-setup-token setup-token))
  ;; IMPORTANT: Save this access-url in your config.edn! You can't get it again.

  ;; 2. Fetch all accounts
  (def accounts-response (fetch-transactions access-url 2025 8))

  ;; 3. Process response
  ;; - gather transactions for relevant accounts
  ;; - check data makes sense
  ;; - normalize it
  (->> accounts-response
       :accounts
       (remove zero-balance?)
       to-tidy-data)

  ;; accounts look like this, but with many transactions:
  ;; {:balance-date 1761439397,
  ;;  :name "Instalments",
  ;;  :currency "CAD",
  ;;  :balance "0.00",
  ;;  :org
  ;;  {:domain "www.scotiabank.com",
  ;;   :name "Scotiabank",
  ;;   :sfin-url "https://beta-bridge.simplefin.org/simplefin",
  ;;   :url "https://www.scotiabank.com",
  ;;   :id "www.scotiabank.com"},
  ;;  :id "ACT-1f4cf536-289d-4adb-b46c-4bd704db8df0",
  ;;  :available-balance "0.00",
  ;;  :holdings [],
  ;;  :transactions []}
  )

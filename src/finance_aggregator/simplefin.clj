(ns finance-aggregator.simplefin
  "SimpleFin Bridge API client for fetching account and transaction data.

   Security notes:
   - Never logs or exposes raw credentials
   - Uses Basic Auth headers (not URL-embedded credentials)
   - Credentials stored encrypted via finance-aggregator.credentials"
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [tick.core :as t]
            [finance-aggregator.schema :as schema]
            [finance-aggregator.credentials :as creds])
  (:import [java.util Base64]))

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

(defn parse-access-url
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

(defn- claim-setup-token
  "Exchanges a setup token (base64-encoded) for an access URL.
   The setup token can only be used once, so save the access URL!
   Returns the access URL string on success, or throws on failure.

   setup-token: Base64-encoded token string, e.g.:
   aHR0cHM6Ly9icmlkZ2Uuc2ltcGxlZmluLm9yZy9zaW1wbGVmaW4vY2xhaW0vZGVtbw=="
  [setup-token]
  (let [claim-url (decode-setup-token setup-token)]
    (try
      (let [response (http/post claim-url)]
        (when (= 200 (:status response))
          (:body response)))
      (catch Exception e
        (throw (ex-info "Failed to claim setup token"
                        {:error (.getMessage e)
                         :claim-url claim-url}))))))

(defn fetch-transactions
  "Fetches all accounts and their transactions from SimpleFin Bridge.
   access-url: The https:// URL with embedded credentials from claim endpoint.
   Returns response map with :accounts key, or error map."
  [access-url month]
  (let [{:keys [username password base-url]} (parse-access-url access-url)]
    (when (and username password base-url)
      (try
        (let [response (http/get (str base-url "/accounts")
                                 {:basic-auth [username password]
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
  (def accounts-response (fetch-transactions access-url))

  ;; 3. Fetch all transactions with institution mapping
  (def config {:access-url access-url
               :institution-mapping {#"(?i)wealthsimple" :wealthsimple
                                     #"(?i)scotiabank" :scotiabank
                                     #"(?i)canadian.?tire" :canadian-tire}})
  (def transactions (fetch-all-transactions config))

  ;; 4. Fetch specific account
  (def account (fetch-account access-url "account-id-123"))

  ;; NEW: Using encrypted credential storage
  ;; Store credentials after claiming
  (creds/store-simplefin-credentials "user-kira" access-url)

  ;; Fetch using stored credentials
  (def accounts (fetch-accounts-for-user "user-kira"))
  (def txs (fetch-all-transactions-for-user "user-kira" {#"(?i)wealthsimple" :wealthsimple})))

#!/usr/bin/env bb
;; Test script to fetch SimpleFin transactions
;; Usage: clojure -M scripts/test_simplefin.clj

(require '[finance-aggregator.simplefin :as sf]
         '[clojure.pprint :as pp]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(defn load-config []
  (let [config-file "resources/config.edn"]
    (if (.exists (io/file config-file))
      (edn/read-string (slurp config-file))
      (do
        (println "ERROR: config.edn not found!")
        (println "Please create resources/config.edn with your SimpleFin access URL")
        (System/exit 1)))))

(defn -main []
  (println "Loading configuration...")
  (let [config (load-config)
        access-url (get-in config [:simplefin :access-url])
        inst-mapping (get-in config [:simplefin :institution-mapping] {})]

    (if-not access-url
      (do
        (println "ERROR: No SimpleFin access URL in config!")
        (System/exit 1))
      (do
        (println "Fetching accounts from SimpleFin Bridge...")
        (let [response (sf/fetch-accounts access-url)]

          (if (:error response)
            (do
              (println "ERROR:" (:error response))
              (System/exit 1))

            (let [accounts (get response :accounts [])]
              (println (str "\n✓ Found " (count accounts) " account(s):\n"))

              (doseq [account accounts]
                (println "─────────────────────────────────────")
                (println "Account:" (:name account))
                (println "ID:" (:id account))
                (println "Balance:" (:balance account) (:currency account))
                (let [tx-count (count (get account :transactions []))]
                  (println "Transactions:" tx-count))
                (println))

              (println "\nFetching all transactions...")
              (let [full-config {:access-url access-url
                                :institution-mapping inst-mapping}
                    transactions (sf/fetch-all-transactions full-config)]

                (if (:error transactions)
                  (do
                    (println "ERROR:" (:error transactions))
                    (System/exit 1))

                  (do
                    (println (str "\n✓ Retrieved " (count transactions) " transaction(s)"))
                    (println "\nSample transactions (first 5):\n")

                    (doseq [tx (take 5 transactions)]
                      (println "─────────────────────────────────────")
                      (println "Date:" (:date tx))
                      (println "Amount:" (:amount tx))
                      (println "Description:" (:description tx))
                      (println "Institution:" (:institution tx))
                      (println "Account:" (:account-name tx))
                      (println "Type:" (:account-type tx))
                      (println))))))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))

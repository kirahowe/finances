#!/usr/bin/env bb
;; Setup script for SimpleFin Bridge
;; Usage: clojure -M scripts/setup_simplefin.clj <setup-token-url>

(require '[finance-aggregator.simplefin :as sf]
         '[clojure.pprint :as pp])

(defn -main [& args]
  (if-let [setup-token (first args)]
    (do
      (println "Exchanging setup token for access URL...")
      (let [result (sf/setup-token->access-url setup-token)]
        (if (:error result)
          (do
            (println "ERROR:" (:error result))
            (System/exit 1))
          (do
            (println "\n✓ Success! Your access URL is:")
            (println result)
            (println "\n⚠️  IMPORTANT: Save this URL in resources/config.edn")
            (println "You will NOT be able to retrieve it again!")
            (println "\nAdd this to your config.edn:")
            (println "{:simplefin {:access-url \"" result "\"}")
            (println "            :institution-mapping {#\"(?i)wealthsimple\" :wealthsimple")
            (println "                                 #\"(?i)scotiabank\" :scotiabank")
            (println "                                 #\"(?i)canadian.?tire\" :canadian-tire}}}")))))
    (do
      (println "Usage: clojure -M scripts/setup_simplefin.clj <setup-token-url>")
      (println "\nGet your setup token from: https://bridge.simplefin.org/simplefin/create")
      (System/exit 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

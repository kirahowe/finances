#!/usr/bin/env clojure
;; Setup script for SimpleFin Bridge
;; Usage: clojure -M scripts/setup_simplefin.clj <base64-setup-token>

(require '[finance-aggregator.simplefin :as sf]
         '[clojure.pprint :as pp])

(defn -main [& args]
  (if-let [setup-token (first args)]
    (do
      (println "Decoding setup token...")
      (let [claim-url (try
                        (sf/decode-setup-token setup-token)
                        (catch Exception e
                          (println "ERROR decoding token:" (.getMessage e))
                          (System/exit 1)))]
        (println "✓ Claim URL:" claim-url)
        (println "\nExchanging setup token for access URL...")
        (let [access-url (try
                          (sf/claim-setup-token setup-token)
                          (catch Exception e
                            (println "ERROR claiming token:" (.getMessage e))
                            (System/exit 1)))]
          (println "\n✓ Success! Your access URL is:")
          (println access-url)
          (println "\n⚠️  IMPORTANT: Save this URL securely!")
          (println "You will NOT be able to retrieve it again!")
          (println "\n=== Option 1: Environment Variable (Recommended) ===")
          (println "Add to your shell profile (~/.zshrc or ~/.bashrc):")
          (println (str "export SIMPLEFIN_ACCESS_URL=\"" access-url "\""))
          (println "\nThen reload your shell or run:")
          (println (str "source ~/.zshrc"))
          (println "\n=== Option 2: .env file (for this project only) ===")
          (println "Create a .env file in the project root (already gitignored):")
          (println (str "echo 'SIMPLEFIN_ACCESS_URL=\"" access-url "\"' > .env"))
          (println "\n=== Option 3: macOS Keychain ===")
          (println "security add-generic-password -a \"$USER\" -s \"simplefin-access-url\" -w \"" access-url "\""))))
    (do
      (println "Usage: clojure -M scripts/setup_simplefin.clj <base64-setup-token>")
      (println "\nGet your base64-encoded setup token from: https://beta-bridge.simplefin.org/simplefin/create")
      (println "It will look like: aHR0cHM6Ly9icmlkZ2Uuc2ltcGxlZmluLm9yZy9zaW1wbGVmaW4vY2xhaW0vZGVtbw==")
      (System/exit 1))))

(apply -main *command-line-args*)

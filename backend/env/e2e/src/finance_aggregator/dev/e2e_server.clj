(ns finance-aggregator.dev.e2e-server
  "Standalone HTTP server for end-to-end tests. Opens a clean Datalevin DB, seeds
   deterministic data, and serves the real API handler with empty Plaid/secrets
   (those components aren't needed for the transfer/transaction routes). Adds a
   test-only POST /e2e/reset that re-seeds, for per-test isolation.

   Run: clojure -M:e2e -m finance-aggregator.dev.e2e-server
   Env: E2E_PORT (default 8081), E2E_DB_PATH (default ./data/e2e.db)"
  (:require
   [finance-aggregator.db.core :as db]
   [finance-aggregator.dev.seed :as seed]
   [finance-aggregator.http.router :as router]
   [finance-aggregator.http.server :as server]
   [finance-aggregator.web.commands :as commands])
  (:gen-class))

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"
             "Access-Control-Allow-Origin" "*"}
   :body body})

(defn- reset-wrapper
  "Intercept POST /e2e/reset to re-seed; everything else hits the real handler.
   A seed failure returns a clean 500 (with CORS) rather than escaping raw, so the
   driving test sees an actionable error instead of an opaque connection failure."
  [app conn]
  (fn [req]
    (if (= "/e2e/reset" (:uri req))
      (try
        (seed/seed! conn)
        (commands/clear-all!) ; the in-memory undo log must not outlive the seed
        (json-response 200 "{\"success\":true}")
        (catch Exception e
          (println "E2E reset failed:" (.getMessage e))
          (json-response 500 "{\"success\":false,\"error\":\"seed failed\"}")))
      (app req))))

(defn -main [& _]
  (let [db-path (or (System/getenv "E2E_DB_PATH") "./data/e2e.db")
        port (Integer/parseInt (or (System/getenv "E2E_PORT") "8081"))]
    (db/delete-database! db-path)
    (let [conn (db/start-db! db-path)
          app (router/create-handler {:db-conn conn
                                      :secrets {}
                                      :plaid-config {}
                                      :cors-config {:allowed-origins ["*"]
                                                    :allowed-methods ["GET" "POST" "PUT" "DELETE" "OPTIONS"]
                                                    :allowed-headers ["*"]
                                                    :max-age 3600}})]
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn [] (try (db/stop-db! conn) (catch Throwable _ nil)))))
      (seed/seed! conn)
      (server/start-server! port (reset-wrapper app conn))
      (println (str "E2E server listening on http://localhost:" port " (db: " db-path ")"))
      (flush)
      @(promise))))

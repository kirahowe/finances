#!/usr/bin/env clojure
;; Start the web server
;; Usage: clojure -M scripts/start_server.clj

(require '[finance-aggregator.server :as server])

(println "Starting Finance Aggregator Web UI...")
(server/start-server! 8080)

;; Keep the process running
(println "Server running at http://localhost:8080")
(println "Press Ctrl+C to stop")

@(promise)

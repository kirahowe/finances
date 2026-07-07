(ns finance-aggregator.dev.main
  "Headless dev entry point: start the full system once AND host a cider-nrepl
   server in the SAME JVM.

   Why one JVM: Datalevin is single-writer per process. If the app server holds
   ./data/dev.db and you start a SECOND JVM (a standalone `clojure -M:repl`) and
   call (go), it blocks forever trying to open the already-locked DB. Running the
   app and the REPL in one process means one DB opener and no lock contention —
   this is what `bb dev` launches.

   Flow: `bb dev` -> `clojure -M:dev -m finance-aggregator.dev.main` -> here. This
   loads the `dev` ns (its set-prep! + (go)/(halt)/(reset)/(conn) helpers), starts
   the system, opens an nREPL on an OS-assigned port, and writes .nrepl-port.
   Connect your editor to that port; drive the system from the `dev` namespace.
   The JVM stays alive on nREPL's non-daemon threads after -main returns."
  (:require
   [clojure.java.io :as io]
   [cider.nrepl :refer [cider-nrepl-handler]]
   [finance-aggregator.lib.log :as log]
   [integrant.repl :as igr]
   [integrant.repl.state :as igs]
   [nrepl.server :as nrepl])
  (:gen-class))

(def ^:private port-file ".nrepl-port")

(defn -main [& _]
  (log/init!)
  ;; Loading `dev` registers integrant.repl/set-prep! and brings the REPL helpers
  ;; into a namespace you can switch to over nREPL.
  (require 'dev)
  (igr/go)
  (let [server (nrepl/start-server :port 0 :handler cider-nrepl-handler)
        port   (:port server)]
    (spit port-file (str port))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable
                               (fn []
                                 (log/info "Dev shutdown: stopping nREPL + halting system")
                                 (nrepl/stop-server server)
                                 (io/delete-file port-file true)
                                 (when igs/system (igr/halt)))))
    (log/info "Dev system ready" {:nrepl-port port})
    (println (str "\nFinance Aggregator dev ready."
                  "\n  HTTP:  http://localhost:8080"
                  "\n  nREPL: " port " (also written to backend/.nrepl-port)"
                  "\n  Connect your editor, then (in-ns 'dev); (go)/(reset)/(halt)/(conn) live there.\n"))
    (flush)))

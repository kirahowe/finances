(ns finance-aggregator.web.routes
  "Server-rendered hypermedia routes (hiccup2 SSR + Datastar over SSE), mounted alongside the
   JSON API on the same http-kit server. The transactions workspace lives at `/`; its view
   re-renders and edits are SSE fragment endpoints under `/transactions/*`."
  (:require
   [finance-aggregator.web.pages.setup :as setup]
   [finance-aggregator.web.pages.transactions :as transactions]))

(defn html-routes
  "Reitit route tree for the server-rendered hypermedia pages. `deps` (db-conn etc.) is
   closed over by the page handlers."
  [deps]
  [""
   ["/"        {:get {:handler (transactions/page deps) :name ::transactions}}]
   ["/transactions/rows"            {:get  {:handler (transactions/rows deps) :name ::rows}}]
   ["/transactions/:id/reconciled/:v" {:put  {:handler (transactions/toggle-reconciled deps) :name ::reconciled}}]
   ["/transactions/:id/description" {:put  {:handler (transactions/set-description deps) :name ::description}}]
   ["/transactions/:id/category"    {:put  {:handler (transactions/set-category deps) :name ::category}}]
   ["/transactions/:id/split-editor" {:get {:handler (transactions/split-editor deps) :name ::split-editor}}]
   ["/transactions/:id/splits"      {:put  {:handler (transactions/set-splits deps) :name ::splits}}]
   ["/transactions/:id/match"          {:get {:handler (transactions/match-editor deps) :name ::match-editor}}]
   ["/transactions/:id/match/:partner" {:put {:handler (transactions/confirm-match deps) :name ::confirm-match}}]
   ["/transactions/:id/unmatch"        {:put {:handler (transactions/unmatch deps) :name ::unmatch}}]
   ["/transactions/:id/posted-date-editor" {:get {:handler (transactions/posted-date-editor deps) :name ::posted-date-editor}}]
   ["/transactions/:id/posted-date"        {:put {:handler (transactions/set-posted-date deps) :name ::posted-date}}]
   ["/transactions/review-transfers"        {:get {:handler (transactions/review-transfers deps) :name ::review-transfers}}]
   ["/transactions/review/:out/confirm/:in" {:put {:handler (transactions/review-confirm deps) :name ::review-confirm}}]
   ["/transactions/review/:a/reject/:b"     {:put {:handler (transactions/review-reject deps) :name ::review-reject}}]
   ["/transactions/undo"            {:post {:handler (transactions/undo deps) :name ::undo}}]
   ["/transactions/redo"            {:post {:handler (transactions/redo deps) :name ::redo}}]
   ["/transactions/manual/new"          {:get  {:handler (transactions/add-transaction-editor deps) :name ::manual-new}}]
   ["/transactions/manual"              {:post {:handler (transactions/create-manual deps) :name ::manual-create}}]
   ["/transactions/:id/manual/delete"   {:get  {:handler (transactions/delete-transaction-editor deps) :name ::manual-delete-modal}
                                         :post {:handler (transactions/delete-manual deps) :name ::manual-delete}}]
   ["/transactions/reconcile"       {:post {:handler (transactions/set-reconcile-balances deps) :name ::reconcile}}]
   ["/transactions/statement-modal" {:get  {:handler (transactions/statement-editor deps) :name ::statement-modal}}]
   ["/transactions/statement"       {:post {:handler (transactions/save-statement deps) :name ::statement}}]
   ["/transactions/statement/delete" {:post {:handler (transactions/delete-statement deps) :name ::statement-delete}}]
   ["/transactions/close"           {:post {:handler (transactions/close-month deps) :name ::close}}]
   ["/transactions/reopen"          {:post {:handler (transactions/reopen-month deps) :name ::reopen}}]
   ["/setup"          {:get  {:handler (setup/page deps) :name ::setup}}]
   ["/setup/sync"     {:post {:handler (setup/sync-now deps) :name ::setup-sync}}]
   ["/setup/resync"   {:post {:handler (setup/resync-connection deps) :name ::setup-resync}}]
   ["/setup/lunchflow" {:get  {:handler (setup/lunchflow-page deps) :name ::setup-lunchflow}
                        :post {:handler (setup/lunchflow-connect deps) :name ::setup-lunchflow-connect}}]
   ["/setup/plaid/link-token" {:get  {:handler (setup/plaid-link-token deps) :name ::setup-plaid-link-token}}]
   ["/setup/plaid/exchange"   {:post {:handler (setup/plaid-exchange deps) :name ::setup-plaid-exchange}}]])

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
   ["/transactions/:id/reviewed/:v" {:put  {:handler (transactions/toggle-reviewed deps) :name ::reviewed}}]
   ["/transactions/:id/description" {:put  {:handler (transactions/set-description deps) :name ::description}}]
   ["/transactions/:id/category"    {:put  {:handler (transactions/set-category deps) :name ::category}}]
   ["/transactions/:id/split-editor" {:get {:handler (transactions/split-editor deps) :name ::split-editor}}]
   ["/transactions/:id/splits"      {:put  {:handler (transactions/set-splits deps) :name ::splits}}]
   ["/transactions/:id/match"          {:get {:handler (transactions/match-editor deps) :name ::match-editor}}]
   ["/transactions/:id/match/:partner" {:put {:handler (transactions/confirm-match deps) :name ::confirm-match}}]
   ["/transactions/:id/unmatch"        {:put {:handler (transactions/unmatch deps) :name ::unmatch}}]
   ["/transactions/review-transfers"        {:get {:handler (transactions/review-transfers deps) :name ::review-transfers}}]
   ["/transactions/review/:out/confirm/:in" {:put {:handler (transactions/review-confirm deps) :name ::review-confirm}}]
   ["/transactions/review/:a/reject/:b"     {:put {:handler (transactions/review-reject deps) :name ::review-reject}}]
   ["/transactions/undo"            {:post {:handler (transactions/undo deps) :name ::undo}}]
   ["/transactions/redo"            {:post {:handler (transactions/redo deps) :name ::redo}}]
   ["/setup"   {:get {:handler (setup/page deps) :name ::setup}}]])

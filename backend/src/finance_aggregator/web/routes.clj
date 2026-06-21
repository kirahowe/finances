(ns finance-aggregator.web.routes
  "Server-rendered hypermedia routes (hiccup2 SSR + Datastar over SSE), mounted alongside the
   JSON API on the same http-kit server. The transactions workspace lives at `/`; its view
   re-renders and edits are SSE fragment endpoints under `/transactions/*`."
  (:require
   [finance-aggregator.web.pages.setup :as setup]
   [finance-aggregator.web.pages.transactions2 :as transactions]))

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
   ["/transactions/undo"            {:post {:handler (transactions/undo deps) :name ::undo}}]
   ["/transactions/redo"            {:post {:handler (transactions/redo deps) :name ::redo}}]
   ["/setup"   {:get {:handler (setup/page deps) :name ::setup}}]])

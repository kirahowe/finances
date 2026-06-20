(ns finance-aggregator.web.routes
  "Server-rendered hypermedia routes (Replicant SSR + Datastar over SSE),
   mounted alongside the JSON API on the same http-kit server.

   This namespace currently hosts only the Phase-1 scaffold, which proves the
   pipeline end to end: server-rendered hiccup, pure-client Datastar reactivity,
   a server-authoritative SSE round-trip, and an esbuild island. Real pages land
   here (and under finance-aggregator.web.pages.*) as the migration proceeds."
  (:require
   [finance-aggregator.db.transactions :as db-transactions]
   [finance-aggregator.web.hiccup :as h]
   [finance-aggregator.web.layout :as layout]
   [finance-aggregator.web.month :as month]
   [finance-aggregator.web.pages.setup :as setup]
   [finance-aggregator.web.pages.transactions :as transactions]
   [starfederation.datastar.clojure.api :as d*]
   [starfederation.datastar.clojure.adapter.http-kit :as hk]))

;; ---------------------------------------------------------------------------
;; Phase-1 scaffold — delete once a real page owns these patterns.
;; ---------------------------------------------------------------------------

(defonce ^:private scaffold-state (atom {:last-synced nil}))

(defn- sync-fragment
  "Hiccup for the server-authoritative fragment. Carries the id Datastar morphs
   by; the page's initial render seeds the same id.

   Returns hiccup, not a rendered string: embedding a pre-rendered HTML string in
   a parent hiccup tree makes Replicant escape it as text (cf. gotcha §2). The
   page embeds this directly; the SSE handler renders it via h/render."
  []
  [:div#sync-result.scaffold-result
   (if-let [n (:last-synced @scaffold-state)]
     (str "server received count = " n)
     "server has not seen a sync yet")])

(defn- scaffold-page [_req]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body
   (layout/base-page
    {:title "Scaffold · Replicant + Datastar"
     :signals {:count 0}
     :islands ["hello"]}
    [:main.scaffold
     [:h1 "Replicant + Datastar scaffold"]
     [:p "If you can read this, server-rendered hiccup works."]

     [:section.scaffold-block
      [:h2 "1 · Datastar reactivity (pure client)"]
      [:p "Count: " [:strong (h/a {"data-text" "$count"})]]
      [:button.btn (h/a {"data-on:click" "$count++"}) "+1"]
      [:button.btn (h/a {"data-on:click" "$count--"}) "−1"]]

     [:section.scaffold-block
      [:h2 "2 · SSE round-trip (server-authoritative)"]
      [:button.btn (h/a {"data-on:click" "@post('/_scaffold/sync')"}) "Sync count to server"]
      (sync-fragment)]

     [:section.scaffold-block
      [:h2 "3 · esbuild island"]
      [:div#island-demo "(island not loaded)"]]])})

(defn- scaffold-sync
  "Write-behind style SSE handler: read the client's `$count` signal, store it
   server-side, and patch the server-rendered fragment back."
  [req]
  (let [signals (h/read-signals req)]
    (swap! scaffold-state assoc :last-synced (:count signals))
    (hk/->sse-response
     req
     {hk/on-open
      (fn [sse]
        (d*/patch-elements! sse (h/render (sync-fragment)))
        (d*/close-sse! sse))})))

;; ---------------------------------------------------------------------------
;; Transactions hypermedia handlers
;; ---------------------------------------------------------------------------

(defn- sync-reviewed-handler
  "Write-behind sink for the optimistic reviewed toggle: persist the `reviewed`
   signal map, then patch the server-authoritative toolbar counts (never the
   checkboxes — the optimistic client state stands)."
  [{:keys [db-conn]}]
  (fn [req]
    (let [signals (h/read-signals req)
          month   (month/serialize (month/parse (:month signals)))]
      (db-transactions/sync-reviewed! db-conn (:reviewed signals))
      (let [counts (db-transactions/month-counts (db-transactions/list-for-month db-conn month))]
        (hk/->sse-response
         req
         {hk/on-open
          (fn [sse]
            (d*/patch-elements! sse (transactions/counts-fragment counts))
            (d*/close-sse! sse))})))))

;; ---------------------------------------------------------------------------
;; Route tree
;; ---------------------------------------------------------------------------

(defn html-routes
  "Reitit route tree for the server-rendered hypermedia pages, mirroring the
   shape of finance-aggregator.http.routes.api/api-routes. `deps` (db-conn etc.)
   is closed over by page handlers as real pages are added."
  [deps]
  [""
   ["/"                      {:get {:handler (transactions/page deps) :name ::transactions}}]
   ["/transactions/reviewed" {:put {:handler (sync-reviewed-handler deps) :name ::sync-reviewed}}]
   ["/setup"                 {:get {:handler (setup/page deps) :name ::setup}}]
   ["/_scaffold"      {:get  {:handler scaffold-page :name ::scaffold}}]
   ["/_scaffold/sync" {:post {:handler scaffold-sync :name ::scaffold-sync}}]])

(ns finance-aggregator.web.routes
  "Server-rendered hypermedia routes (Replicant SSR + Datastar over SSE),
   mounted alongside the JSON API on the same http-kit server.

   This namespace currently hosts only the Phase-1 scaffold, which proves the
   pipeline end to end: server-rendered hiccup, pure-client Datastar reactivity,
   a server-authoritative SSE round-trip, and an esbuild island. Real pages land
   here (and under finance-aggregator.web.pages.*) as the migration proceeds."
  (:require
   [charred.api :as json]
   [finance-aggregator.db.transactions :as db-transactions]
   [finance-aggregator.web.hiccup :as h]
   [finance-aggregator.web.layout :as layout]
   [finance-aggregator.web.month :as month]
   [finance-aggregator.web.pages.rows-spike :as rows-spike]
   [finance-aggregator.web.pages.setup :as setup]
   [finance-aggregator.web.pages.transactions :as transactions]
   [finance-aggregator.web.pages.transactions2 :as transactions2]
   [starfederation.datastar.clojure.api :as d*]
   [starfederation.datastar.clojure.adapter.http-kit :as hk]))

;; ---------------------------------------------------------------------------
;; SSE helper
;; ---------------------------------------------------------------------------

(defn- sse-respond
  "Open an SSE response, run each patch fn (each given the sse generator) on open,
   then close. The shared shape of every hypermedia write-behind handler."
  [req & patch-fns]
  (hk/->sse-response
   req
   {hk/on-open
    (fn [sse]
      (run! #(% sse) patch-fns)
      (d*/close-sse! sse))}))

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
    (sse-respond req #(d*/patch-elements! % (h/render (sync-fragment))))))

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
        (sse-respond req #(d*/patch-elements! % (transactions/counts-fragment counts)))))))

(defn- set-description-handler
  "Write-behind sink for the inline description editor. Persists the row's `desc`
   signal as a user-description override, then patches the signal back to the
   authoritative effective description — so clearing the override reconciles to the
   imported description (which the optimistic blank can't know)."
  [{:keys [db-conn]}]
  (fn [req]
    (let [signals   (h/read-signals req)
          tx-id     (-> req :path-params :id parse-long)
          sig-key   (keyword (str "tx" tx-id))
          updated   (db-transactions/set-user-description! db-conn tx-id (get-in signals [:desc sig-key]))
          effective (or (not-empty (:transaction/effective-description updated)) "")]
      (sse-respond req #(d*/patch-signals! % (json/write-json-str {:desc {sig-key effective}}))))))

(defn- set-category-handler
  "Write-behind sink for the category combobox. Reads the row's `cat` signal (empty
   string clears the category), persists via update-category!, and patches the
   server-authoritative toolbar counts (a category change moves the uncategorized
   count). The combobox island already updated the cell optimistically."
  [{:keys [db-conn]}]
  (fn [req]
    (let [signals (h/read-signals req)
          tx-id   (-> req :path-params :id parse-long)
          month   (month/serialize (month/parse (:month signals)))
          cat-raw (get-in signals [:cat (keyword (str "tx" tx-id))])
          cat-id  (cond
                    (number? cat-raw) (long cat-raw)
                    (string? cat-raw) (some-> cat-raw not-empty parse-long)
                    :else nil)]
      (db-transactions/update-category! db-conn tx-id cat-id)
      (let [counts (db-transactions/month-counts (db-transactions/list-for-month db-conn month))]
        (sse-respond req #(d*/patch-elements! % (transactions/counts-fragment counts)))))))

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
   ["/transactions/:id/description" {:put {:handler (set-description-handler deps) :name ::set-description}}]
   ["/transactions/:id/category"    {:put {:handler (set-category-handler deps) :name ::set-category}}]
   ["/setup"                 {:get {:handler (setup/page deps) :name ::setup}}]
   ;; Phase R2 — server-authoritative transactions page (replaces / at R4).
   ["/v2"                    {:get {:handler (transactions2/page deps) :name ::v2}}]
   ["/v2/rows"               {:get {:handler (transactions2/rows deps) :name ::v2-rows}}]
   ["/v2/tx/:id/reviewed/:v" {:put {:handler (transactions2/toggle-reviewed deps) :name ::v2-reviewed}}]
   ["/v2/tx/:id/description" {:put {:handler (transactions2/set-description deps) :name ::v2-description}}]
   ["/v2/tx/:id/category"    {:put {:handler (transactions2/set-category deps) :name ::v2-category}}]
   ["/v2/undo"               {:post {:handler (transactions2/undo deps) :name ::v2-undo}}]
   ["/v2/redo"               {:post {:handler (transactions2/redo deps) :name ::v2-redo}}]
   ;; SPIKE — server-authoritative filtering over SSE (delete after the latency test).
   ["/spike"                 {:get {:handler (rows-spike/page deps) :name ::spike}}]
   ["/spike/rows"            {:get {:handler (rows-spike/rows deps) :name ::spike-rows}}]
   ["/spike/tx/:id/reviewed/:v" {:put {:handler (rows-spike/toggle-reviewed deps) :name ::spike-reviewed}}]
   ["/_scaffold"      {:get  {:handler scaffold-page :name ::scaffold}}]
   ["/_scaffold/sync" {:post {:handler scaffold-sync :name ::scaffold-sync}}]])

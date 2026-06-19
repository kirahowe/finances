(ns spike.server
  "Self-contained http-kit server for the spike. Routes:

     GET  /                     server-rendered page (Replicant)
     GET  /public/*             static assets (vendored datastar.js, css, island)
     PUT  /sync-reviewed        debounced write-behind: persist reviewed signals,
                                patch the server-authoritative counts chip via SSE
     PUT  /tx/:id/payee         persist an inline payee edit (SSE ack)

  Demonstrates the Datastar Clojure SDK over http-kit feeding fragments rendered
  by Replicant on the JVM."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as http]
            [replicant.string :as rs]
            [spike.data :as data]
            [spike.views :as views]
            [starfederation.datastar.clojure.api :as d*]
            [starfederation.datastar.clojure.adapter.http-kit :as hk]))

(defn- read-signals
  "Datastar's get-signals hands back the raw payload (an InputStream for PUT/POST);
  we bring our own JSON parsing, per the SDK docs. Returns a string-keyed map."
  [req]
  (let [raw (d*/get-signals req)]
    (when raw (json/read-str (slurp raw)))))

;; ---------------------------------------------------------------------------
;; Static files
;; ---------------------------------------------------------------------------

(def ^:private content-types
  {"js"  "text/javascript" "css" "text/css" "json" "application/json"
   "svg" "image/svg+xml" "html" "text/html"})

(defn- ext [path] (last (str/split path #"\.")))

(defn- static-handler [uri]
  (let [rel (subs uri (count "/public/"))
        res (io/resource (str "public/" rel))]
    (if (and res (not (str/includes? rel "..")))
      {:status 200
       :headers {"content-type" (get content-types (ext rel) "application/octet-stream")
                 "cache-control" "no-store"}
       :body (slurp res)}
      {:status 404 :body "not found"})))

;; ---------------------------------------------------------------------------
;; SSE handlers
;; ---------------------------------------------------------------------------

(defn sync-reviewed
  "Write-behind endpoint. Reads the current `reviewed` signal map off the request,
  persists the transaction-level flags, then patches the counts chip from
  server-authoritative state. Deliberately does NOT patch the checkboxes back —
  the optimistic client state stands (no per-toggle revalidation)."
  [req]
  (let [signals (read-signals req)
        reviewed (get signals "reviewed")]
    (doseq [[k v] reviewed
            :let [k (name k)]
            :when (str/starts-with? k "tx")]
      (data/set-reviewed! (parse-long (subs k 2)) (boolean v)))
    (hk/->sse-response
     req
     {hk/on-open
      (fn [sse]
        (d*/patch-elements! sse (rs/render (views/counts-chip)))
        (d*/close-sse! sse))})))

(defn set-payee
  "Persist an inline payee edit. The new value rode in on the `payee.txN` signal."
  [req tx-id]
  (let [signals (read-signals req)
        payees  (get signals "payee")
        v       (get payees (str "tx" tx-id))]
    (when v (data/set-payee! tx-id v))
    (hk/->sse-response
     req
     {hk/on-open
      (fn [sse]
        ;; Patch the counts chip as a visible server ack (value already optimistic).
        (d*/patch-elements! sse (rs/render (views/counts-chip)))
        (d*/close-sse! sse))})))

(defn set-category
  "Plain JSON endpoint (NOT Datastar) used by the combobox island — demonstrating
  a JSON API coexisting with the hypermedia pages. The same data layer backs both;
  only the response shaping differs (here: JSON; the Datastar handlers: HTML/SSE)."
  [req tx-id]
  (let [{:strs [category]} (json/read-str (slurp (:body req)))]
    (data/set-category! tx-id category)
    {:status 200 :headers {"content-type" "application/json"}
     :body (json/write-str {:ok true :category category})}))

;; ---------------------------------------------------------------------------
;; Routing
;; ---------------------------------------------------------------------------

(defn handler [{:keys [request-method uri] :as req}]
  (let [payee-match (re-matches #"/tx/(\d+)/payee" uri)
        cat-match   (re-matches #"/tx/(\d+)/category" uri)]
    (cond
      (and (= :get request-method) (= uri "/"))
      {:status 200 :headers {"content-type" "text/html"} :body (views/page)}

      (str/starts-with? uri "/public/")
      (static-handler uri)

      (and (= :put request-method) (= uri "/sync-reviewed"))
      (sync-reviewed req)

      (and (= :put request-method) payee-match)
      (set-payee req (parse-long (second payee-match)))

      (and (= :put request-method) cat-match)
      (set-category req (parse-long (second cat-match)))

      :else
      {:status 404 :headers {"content-type" "text/plain"} :body "not found"})))

(defonce ^:private !server (atom nil))

(defn start! [port]
  (when-let [stop @!server] (stop))
  (reset! !server (http/run-server #'handler {:port port :legacy-return-value? false}))
  (println (str "spike server up on http://localhost:" port))
  @!server)

(defn -main [& args]
  (let [port (parse-long (or (first args) "7777"))]
    (start! port)
    @(promise)))

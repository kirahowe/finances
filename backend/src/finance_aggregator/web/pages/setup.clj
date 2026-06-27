(ns finance-aggregator.web.pages.setup
  "Handler (glue) for /setup: fetch → present → render → respond. Business rules
   live in the data layer (db.*) and the presenter (web.accounts/present); the
   hiccup lives in web.pages.setup-view. The sync actions fire a background resync
   (statuses persist; refreshing /setup shows progress) and redirect back."
  (:require
   [finance-aggregator.db.accounts :as db-accounts]
   [finance-aggregator.db.connections :as db-connections]
   [finance-aggregator.db.stats :as db-stats]
   [finance-aggregator.lib.log :as log]
   [finance-aggregator.resync :as resync]
   [finance-aggregator.web.accounts :as accounts]
   [finance-aggregator.web.layout :as layout]
   [finance-aggregator.web.pages.setup-view :as view])
  (:import
   [java.util Date]))

(defn- redirect-to-setup []
  {:status 303 :headers {"Location" "/setup"}})

(defn- background
  "Run a resync action in the background, logging (never propagating) failures
   outside the engine's per-connection isolation. The 303 never waits on it."
  [label thunk]
  (future
    (try (thunk)
         (catch Throwable t
           (log/error label {:error (.getMessage t)})))))

(defn sync-now
  "Factory: POST /setup/sync — fire one resilient-sync pass over every due
   connection in the background, then redirect back."
  [deps]
  (fn [_req]
    (background "Background resync pass failed" #(resync/resync-all! deps))
    (redirect-to-setup)))

(defn resync-connection
  "Factory: POST /setup/resync — resync a single connection (form field
   connection-id) in the background, then redirect back. Unknown/blank ids are a
   no-op redirect."
  [{:keys [db-conn] :as deps}]
  (fn [req]
    (when-let [id (not-empty (get-in req [:params "connection-id"]))]
      (when (db-connections/get-connection db-conn id)
        (background "Background per-connection resync failed"
                    #(resync/resync-connection! deps {:connection/id id}))))
    (redirect-to-setup)))

(defn page
  "Factory: GET /setup — render the stats strip + connection cards."
  [{:keys [db-conn]}]
  (fn [_req]
    (let [model (accounts/present
                 {:stats       (db-stats/entity-counts db-conn)
                  :connections (db-connections/list-connections db-conn)
                  :accounts    (db-accounts/list-with-institution db-conn)
                  :now         (Date.)})]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (layout/document {:title "Setup · Finance Aggregator"}
                              (view/body model))})))

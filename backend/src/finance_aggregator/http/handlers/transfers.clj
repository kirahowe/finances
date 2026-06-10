(ns finance-aggregator.http.handlers.transfers
  "Transfer-matching handlers.

   Endpoints:
   - GET    /api/transfers/suggestions          - Auto-match candidate pairs
   - POST   /api/transfers                       - Confirm a match {inflowId outflowId}
   - DELETE /api/transfers/:id                   - Unmatch a transaction
   - POST   /api/transfers/reject                - Record a rejected pair {aId bId}
   - GET    /api/transfers/candidates?transactionId=X - Manual-match candidates"
  (:require
   [finance-aggregator.db.transfers :as db-transfers]
   [finance-aggregator.http.responses :as responses]))

(defn- ->id
  "Coerce a request-supplied transaction id (a JSON number or a path/query string)
   to a long, throwing :bad-request (400) for a missing or non-numeric value rather
   than letting a raw NPE/parse error surface as an opaque 500."
  [field v]
  (let [n (cond
            (integer? v) (long v)
            (string? v) (parse-long v))]
    (or n (throw (ex-info (str "Invalid or missing " field) {:type :bad-request})))))

(defn suggestions-handler
  [{:keys [db-conn]}]
  (fn [_request]
    (responses/success-response (db-transfers/suggest-matches db-conn))))

(defn confirm-handler
  [{:keys [db-conn]}]
  (fn [request]
    (let [{:keys [inflowId outflowId]} (:body-params request)]
      (responses/success-response
       (db-transfers/confirm-match! db-conn (->id "outflowId" outflowId) (->id "inflowId" inflowId))))))

(defn unmatch-handler
  [{:keys [db-conn]}]
  (fn [request]
    (let [tx-id (->id "id" (-> request :path-params :id))]
      (responses/success-response (db-transfers/unmatch! db-conn tx-id)))))

(defn reject-handler
  [{:keys [db-conn]}]
  (fn [request]
    (let [{:keys [aId bId]} (:body-params request)]
      (responses/success-response
       (db-transfers/reject-match! db-conn (->id "aId" aId) (->id "bId" bId))))))

(defn candidates-handler
  [{:keys [db-conn]}]
  (fn [request]
    (let [tx-id (->id "transactionId" (get-in request [:query-params "transactionId"]))]
      (responses/success-response (db-transfers/match-candidates db-conn tx-id)))))

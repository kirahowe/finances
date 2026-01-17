(ns finance-aggregator.ws.handler
  "WebSocket connection handler using http-kit.

   Handles:
   - Connection lifecycle (connect, disconnect)
   - Message routing (subscribe, unsubscribe, trigger-sync)
   - Broadcasting state updates to clients

   Message protocol (JSON over WebSocket):

   Client -> Server:
   {:type 'subscribe' :item-id 'xxx'}
   {:type 'unsubscribe' :item-id 'xxx'}
   {:type 'trigger-sync' :item-id 'xxx'}

   Server -> Client:
   {:type 'sync-status' :item-id 'xxx' :state {...}}
   {:type 'connected' :channel-id 'xxx'}
   {:type 'error' :message 'xxx'}"
  (:require
   [clojure.data.json :as json]
   [finance-aggregator.lib.log :as log]
   [finance-aggregator.ws.state :as state]
   [org.httpkit.server :as http-kit])
  (:import
   [java.util UUID]))

;;; JSON Helpers

(defn- parse-message
  "Parse incoming JSON message. Returns nil on parse failure."
  [raw-message]
  (try
    (json/read-str raw-message :key-fn keyword)
    (catch Exception e
      (log/warn "Failed to parse WebSocket message" {:error (.getMessage e)})
      nil)))

(defn- encode-message
  "Encode message as JSON string."
  [message]
  (json/write-str message))

;;; Send Helper

(defn- send-to-channel!
  "Send a message to a WebSocket channel."
  [channel message]
  (when (http-kit/open? channel)
    (http-kit/send! channel (encode-message message))))

;;; Message Handlers

(defmulti handle-message*
  "Dispatch incoming messages by type."
  (fn [_channel-id _channel message] (:type message)))

(defmethod handle-message* "subscribe"
  [channel-id channel {:keys [item-id]}]
  (if item-id
    (if (state/subscribed? channel-id item-id)
      ;; Already subscribed - just acknowledge
      (send-to-channel! channel {:type "subscribed" :item-id item-id})
      ;; New subscription
      (do
        (state/subscribe! channel-id item-id)
        (send-to-channel! channel {:type "subscribed" :item-id item-id})))
    (send-to-channel! channel {:type "error" :message "item-id required"})))

(defmethod handle-message* "unsubscribe"
  [channel-id channel {:keys [item-id]}]
  (if item-id
    (do
      (state/unsubscribe! channel-id item-id)
      (send-to-channel! channel {:type "unsubscribed" :item-id item-id}))
    (send-to-channel! channel {:type "error" :message "item-id required"})))

(defmethod handle-message* "ping"
  [_channel-id channel _message]
  (send-to-channel! channel {:type "pong"}))

(defmethod handle-message* :default
  [_channel-id channel message]
  (log/warn "Unknown WebSocket message type" {:type (:type message)})
  (send-to-channel! channel {:type "error" :message (str "Unknown message type: " (:type message))}))

;;; Connection Lifecycle

(defn- on-connect
  "Handle new WebSocket connection."
  [channel-id channel]
  (state/register-connection! channel-id channel)
  (send-to-channel! channel {:type "connected" :channel-id (str channel-id)})
  (log/info "WebSocket client connected" {:channel-id channel-id}))

(defn- on-disconnect
  "Handle WebSocket disconnection."
  [channel-id status]
  (state/unregister-connection! channel-id)
  (log/info "WebSocket client disconnected" {:channel-id channel-id :status status}))

(defn- on-receive
  "Handle incoming WebSocket message."
  [channel-id channel raw-message]
  (if-let [message (parse-message raw-message)]
    (handle-message* channel-id channel message)
    (send-to-channel! channel {:type "error" :message "Invalid JSON"})))

;;; Ring Handler

(defn ws-handler
  "Ring handler for WebSocket upgrade.

   Usage in routes:
   [\"/ws\" {:get ws-handler}]"
  [request]
  (let [channel-id (UUID/randomUUID)]
    (http-kit/as-channel request
      {:on-open (fn [channel]
                  (on-connect channel-id channel))
       :on-close (fn [channel status]
                   (on-disconnect channel-id status))
       :on-receive (fn [channel message]
                     (on-receive channel-id channel message))})))

;;; Initialization

(defn init!
  "Initialize WebSocket system. Call on app startup."
  []
  ;; Wire up broadcast function to avoid circular dependency
  (state/set-broadcast-fn! send-to-channel!)
  (log/info "WebSocket system initialized"))

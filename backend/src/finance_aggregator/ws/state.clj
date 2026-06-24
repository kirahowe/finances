(ns finance-aggregator.ws.state
  "In-memory sync state management.

   Inspired by Phoenix LiveView - server is the single source of truth.
   State changes trigger broadcasts to subscribed WebSocket clients.

   State structure:
   {item-id {:status :pending|:syncing|:synced|:failed
             :institution-name string
             :transaction-count int
             :progress {:added int :modified int :removed int}
             :error string|nil
             :updated-at instant}}"
  (:require
   [finance-aggregator.lib.log :as log]
   [tick.core :as t]))

;;; State Atoms

;; Sync status for each Plaid item. Updated during sync, triggers broadcasts.
(defonce ^:private sync-state (atom {}))

;; Connected WebSocket clients and their subscriptions.
;; {channel-id {:channel http-kit-channel, :subscriptions #{item-id ...}}}
(defonce ^:private connections (atom {}))

;; Function to broadcast messages. Set by ws.handler to avoid circular deps.
;; Signature: (fn [channel message] ...)
(defonce ^:private broadcast-fn (atom nil))

;;; Broadcast Setup

(defn set-broadcast-fn!
  "Set the broadcast function. Called by ws.handler on init."
  [f]
  (reset! broadcast-fn f))

(defn- broadcast-to-subscribers!
  "Send state update to all clients subscribed to this item-id."
  [item-id state]
  (when-let [send-fn @broadcast-fn]
    (let [message {:type :sync-status
                   :item-id item-id
                   :state state}]
      (doseq [[_channel-id {:keys [channel subscriptions]}] @connections
              :when (contains? subscriptions item-id)]
        (try
          (send-fn channel message)
          (catch Exception e
            (log/warn "Failed to send to WebSocket client" {:error (.getMessage e)})))))))

;;; State Watcher

(defn- state-watcher
  "Watch sync-state atom and broadcast changes to subscribers."
  [_key _ref old-state new-state]
  (doseq [[item-id new-item-state] new-state
          :when (not= (get old-state item-id) new-item-state)]
    (broadcast-to-subscribers! item-id new-item-state)))

;; Add watcher on namespace load
(add-watch sync-state :broadcast-changes state-watcher)

;;; Connection Management

(defn register-connection!
  "Register a new WebSocket connection."
  [channel-id channel]
  (swap! connections assoc channel-id {:channel channel
                                        :subscriptions #{}})
  (log/debug "WebSocket connection registered" {:channel-id channel-id}))

(defn unregister-connection!
  "Remove a WebSocket connection."
  [channel-id]
  (swap! connections dissoc channel-id)
  (log/debug "WebSocket connection unregistered" {:channel-id channel-id}))

(defn subscribe!
  "Subscribe a connection to sync updates for an item."
  [channel-id item-id]
  (swap! connections update-in [channel-id :subscriptions] (fnil conj #{}) item-id)
  (log/debug "Client subscribed" {:channel-id channel-id :item-id item-id})
  ;; Send current state immediately if available
  (when-let [current-state (get @sync-state item-id)]
    (when-let [send-fn @broadcast-fn]
      (when-let [channel (get-in @connections [channel-id :channel])]
        (send-fn channel {:type :sync-status
                          :item-id item-id
                          :state current-state})))))

(defn unsubscribe!
  "Unsubscribe a connection from an item's updates."
  [channel-id item-id]
  (swap! connections update-in [channel-id :subscriptions] disj item-id)
  (log/debug "Client unsubscribed" {:channel-id channel-id :item-id item-id}))

(defn subscribed?
  "Check if a connection is already subscribed to an item."
  [channel-id item-id]
  (contains? (get-in @connections [channel-id :subscriptions]) item-id))

;;; Sync State Updates (called by plaid.service)

(defn update-sync-status!
  "Update sync status for an item. Triggers broadcast to subscribers.

   item-id: Plaid item ID
   status: :pending, :syncing, :synced, or :failed
   opts: {:institution-name string
          :transaction-count int
          :progress {:added int :modified int :removed int}
          :error string}"
  [item-id status & {:keys [institution-name transaction-count progress error]
                     :or {transaction-count 0}}]
  (swap! sync-state assoc item-id
         {:status status
          :institution-name institution-name
          :transaction-count transaction-count
          :progress progress
          :error error
          :updated-at (t/now)})
  (log/debug "Sync status updated" {:item-id item-id :status status}))

(defn get-sync-status
  "Get current sync status for an item (for REST fallback)."
  [item-id]
  (get @sync-state item-id))

(defn clear-sync-status!
  "Clear sync status for an item (e.g., after timeout or cleanup)."
  [item-id]
  (swap! sync-state dissoc item-id))

;;; Debug/Admin

(defn get-all-sync-states
  "Get all current sync states (for debugging)."
  []
  @sync-state)

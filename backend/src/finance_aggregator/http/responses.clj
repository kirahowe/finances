(ns finance-aggregator.http.responses
  "Response helper functions for consistent HTTP responses.

   Provides:
   - JSON response generation
   - Success/error response envelopes
   - Standard status code helpers"
  (:require
   [charred.api :as json]))

;;
;; JSON Response Helpers
;;

(defn json-response
  "Create a JSON response with given data and optional status code.

   Args:
     data - Data to serialize as JSON
     status - HTTP status code (default: 200)

   Returns:
     Ring response map with JSON body"
  ([data]
   (json-response data 200))
  ([data status]
   {:status status
    :headers {"Content-Type" "application/json"}
    :body (json/write-json-str data)}))

;;
;; Success Response Envelope
;;

(defn success-response
  "Create a success response with data wrapped in standard envelope.

   Response format: {:success true :data <data>}

   Args:
     data - Data to include in response
     status - HTTP status code (default: 200)

   Returns:
     Ring response map with JSON body"
  ([data]
   (success-response data 200))
  ([data status]
   (json-response {:success true :data data} status)))

;;
;; Standard Response Helpers
;;

(defn no-content-response
  "Create a 204 No Content response.

   Returns:
     Ring response map with 204 status and no body"
  []
  {:status 204})

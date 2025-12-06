(ns finance-aggregator.http.errors
  "Error handling utilities and exception middleware for HTTP layer.

   Provides:
   - Error response generation
   - Exception to HTTP response conversion
   - Exception handling middleware

   Error types (via ex-info :type key):
   - :bad-request (400)
   - :not-found (404)
   - :conflict (409)
   - default: 500"
  (:require
   [charred.api :as json]))

;;
;; Error Response Generation
;;

(defn error-response
  "Create an error response map with given status and message.

   Args:
     status - HTTP status code
     message - Error message string
     data - Optional map of additional error data

   Returns:
     Ring response map with JSON body"
  ([status message]
   (error-response status message nil))
  ([status message data]
   {:status status
    :headers {"Content-Type" "application/json"}
    :body (json/write-json-str
           (cond-> {:success false
                    :error message}
             data (merge data)))}))

;;
;; Exception to Response Conversion
;;

(def ^:private type->status
  "Map of error types to HTTP status codes"
  {:bad-request 400
   :not-found 404
   :conflict 409})

(defn exception->response
  "Convert an exception to an HTTP error response.

   For ex-info exceptions, uses :type key from ex-data to determine status:
   - :bad-request -> 400
   - :not-found -> 404
   - :conflict -> 409
   - default -> 500

   Args:
     ex - Exception or Throwable

   Returns:
     Ring response map with error details"
  [ex]
  (let [message (.getMessage ex)
        data (when (instance? clojure.lang.ExceptionInfo ex)
               (ex-data ex))
        error-type (:type data)
        status (get type->status error-type 500)
        ;; Remove :type from data to avoid duplication in response
        response-data (dissoc data :type)]
    (error-response status message response-data)))

;;
;; Exception Handling Middleware
;;

(defn wrap-exception-handling
  "Middleware that catches exceptions and converts them to error responses.

   Wraps the handler to catch any thrown exceptions and convert them to
   appropriate HTTP error responses using exception->response.

   Args:
     handler - Ring handler function

   Returns:
     Wrapped handler function"
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception ex
        (exception->response ex)))))

(ns finance-aggregator.http.middleware
  "HTTP middleware for CORS, JSON handling, and request processing.

   Provides:
   - CORS headers middleware
   - JSON request body parsing
   - JSON response serialization
   - Combined JSON middleware"
  (:require
   [charred.api :as json]
   [clojure.java.io :as io]))

;;
;; CORS Middleware
;;

(defn wrap-cors
  "Middleware to add CORS headers to responses.

   Adds the following headers:
   - Access-Control-Allow-Origin: *
   - Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS
   - Access-Control-Allow-Headers: Content-Type

   Handles OPTIONS preflight requests.

   Args:
     handler - Ring handler function

   Returns:
     Wrapped handler function"
  [handler]
  (fn [request]
    (if (= :options (:request-method request))
      ;; Handle CORS preflight
      {:status 200
       :headers {"Access-Control-Allow-Origin" "*"
                 "Access-Control-Allow-Methods" "GET, POST, PUT, DELETE, OPTIONS"
                 "Access-Control-Allow-Headers" "Content-Type"}}
      ;; Add CORS headers to actual response
      (let [response (handler request)]
        (update response :headers
                (fn [headers]
                  (merge {"Access-Control-Allow-Origin" "*"
                          "Access-Control-Allow-Methods" "GET, POST, PUT, DELETE, OPTIONS"
                          "Access-Control-Allow-Headers" "Content-Type"}
                         headers)))))))

;;
;; JSON Request Middleware
;;

(defn- parse-json-body
  "Parse JSON from input stream or string body.

   Args:
     body - Request body (InputStream, String, or nil)

   Returns:
     Parsed data structure or nil"
  [body]
  (when body
    (try
      (if (string? body)
        (json/read-json body :key-fn keyword)
        (json/read-json (slurp body) :key-fn keyword))
      (catch Exception _
        nil))))

(defn wrap-json-request
  "Middleware to parse JSON request bodies.

   Parses JSON body and adds it to request as :body-params.
   Only processes requests with Content-Type: application/json.

   Args:
     handler - Ring handler function

   Returns:
     Wrapped handler function"
  [handler]
  (fn [request]
    (let [content-type (get-in request [:headers "content-type"])
          json-request? (and content-type
                             (re-find #"application/json" content-type))]
      (if json-request?
        (let [body-params (parse-json-body (:body request))
              updated-request (assoc request :body-params body-params)]
          (handler updated-request))
        (handler request)))))

;;
;; JSON Response Middleware
;;

(defn wrap-json-response
  "Middleware to serialize response bodies as JSON.

   Serializes map bodies to JSON and adds Content-Type header.
   Preserves string and nil bodies unchanged.

   Args:
     handler - Ring handler function

   Returns:
     Wrapped handler function"
  [handler]
  (fn [request]
    (let [response (handler request)
          body (:body response)]
      (if (and body (not (string? body)))
        ;; Serialize non-string bodies to JSON
        (-> response
            (assoc :body (json/write-json-str body))
            (assoc-in [:headers "Content-Type"] "application/json"))
        ;; Keep string/nil bodies as-is
        response))))

;;
;; Combined JSON Middleware
;;

(defn wrap-json
  "Combined middleware for JSON request and response handling.

   Combines wrap-json-request and wrap-json-response.

   Args:
     handler - Ring handler function

   Returns:
     Wrapped handler function"
  [handler]
  (-> handler
      wrap-json-response
      wrap-json-request))

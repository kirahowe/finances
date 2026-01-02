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

(defn- format-cors-value
  "Format a CORS config value for HTTP header.
   Vectors become comma-separated strings, strings pass through unchanged."
  [v]
  (if (vector? v)
    (clojure.string/join ", " v)
    v))

(defn wrap-cors
  "Middleware to add CORS headers to responses.

   Handles OPTIONS preflight requests.

   Args:
     handler - Ring handler function
     cors-config - Map with :allowed-origins, :allowed-methods, :allowed-headers, :max-age

   Returns:
     Wrapped handler function"
  [handler {:keys [allowed-origins allowed-methods allowed-headers max-age]
            :or {allowed-origins ["*"]
                 allowed-methods ["GET" "POST" "PUT" "DELETE" "OPTIONS"]
                 allowed-headers ["*"]
                 max-age 3600}}]
  (let [cors-headers {"Access-Control-Allow-Origin" (format-cors-value allowed-origins)
                      "Access-Control-Allow-Methods" (format-cors-value allowed-methods)
                      "Access-Control-Allow-Headers" (format-cors-value allowed-headers)
                      "Access-Control-Max-Age" (str max-age)}]
    (fn [request]
      (if (= :options (:request-method request))
        ;; Handle CORS preflight
        {:status 200
         :headers cors-headers}
        ;; Add CORS headers to actual response
        (let [response (handler request)]
          (update response :headers
                  (fn [headers]
                    (merge cors-headers headers))))))))

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
    (let [headers (:headers request)
          ;; Try multiple ways to get content-type (supports different header key formats)
          content-type (or (get headers "content-type")
                          (get headers :content-type)
                          (get headers "Content-Type"))
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

(ns nurt.io.http
  "HTTP IO operations for making HTTP requests using hato.

  This namespace provides low-level HTTP operations that can be used independently
  or by effect handlers. Requests are made using the hato HTTP client with
  support for all HTTP methods, headers, response coercion, and automatic
  JSON serialization for request bodies."
  (:require [hato.client :as http]
            [nurt.internal.json :as json]))

(defn- process-request-body
  "Processes the request body, auto-serializing maps to JSON when appropriate.

  If the body is a map/vector and Content-Type is application/json (or not set),
  it will be automatically JSON-serialized. Otherwise, the body is passed through.

  Args:
    request - The request map containing :body and :headers

  Returns:
    Modified request map with processed body and updated headers"
  [request]
  (let [{:keys [body headers]} request
        content-type (get headers "Content-Type" (get headers "content-type"))]
    (if (and (or (map? body) (vector? body))
             (or (nil? content-type)
                 (.contains (str content-type) "application/json")))
      (-> request
          (assoc :body (json/write-str body))
          (assoc-in [:headers "Content-Type"] "application/json"))
      request)))

(defn- process-response-body
  "Processes the response body, auto-parsing JSON when appropriate.

  If the response Content-Type is application/json and body is a string,
  it will be automatically JSON-parsed. Otherwise, the body is passed through.

  Args:
    response - The response map containing :body and :headers
    options - Request options containing :key-fn for JSON parsing

  Returns:
    Modified response map with processed body"
  [response {:keys [key-fn]}]
  (let [{:keys [body headers]} response
        content-type (or (get headers "content-type")
                        (get headers "Content-Type"))]
    (if (and (string? body)
             content-type
             (.contains (str content-type) "application/json"))
      (try
        (assoc response :body (if key-fn
                               (json/read-str body :key-fn key-fn)
                               (json/read-str body)))
        (catch Exception _
          response))
      response)))

(defn parse-json
  "Parses a JSON string into a Clojure data structure.

  Args:
    json-str - The JSON string to parse
    options - Optional map containing:
      :key-fn - Function to transform JSON keys (e.g., keyword)

  Returns:
    Clojure data structure

  Examples:
    (parse-json \"{\\\"user-id\\\":123}\")
    => {\"user-id\" 123}

    (parse-json \"{\\\"user-id\\\":123}\" {:key-fn keyword})
    => {:user-id 123}"
  [json-str & [options]]
  (let [{:keys [key-fn]} options]
    (if key-fn
      (json/read-str json-str :key-fn key-fn)
      (json/read-str json-str))))

(defn generate-json
  "Converts a Clojure data structure to a JSON string.

  Args:
    data - The Clojure data structure to serialize

  Returns:
    JSON string representation of the data

  Examples:
    (generate-json {:user-id 123 :name \"John\"})
    => \"{\\\"user-id\\\":123,\\\"name\\\":\\\"John\\\"}\""
  [data]
  (json/write-str data))

(defn request!
  "Makes an HTTP request using hato client.

  Supports all HTTP methods with configurable options including headers,
  query parameters, request body, timeouts, and response coercion.

  Automatically serializes Clojure maps and vectors to JSON when:
  - Content-Type is application/json, or
  - Content-Type is not specified and body is a map/vector

  Automatically parses JSON responses when:
  - Response Content-Type is application/json
  - :parse-json? is true (default: true)

  Args:
    context - Execution context containing optional :http-client config
    request - Map containing:
      :method - HTTP method keyword (:get, :post, :put, :delete, etc.)
      :url - Request URL (string)
      :headers - Optional map of HTTP headers
      :query-params - Optional map of query parameters
      :body - Optional request body (string, map, etc.)
      :form-params - Optional form parameters for POST requests
      :as - Optional response coercion (:json, :text, :stream, etc.)
      :timeout - Optional timeout in milliseconds
      :throw-exceptions - Whether to throw on HTTP error status (default true)
      :parse-json? - Whether to auto-parse JSON responses (default true)
      :key-fn - Function to transform JSON keys (e.g., keyword)

  Returns:
    HTTP response map containing :status, :headers, :body, etc.

  Examples:
    ;; Simple GET request with auto JSON parsing
    (request! {}
              {:method :get
               :url \"https://api.example.com/users\"
               :headers {\"Authorization\" \"Bearer token\"}})

    ;; POST with automatic JSON serialization and parsing
    (request! {}
              {:method :post
               :url \"https://api.example.com/users\"
               :body {:name \"John\" :email \"john@example.com\"}})

    ;; GET with keyword JSON keys
    (request! {}
              {:method :get
               :url \"https://api.example.com/users\"
               :key-fn keyword})

    ;; Disable auto JSON parsing
    (request! {}
              {:method :get
               :url \"https://api.example.com/users\"
               :parse-json? false})"
  [context request]
  (let [http-client-config (:http-client context)
        processed-request (process-request-body request)
        full-request (if http-client-config
                       (merge processed-request {:http-client http-client-config})
                       processed-request)
        response (http/request full-request)
        parse-json? (get request :parse-json? true)]
    (if parse-json?
      (process-response-body response request)
      response)))


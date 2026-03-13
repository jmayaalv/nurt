(ns nurt.effect.http
  (:require [clojure.spec.alpha :as s]
            [nurt.effect :as effect]
            [nurt.io.http :as io-http]))

(s/def ::method
  #{:get :post :put :delete :patch :head :options})

(s/def ::url string?)

(s/def ::headers
  (s/map-of string? string?))

(s/def ::query-params
  (s/map-of (s/or :string string? :keyword keyword?) any?))

(s/def ::body any?)

(s/def ::form-params
  (s/map-of (s/or :string string? :keyword keyword?) any?))

(s/def ::as
  #{:json :text :stream :auto :bytes})

(s/def ::timeout pos-int?)

(s/def ::throw-exceptions boolean?)

(s/def ::parse-json? boolean?)

(s/def ::key-fn fn?)

(s/def ::http-request
  (s/keys :req-un [::method ::url]
          :opt-un [::headers ::query-params ::body ::form-params
                   ::as ::timeout ::throw-exceptions ::parse-json? ::key-fn]))

(defmethod effect/effect-spec :http [_]
  (s/keys :req-un [::http-request]
          :req [:effect/type]))

(defn http
  "Creates an HTTP effect for making HTTP requests.
  
  Accepts a request map containing HTTP method, URL, and optional parameters
  like headers, query parameters, request body, and response coercion options.
  
  Automatically serializes Clojure maps and vectors in the request body to JSON
  when Content-Type is application/json or not specified.
  
  Automatically parses JSON responses when Content-Type is application/json
  and :parse-json? is true (default).
  
  Args:
    http-request - Map containing:
      :method - HTTP method keyword (:get, :post, :put, :delete, etc.) (required)
      :url - Request URL (string) (required)
      :headers - Optional map of HTTP headers
      :query-params - Optional map of query parameters
      :body - Optional request body
      :form-params - Optional form parameters for POST requests
      :as - Optional response coercion (:json, :text, :stream, etc.)
      :timeout - Optional timeout in milliseconds
      :throw-exceptions - Whether to throw on HTTP error status
      :parse-json? - Whether to auto-parse JSON responses (default true)
      :key-fn - Function to transform JSON keys (e.g., keyword)
  
  Returns:
    HTTP effect map with :effect/type :http and :http-request
  
  Examples:
    ;; Simple GET request with auto JSON parsing
    (http {:method :get
           :url \"https://api.example.com/users\"
           :headers {\"Authorization\" \"Bearer token\"}})
    
    ;; POST with automatic JSON serialization and parsing
    (http {:method :post
           :url \"https://api.example.com/users\"
           :body {:name \"John\" :email \"john@example.com\"}})
    
    ;; GET with keyword JSON keys
    (http {:method :get
           :url \"https://api.example.com/users\"
           :key-fn keyword})
    
    ;; Disable auto JSON parsing
    (http {:method :get
           :url \"https://api.example.com/search\"
           :parse-json? false})"
  [http-request]
  {:effect/type :http
   :http-request http-request})

(defn http!
  "Executes an HTTP effect by making the specified HTTP request.
  
  This is the effect handler function that performs the actual HTTP request.
  It's automatically called by the Nurt broker when processing :http effects.
  
  Args:
    effect - HTTP effect map containing :http-request
    context - Execution context containing optional :http-client config
  
  Returns:
    HTTP response map from the request
  
  Note:
    This function is typically not called directly. It's intended to be
    registered as an effect handler in the broker and called automatically
    during command processing."
  [{:keys [http-request]} context]
  (io-http/request! context http-request))
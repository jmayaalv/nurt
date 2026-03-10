(ns nurt.internal.json
  "Internal JSON abstraction layer for Nurt.
  
  This namespace provides a unified interface for JSON operations, abstracting
  the underlying JSON library implementation. This makes it easier to change
  JSON libraries in the future without modifying code throughout the codebase.
  
  Currently uses charred for high-performance JSON processing, but can be easily
  swapped for other libraries by only changing this namespace."
  (:require [charred.api :as json]))

(defn write-str
  "Converts a Clojure data structure to a JSON string.
  
  Args:
    data - The Clojure data structure to serialize
    
  Returns:
    JSON string representation of the data
    
  Examples:
    (write-str {:user-id 123 :name \"John\"})
    => \"{\\\"user-id\\\":123,\\\"name\\\":\\\"John\\\"}\""
  [data]
  (json/write-json-str data))

(defn read-str
  "Parses a JSON string into a Clojure data structure.
  
  Args:
    json-str - The JSON string to parse
    
  Options:
    :key-fn - Function to transform JSON keys (e.g., keyword to convert to keywords)
    
  Returns:
    Clojure data structure
    
  Examples:
    (read-str \"{\\\"user-id\\\":123}\")
    => {\"user-id\" 123}
    
    (read-str \"{\\\"user-id\\\":123}\" :key-fn keyword)
    => {:user-id 123}"
  [json-str & {:keys [key-fn]}]
  (if key-fn
    (json/read-json json-str :key-fn key-fn)
    (json/read-json json-str)))
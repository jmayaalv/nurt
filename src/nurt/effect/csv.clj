(ns nurt.effect.csv
  (:require [clojure.spec.alpha :as s]
            [nurt.effect :as effect]
            [nurt.io.csv :as io-csv]))

(s/def ::data
  (s/coll-of map?))

(s/def ::path string?)

(s/def ::output-stream
  #(or (instance? java.io.OutputStream %) 
       (instance? java.io.Writer %)))

(s/def ::headers boolean?)
(s/def ::separator char?)
(s/def ::quote-char char?)
(s/def ::escape-char char?)

(s/def ::options
  (s/keys :opt-un [::headers ::separator ::quote-char ::escape-char]))

(s/def ::csv-data
  (s/and (s/keys :req-un [::data]
                 :opt-un [::path ::output-stream ::options])
         #(or (:path %) (:output-stream %))))

(defmethod effect/effect-spec :csv [_]
  (s/keys :req-un [::csv-data]
          :req [:effect/type]))

(defn csv
  "Creates a CSV effect for writing data to CSV format.
  
  Accepts data to be written as CSV along with output destination and options.
  Either :path or :output-stream must be provided in the csv-data map.
  
  Args:
    csv-data - Map containing:
      :data - Collection of maps to write as CSV (required)
      :path - Output file path (string) - use this OR :output-stream
      :output-stream - Output stream to write to - use this OR :path
      :options - Optional CSV formatting options
  
  Returns:
    CSV effect map with :effect/type :csv and :csv-data
  
  Examples:
    ;; Write to file
    (csv {:data [{:name \"John\" :age 30} {:name \"Jane\" :age 25}]
          :path \"/tmp/users.csv\"
          :options {:headers true}})
    
    ;; Write to stream
    (csv {:data [{:id 1 :status \"active\"} {:id 2 :status \"inactive\"}]
          :output-stream output-stream
          :options {:separator \\|}})
    
    ;; Simple file output
    (csv {:data user-records
          :path \"export.csv\"})"
  [csv-data]
  {:effect/type :csv
   :csv-data csv-data})

(defn csv!
  "Executes a CSV effect by writing data to the specified destination.
  
  This is the effect handler function that performs the actual CSV writing.
  It's automatically called by the Kane Broker when processing :csv effects.
  
  Args:
    effect - CSV effect map containing :csv-data
    context - Execution context (passed to IO function)
  
  Returns:
    Result of the CSV write operation
  
  Note:
    This function is typically not called directly. It's intended to be
    registered as an effect handler in the broker and called automatically
    during command processing."
  [{:keys [csv-data]} context]
  (io-csv/write! context csv-data))
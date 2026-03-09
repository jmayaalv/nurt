(ns nurt.io.csv
  "CSV IO operations for generating CSV files using charred.

  This namespace provides low-level CSV operations that can be used independently
  or by effect handlers. Data is written to files or streams using charred's
  bulk CSV writing capabilities."
  (:require [charred.api :as charred]
            [clojure.java.io :as io]))

(defn write!
  "Writes data to CSV format using charred.

  Supports writing to files or output streams with configurable options.
  Data should be a collection of maps with consistent keys.

  Args:
    context - Execution context (currently unused but maintained for consistency)
    csv-data - Map containing:
      :data - Collection of maps to write as CSV rows
      :path - Output file path (string) - use this OR :output-stream
      :output-stream - Output stream to write to - use this OR :path
      :options - Optional map of charred CSV options:
        :headers - Boolean, whether to write header row (default true)
        :separator - Character separator (default comma)
        :quote-char - Quote character (default double-quote)
        :escape-char - Escape character (default backslash)

  Returns:
    Result of the write operation (typically nil for successful writes)

  Examples:
    ;; Write to file
    (write! {}
            {:data [{:name \"John\" :age 30} {:name \"Jane\" :age 25}]
             :path \"/tmp/users.csv\"
             :options {:headers true}})

    ;; Write to stream
    (with-open [out (io/output-stream \"/tmp/data.csv\")]
      (write! {}
              {:data [{:id 1 :value \"A\"} {:id 2 :value \"B\"}]
               :output-stream out
               :options {:separator \\|}})))"
  [_context {:keys [data path output-stream options]}]
  (let [csv-options (merge {:headers true} options)]
    (cond
      path
      (charred/write-csv path data csv-options)

      output-stream
      (charred/write-csv output-stream data csv-options)

      :else
      (throw (ex-info "Must provide either :path or :output-stream"
                      {:data-keys (keys {:data data :path path
                                         :output-stream output-stream :options options})})))))

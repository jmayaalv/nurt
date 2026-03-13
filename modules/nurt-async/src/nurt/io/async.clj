(ns nurt.io.async
  "Async job queue I/O — current implementation: msolli/proletarian."
  (:require [clojure.tools.logging :as log]
            [proletarian.job :as job]))

(defn enqueue!
  "Enqueues a background job using :db from context as the datasource/tx.

  Args:
    context - execution context containing :db (datasource or transaction)
    queue   - keyword identifying the job type
    payload - map of job data
    opts    - optional map passed through to proletarian:
      :job-table          - custom job table name
      :archived-job-table - custom archived job table name

  Returns:
    Result of proletarian job enqueue."
  [context queue payload opts]
  (let [db (:db context)]
    (log/debug "Enqueueing async job" queue)
    (if opts
      (job/enqueue! db queue payload opts)
      (job/enqueue! db queue payload))))

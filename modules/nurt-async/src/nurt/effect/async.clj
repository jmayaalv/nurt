(ns nurt.effect.async
  (:require [clojure.spec.alpha :as s]
            [nurt.effect :as effect]
            [nurt.io.async :as io-async]))

(s/def ::queue keyword?)
(s/def ::payload map?)
(s/def ::job-table string?)
(s/def ::archived-job-table string?)
(s/def ::opts (s/keys :opt-un [::job-table ::archived-job-table]))

(defmethod effect/effect-spec :async [_]
  (s/keys :req-un [::queue ::payload]
          :opt-un [::opts]
          :req    [:effect/type]))

(defn enqueue
  "Creates an async job enqueue effect.

  Args:
    queue   - keyword identifying the job type
    payload - map of job data
    opts    - optional map of options:
      :job-table          - custom job table name (string)
      :archived-job-table - custom archived job table name (string)

  Returns:
    Async effect map with :effect/type :async"
  ([queue payload]
   {:effect/type :async
    :queue       queue
    :payload     payload})
  ([queue payload opts]
   {:effect/type :async
    :queue       queue
    :payload     payload
    :opts        opts}))

(defn enqueue!
  "Enqueues an async job. Gets :db from context as datasource/tx."
  [{:keys [queue payload opts]} context]
  (io-async/enqueue! context queue payload opts))

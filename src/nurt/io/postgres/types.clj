(ns nurt.io.postgres.types
  (:require
   [clojure.string :as str]
   [nurt.internal.json :as json]
   [next.jdbc.date-time :as jdbc.date-time]
   [next.jdbc.prepare :as p]
   [next.jdbc.result-set :as rs])
  (:import
   [java.util UUID]
   [java.sql PreparedStatement]
   [org.joda.money CurrencyUnit]
   [org.postgresql.util PGobject]))

;; :decode-key-fn here specifies that JSON-keys will become keywords:

(defn ->pgobject
  "Transforms Clojure data to a PGobject that contains the data as
  JSON. PGObject type defaults to `jsonb` but can be changed via
  metadata key `:pgtype`"
  [x]
  (when x
    (doto (PGobject.)
      (.setType "jsonb")
      (.setValue (json/write-str x)))))

(defn <-pgobject
  "Transform PGobject containing `json` or `jsonb` value to Clojure
  data."
  [^org.postgresql.util.PGobject v]
  (let [type  (.getType v)
        value (.getValue v)]
    (if (#{"jsonb" "json"} type)
      (when value
        (json/read-str value :key-fn keyword))
      value)))

(extend-protocol p/SettableParameter
  clojure.lang.IPersistentMap
  (set-parameter [m ^PreparedStatement s i]
    (.setObject s i (->pgobject m)))

  clojure.lang.IPersistentVector
  (set-parameter [v ^PreparedStatement s i]
    (.setObject s i (->pgobject v)))

  clojure.lang.LazySeq
  (set-parameter [v ^PreparedStatement s i]
    (.setObject s i (->pgobject (vec v))))

  clojure.lang.ArraySeq
  (set-parameter [v ^PreparedStatement s i]
    (.setObject s i (->pgobject (vec v))))

  clojure.lang.PersistentHashSet
  (set-parameter [v ^PreparedStatement s i]
    (.setObject s i (->pgobject v)))

  clojure.lang.PersistentTreeSet
  (set-parameter [v ^PreparedStatement s i]
    (.setObject s i (->pgobject v)))

  clojure.lang.Keyword
  (set-parameter [^clojure.lang.Keyword v ^PreparedStatement s ^long i]
    (.setString s i (str v)))

  CurrencyUnit
  (set-parameter [^CurrencyUnit v ^PreparedStatement s ^long i]
    (.setString s i (.getCode v)))

  UUID
  (set-parameter [^UUID v ^PreparedStatement s ^long i]
    (.setString s i  (str v)))
  )

(extend-protocol rs/ReadableColumn
  String
  (read-column-by-label [^String v _]
    (if (str/starts-with? v ":") (keyword (subs v 1)) v))
  (read-column-by-index [^String v _2 _3] v
    (if (str/starts-with? v ":") (keyword (subs v 1)) v))
  org.postgresql.util.PGobject
  (read-column-by-label [^org.postgresql.util.PGobject v _]
    (<-pgobject v))
  (read-column-by-index [^org.postgresql.util.PGobject v _2 _3]
    (<-pgobject v)))


;;;  read dates as java local date/time
(jdbc.date-time/read-as-local)

(extend-protocol rs/ReadableColumn
  java.sql.Time
  (read-column-by-label [^java.sql.Time v _] (.toLocalTime v))
  (read-column-by-index [^java.sql.Time v _2 _3] (.toLocalTime v)))

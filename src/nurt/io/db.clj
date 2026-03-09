(ns nurt.io.db
  (:require
   [camel-snake-kebab.core :as camel]
   [clojure.tools.logging :as log]
   [clojure.string :as str]
   [exoscale.coax :as coax]
   [nurt.io.postgres.types]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]))

(defonce table->ns+ (atom {}))

(defn clear-tables!
  "Clears all registered table-to-namespace mappings. Useful for testing."
  []
  (reset! table->ns+ {}))

(defn register-tables!
  "Registers table-to-namespace mappings for result set qualification.

  Maps SQL table names (strings) to Clojure namespaces (strings) so that
  result set columns are namespaced keywords (e.g., :users/name).
  Throws if a table is already registered with a different namespace.

  Args:
    table->ns - Map of table-name (string) -> namespace (string)"
  [table->ns]
  (let [conflicts (keep (fn [[table ns-name]]
                          (when-let [existing-ns (get @table->ns+ table)]
                            (when (not= existing-ns ns-name)
                              {:table               table
                               :existing-namespace  existing-ns
                               :attempted-namespace ns-name})))
                        table->ns)]
    (when (seq conflicts)
      (let [{:keys [table existing-namespace attempted-namespace]} (first conflicts)]
        (throw (ex-info (format "The table %s has already been registered with namespace %s"
                                table
                                existing-namespace)
                        {:table                  table
                         :existing-namespace     existing-namespace
                         :attempted-namespace    attempted-namespace
                         :attempted-registration table->ns}))))
    (swap! table->ns+ (fnil into {}) table->ns)))

(defn next-jdbc-opts
  "Builds next.jdbc result-set options map using the given table->ns mappings.

  Configures column qualification (table name -> namespace) and kebab-case
  label conversion for result sets.

  Args:
    table->ns - Map of table-name (string) -> namespace (string)"
  [table->ns]
  (let [lookup (memoize #(get table->ns % %))]
    {:builder-fn (fn [rs opts]
                   (rs/as-modified-maps rs
                                        (assoc opts
                                               :qualifier-fn (fn [x]
                                                               (lookup (str/lower-case x)))
                                               :label-fn camel/->kebab-case)))}))

(defn- execute!
  [db stmt opts]
  (try
    (jdbc/execute! db stmt opts)
    (catch Exception e
      (log/error "Error executing query " stmt)
      (throw e))))

(defn- execute-one!
  [db stmt opts]
  (try
    (jdbc/execute-one! db stmt opts)
    (catch Exception e
      (log/error "Error executing query " stmt)
      (throw e))))

(defn query!
  "Executes a SQL query and returns all results as a vector of maps.

  Uses registered table-to-namespace mappings for column qualification and
  applies coax coercion on results. Logs the query at debug level.

  Args:
    db   - JDBC datasource or connection
    stmt - SQL statement (honeysql map or HugSQL vector)
    opts - Optional map with :row-fn (function applied to each row)"
  ([db stmt]
   (log/debug "executing query:" stmt)
   (mapv #(with-meta
            (coax/coerce-structure %)
            nil)
         (execute! db stmt (next-jdbc-opts @table->ns+))))
  ([db stmt {:keys [row-fn] :or {row-fn identity} :as opts}]
   (log/debug "executing query:" stmt)
   (mapv #(with-meta
            (row-fn %)
            nil)
         (execute! db stmt (next-jdbc-opts @table->ns+)))))

(defn query-one!
  "Executes a SQL query and returns a single result map, or nil if not found.

  Uses registered table-to-namespace mappings for column qualification and
  applies coax coercion on the result. Logs the query at debug level.

  Args:
    db   - JDBC datasource or connection
    stmt - SQL statement (honeysql map or HugSQL vector)
    opts - Optional map with :row-fn (function applied to the row)"
  ([db stmt]
   (log/debug "executing query one: " stmt)
   (some-> (execute-one! db stmt (next-jdbc-opts @table->ns+))
           (coax/coerce-structure)
           (with-meta nil)))
  ([db stmt {:keys [row-fn] :or {row-fn identity} :as opts}]
   (log/debug "executing query one: " stmt)
   (some-> (execute-one! db stmt (merge (next-jdbc-opts @table->ns+)
                                        opts))
           row-fn
           (with-meta nil))))

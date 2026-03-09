(ns nurt.effect.db
  (:require
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [nurt.effect :as effect]
   [next.jdbc :as jdbc]))

(s/def ::sql-statement
  (s/or :vector-statement (s/and vector?
                                 (s/cat :sql string?
                                        :params (s/* any?)))
        :string-statement string?))

(s/def ::statements
  (s/coll-of ::sql-statement :min-count 1))

(defmethod effect/effect-spec :db [_]
  (s/keys :req-un [::statements]
          :req [:effect/type]))


(defn db
  "Creates a database effect for executing SQL statements within a transaction.

  Accepts either a single SQL statement or a collection of statements.
  Statements can be either:
  - String: Raw SQL (e.g., \"SELECT * FROM users\")
  - Vector: SQL with parameters (e.g., [\"SELECT * FROM users WHERE id = ?\" 123])

  All statements will be executed within a single database transaction using next.jdbc.

  Args:
    statement-or-statements - Either a single statement or collection of statements

  Returns:
    Database effect map with :effect/type :db and :statements collection

  Examples:
    ;; Single string statement
    (db \"SELECT * FROM users\")

    ;; Single parameterized statement
    (db [\"SELECT * FROM users WHERE id = ?\" 123])

    ;; Multiple statements
    (db [[\"INSERT INTO users (name) VALUES (?)\" \"John\"]
         [\"UPDATE settings SET last_login = ?\" (java.util.Date.)]])"
  [statement-or-statements]
  (let [statements (if (vector? (first statement-or-statements))
                     statement-or-statements
                     [statement-or-statements])
        effect {:effect/type :db
                :statements statements}]
    effect))

(defn db!
  "Executes a database effect by running SQL statements within a transaction.

  This is the effect handler function that performs the actual database operations.
  It's automatically called by the Kane Broker when processing :db effects.
  All statements are executed within a single transaction - if any statement fails,
  the entire transaction is rolled back.

  Args:
    effect - Database effect map containing :statements
    context - Execution context containing :db (database connection/datasource)

  Returns:
    Result of the transaction execution

  Note:
    This function is typically not called directly. It's registered as an effect
    handler in the broker and called automatically during command processing."
  [{:keys [statements]} {:keys [db]}]
  (jdbc/with-transaction [tx db]
    (run! (fn [statement]
            (log/debug "Executing: " statement)
            (jdbc/execute! tx statement))
          statements)))

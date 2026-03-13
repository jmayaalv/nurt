(ns nurt.bus.util
  "Utilities for command management and broker operations."
  (:require [nurt.bus.macro :as macro]
            [clojure.string :as str]))

(defn apply-commands
  "Register multiple command functions to a broker.

  Parameters:
  - broker: The broker to register commands to
  - command-fns: Collection of command registration functions

  Returns:
  The broker (for chaining)

  Example:
  (apply-commands my-broker [create-user-cmd update-user-cmd delete-user-cmd])"
  [broker command-fns]
  (doseq [command-fn command-fns]
    (command-fn broker))
  broker)

(defn register-namespace-commands
  "Register all defcommand functions from a namespace to a broker.

  Scans the namespace for vars with :defcommand metadata and registers them.

  Parameters:
  - broker: The broker to register commands to
  - ns-symbol: Symbol representing the namespace to scan

  Returns:
  A map with :registered (count) and :commands (list of command names)

  Example:
  (register-namespace-commands my-broker 'app.commands.user)"
  [broker ns-symbol]
  (let [ns-map (ns-map (find-ns ns-symbol))
        command-vars (filter #(-> % val meta :defcommand) ns-map)
        command-fns (map val command-vars)]

    (doseq [command-fn command-fns]
      (command-fn broker))

    {:registered (count command-fns)
     :commands (map #(macro/command-name (val %)) command-vars)}))

(defn auto-register-commands
  "Auto-discover and register commands from multiple namespaces.

  Parameters:
  - broker: The broker to register commands to
  - namespace-patterns: Collection of namespace symbols or patterns to scan

  Returns:
  Map with registration summary

  Example:
  (auto-register-commands my-broker
    ['app.commands.user
     'app.commands.fund
     'app.commands.trade])"
  [broker namespace-patterns]
  (let [results (map #(register-namespace-commands broker %) namespace-patterns)
        total-registered (apply + (map :registered results))
        all-commands (mapcat :commands results)]

    {:total-registered total-registered
     :namespaces (count namespace-patterns)
     :commands all-commands
     :details results}))

(defn list-registered-commands
  "List all commands registered to a broker (if introspection is available).

  Note: This is a best-effort function that works with the current InMemoryBroker
  implementation. May not work with other broker implementations.

  Parameters:
  - broker: The broker to inspect

  Returns:
  Set of registered command keywords"
  [broker]
  (when-let [router (and (contains? broker :router+)
                         @(:router+ broker))]
    (set (keys router))))

(defn command-info
  "Get detailed information about a command registration function.

  Parameters:
  - command-fn: A command registration function created by defcommand

  Returns:
  Map with command details including name, options, and interceptors"
  [command-fn]
  (when-let [metadata (macro/command-metadata command-fn)]
    {:name (:name metadata)
     :options (:options metadata)
     :inject-path (:inject-path metadata)
     :interceptor-count (count (:interceptors metadata))
     :interceptors (:interceptors metadata)
     :has-coeffects (boolean (seq (get-in metadata [:options :coeffects])))
     :has-coercer (boolean (get-in metadata [:options :coercer]))
     :has-id (boolean (get-in metadata [:options :id]))
     :has-transformers (boolean (seq (get-in metadata [:options :transformers])))
     :has-custom-interceptors (boolean (seq (get-in metadata [:options :interceptors])))}))

(defn validate-command-function
  "Validate that a function is a proper defcommand registration function.

  Parameters:
  - command-fn: Function to validate

  Returns:
  True if valid, throws exception with details if invalid"
  [command-fn]
  (when-not (fn? command-fn)
    (throw (ex-info "Command must be a function" {:value command-fn :type (type command-fn)})))

  (when-not (macro/command-metadata command-fn)
    (throw (ex-info "Function is not a defcommand registration function"
                    {:function command-fn :metadata (meta command-fn)})))

  true)

(defn commands->registry
  "Convert a collection of command functions into a registry map.

  Parameters:
  - command-fns: Collection of command registration functions

  Returns:
  Map of command-name -> command-function for easy lookup

  Example:
  (def registry (commands->registry [create-user-cmd update-user-cmd]))
  ((:create-user registry) my-broker)"
  [command-fns]
  (reduce
   (fn [registry command-fn]
     (validate-command-function command-fn)
     (let [command-name (macro/command-name command-fn)]
       (assoc registry command-name command-fn)))
   {}
   command-fns))

(defn broker-health-check
  "Perform basic health check on a broker with registered commands.

  Parameters:
  - broker: The broker to check
  - test-commands: Optional collection of command names to test

  Returns:
  Map with health check results"
  [broker & [test-commands]]
  (let [registered (list-registered-commands broker)
        health-info {:registered-count (count registered)
                     :registered-commands registered
                     :broker-type (type broker)
                     :timestamp (java.time.Instant/now)}]

    (if test-commands
      (let [missing-commands (remove registered test-commands)
            available-commands (filter registered test-commands)]
        (assoc health-info
               :test-commands test-commands
               :missing-commands missing-commands
               :available-commands available-commands
               :all-commands-available? (empty? missing-commands)))
      health-info)))

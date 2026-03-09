(ns nurt.broker
  (:require
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [exoscale.coax :as c]
   [exoscale.lingo :as  lingo]
   [nurt.bus :as bus]
   [nurt.bus.interceptor :as ki]
   [nurt.effect.csv :as effect.csv]
   [nurt.effect.db :as effect.db]
   [nurt.effect.email :as effect.email]
   [nurt.effect.command :as effect.command]
   [nurt.effect.http :as effect.http]
   [nurt.effect.jms :as effect.jms]))


(defmulti command-spec :command/type)
(s/def :broker/command (s/multi-spec command-spec :command/type))

(defn register-command!
  "Registers a command handler with the broker.

  Commands are registered with coeffects using the path [:command :coeffects],
  allowing command handlers to remain pure functions by injecting dependencies
  into the context rather than performing side effects directly.

  Args:
    broker - The Kane Bus broker instance
    command-key - Keyword identifying the command type (e.g., :user/create)
    handler-fn - Function that processes the command and returns effects
    interceptors - Optional interceptors to apply to command processing

  Returns:
    The broker instance

  Example:
    (register-command! broker
                       :user/create
                       user-create-handler
                       logging-interceptor
                       validation-interceptor)"
  [broker command-key handler-fn & interceptors]
  (bus/add-handler! broker
                    command-key
                    [:command :coeffects]
                    (vec interceptors)
                    handler-fn))

(defn create
  "Creates a new Kane Broker instance with pre-configured interceptors.

  The broker is built on Kane Bus and includes the following interceptor chain:
  - Transformer: Coerces commands using Coax based on command type
  - Logging: Logs command execution at debug level
  - Validator: Validates commands against :broker/command spec
  - Effects: Handles effect execution (supports :db, :csv, :http, :email effects)

  The broker dispatches commands based on [:command :command/type] path
  and validates them using clojure.spec with enhanced error messages via Lingo.

  Returns:
    A configured Kane Bus broker instance ready for command registration

  Example:
    (def broker (create))
    (register-command! broker :user/create user-handler)"
  []
  (bus/in-memory-broker [:command :command/type]
                        (ki/transformer {:path         [:command]
                                         :transform-fn (fn [command]
                                                         (c/coerce (:command/type command) command))})
                        (ki/logging {:path    [:command]
                                     :message (fn [command]
                                                (log/debug "Executing command: " (:command/type command))
                                                (log/debug (str "  " command)))})
                        (ki/validator {:path        [:command]
                                       :validate-fn (fn [command]
                                                      (if (s/valid? :broker/command command)
                                                        [:ok command]
                                                        [:error (ex-info "invalid command"
                                                                         (lingo/explain-data :broker/command command ))]))})
                        (ki/effects {:db      #'effect.db/db!
                                     :jms     #'effect.jms/jms!
                                     :csv     #'effect.csv/csv!
                                     :http    #'effect.http/http!
                                     :email   #'effect.email/email!
                                     :command #'effect.command/command!})))

(defn register-effect!
  "Registers an effect processor as a bus-level leave interceptor.

  Effect processors run during the leave phase of the interceptor pipeline,
  allowing them to process effects from handler return values.

  Parameters:
  - broker: InMemoryBroker instance
  - effect-type: Keyword identifying the effect type to handle
  - effect-fn: Function that processes the effect. Receives (effect ctx) as arguments

  The effect processor will be called for effects matching the effect-type
  found in handler responses of the form [:ok result effects].

  Returns:
  The broker instance (for chaining).

  Example:
  (register-effect! broker :send-email
    (fn [effect ctx]
      (send-email (:to effect) (:subject effect) (:body effect))))"
  [broker effect-type effect-fn]
  (bus/register-effect! broker effect-type effect-fn))


(defn execute!
  "Executes a command through the broker's interceptor chain.

  The command is dispatched through Kane Bus using the provided context.
  The broker will apply all registered interceptors (transformation, logging,
  validation, and effects) before executing the command handler.

  Args:
    command - Command map containing :command/type and command-specific data
    context - Execution context map containing :broker and other dependencies

  Returns:
    Result of the command execution after passing through the interceptor chain

  Example:
    (execute! {:command/type :user/create
               :user/name \"John Doe\"
               :user/email \"john@example.com\"}
              {:broker broker
               :db datasource
               :mq mq-connection})"
  [command {:keys [broker] :as context}]
  (bus/dispatch broker (assoc context :command command)))

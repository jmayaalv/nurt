(ns nurt.bus
  (:require [exoscale.interceptor :as i]
            [nurt.bus.interceptor :as interceptor]
            [clojure.tools.logging :as log]))

(defprotocol Broker
  "Core protocol for event bus implementations.

  Brokers are responsible for routing events to registered handlers through
  configurable interceptor pipelines."

  (dispatch [this event]
    "Dispatches an event to its registered handler through the interceptor pipeline.

    Parameters:
    - event: Map containing the event data. Must include the dispatch key path
             configured in the broker to route to the correct handler.

    Returns:
    The result of the handler execution after processing through all interceptors.

    Throws:
    ExceptionInfo if no handler is registered for the event type or if any
    interceptor in the pipeline fails.

    Example:
    (dispatch broker {:command {:command/type :user-created
                                :email \"alice@example.com\"}})")

  (add-handler!
    [this event-type inject-path handler]
    [this event-type inject-path interceptors handler]
    "Registers an event handler with optional interceptor pipeline.

    Parameters:
    - event-type: Keyword identifying the event type to handle
    - inject-path: Vector of keys specifying which parts of the event context
                   to inject into the handler function
    - interceptors: (optional) Vector of interceptors to run before the handler
    - handler: Function that processes the event. Receives selected context data
               based on inject-path.

    Handler Function:
    The handler receives a map containing only the keys specified in inject-path.
    It can return:
    - A plain value (passed through as-is)
    - [:ok result effects] - result with side effects to execute
    - [:error error-data] - error that stops pipeline execution

    Returns:
    The broker instance (for chaining).

    Examples:
    ; Simple handler
    (add-handler! broker :user-created [:command]
      (fn [{:keys [command]}]
        (create-user! command)))

    ; Handler with interceptors and effects
    (add-handler! broker :payment-processed [:command :coeffects]
      [validator transformer logger]
      (fn [ctx]
        [:ok {:payment-id \"pay_123\"}
         [{:type :email :to \"user@example.com\"}]]))")

  (registered? [this event-type]
    "Checks if a handler is registered for the given event type.

    Parameters:
    - event-type: Keyword identifying the event type to check

    Returns:
    Boolean indicating whether a handler is registered for the event type.

    Example:
    (registered? broker :user-created)  ; => true/false")

  (interceptor [this id]
    "Gets an interceptor by it's given id.
    Parameters:
    - id: interceptor id

    Returns:
    Interceptor or nil if not found

    Examnple:
    (interceptor broker :db)"))

(defrecord InMemoryBroker [interceptors+ router+ dispatch-path]
  Broker
  (dispatch [_ event]
    (let [event-type                     (if (vector? dispatch-path)
                                           (get-in event dispatch-path)
                                           (get event dispatch-path))
          {:keys [interceptors handler]} (get @router+ (keyword event-type))]
      (log/trace event)
      (log/debug "Executing event of type:" event-type)
      (if (nil? handler)
         (throw (ex-info "No handler registered for event type"
                         {:path  dispatch-path
                          :event event-type}))
         (let [pipeline (concat @interceptors+ interceptors [handler])
               ctx      (i/execute event pipeline)]
           (:response ctx)))))

  ;; Add handler with no handler-specific interceptors
  (add-handler! [this event-type inject-path handler-fn]
    (add-handler! this event-type inject-path [] handler-fn))

  ;; Add handler with handler-specific interceptors
  (add-handler! [this event-type inject-path interceptors handler-fn]
    (swap! router+
           assoc
           event-type
           {:handler      {:name  event-type
                           :enter (fn [ctx]
                                    (assoc ctx
                                           :response
                                           (if (fn? handler-fn)
                                                  (handler-fn (select-keys ctx inject-path))
                                                  ((deref handler-fn) (select-keys ctx inject-path)))))}
            :interceptors interceptors})
    this)

  ;; Check if handler is registered
  (registered? [_ event-type]
    (contains? @router+ event-type))
  (interceptor [_ id]
    (->> @interceptors+
         (keep (fn [interceptor]
               (when (= (:name interceptor) id)
                 interceptor)))
         first)))

(defn in-memory-broker
  "Creates a new InMemoryBroker with empty interceptor and router registries.

  The InMemoryBroker implementation stores handlers and interceptors in memory
  using atomic references for thread-safe access.

  Parameters:
  - dispatch-path: (optional) Path vector specifying where to find the event type
                   in the event map. Defaults to [:type].
                   Can also be a single keyword for backward compatibility.
  - interceptors: (optional) Bus-level interceptors applied to all events before
                  handler-specific interceptors.

  Dispatch Path Configuration:
  The dispatch-path determines how events are routed to handlers:
  - [:command :command/type] → (get-in event [:command :command/type])
  - [:event :type] → (get-in event [:event :type])
  - :type → (get event :type)

  Bus-level Interceptors:
  Interceptors provided during broker creation run for every event in this order:
  1. Bus-level interceptors (in order provided)
  2. Handler-specific interceptors (in order provided)
  3. Handler function

  Thread Safety:
  The broker uses atomic references internally, making it safe for concurrent
  access across multiple threads.

  Examples:
  ; Default configuration
  (in-memory-broker)

  ; Custom dispatch path
  (in-memory-broker [:event :type])

  ; With bus-level interceptors
  (in-memory-broker [:command :command/type]
    (logging {:level :info})
    (coeffect :request-id #(java.util.UUID/randomUUID)))

  ; Simple dispatch path (backward compatibility)
  (in-memory-broker :type)"
  ([] (in-memory-broker [:type]))
  ([dispatch-key & interceptors]
   (->InMemoryBroker (atom (vec interceptors)) ;; bus-level interceptors
                     (atom {}) ;; router: event-type → {:interceptors [...] :handler interceptor}
                     dispatch-key)))

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
  (let [effect-interceptor (interceptor/register-effect! effect-type effect-fn)]
    (swap! (:interceptors+ broker) conj effect-interceptor)
    broker))

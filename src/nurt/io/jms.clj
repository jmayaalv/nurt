(ns nurt.io.jms
  "JMS IO operations for sending messages to queues.

  This namespace provides low-level JMS operations that can be used independently
  or by effect handlers. Messages are JSON-serialized using nurt.internal.json with
  support for properties and delayed delivery."
  (:require
   [clojure.edn :as edn]
   [nurt.internal.json :as json]
   [kane.mq :as mq])
  (:import
   [org.apache.activemq ScheduledMessage]))

(defn message-format [{:keys [properties]}]
  (get properties :format :json))

(defn send!
  "Sends a JMS message to a queue with JSON serialization.

  Handles message properties and delayed delivery using ActiveMQ's scheduled
  message feature. The message payload is JSON-serialized using nurt.internal.json.

  Args:
    context - Execution context containing :mq (MQ connection)
    message - Message map containing:
      :queue - Destination queue name (string)
      :message - Message payload (map, will be JSON-serialized)
      :properties - Optional message properties (map)
      :delay - Optional delay in milliseconds for scheduled delivery (positive integer)

  Returns:
    Result of the MQ send operation

  Examples:
    ;; Basic message
    (send! {:mq mq-conn}
           {:queue \"user.events\"
            :message {:user-id 123 :action \"created\"}})

    ;; Message with properties and delay
    (send! {:mq mq-conn}
           {:queue \"order.processing\"
            :message {:order-id 456}
            :properties {:priority 5}
            :delay 5000})"
  [context {:keys [queue message properties delay] :as mq-message}]
  (let [mq     (:mq context)
        format (message-format mq-message)]
    (when-not mq
      (throw (ex-info "Missing :mq in context" {:context context})))
    (if (= :json format)
      (mq/send! mq
                queue
                (json/write-str message)
                {:properties (if delay
                               (assoc properties ScheduledMessage/AMQ_SCHEDULED_DELAY delay)
                               properties)})
      (mq/send! mq
                queue
                (str message)
                {:properties (if delay
                               (assoc properties ScheduledMessage/AMQ_SCHEDULED_DELAY delay)
                               properties)}))))

(defn start-consumer!
  "Starts a JMS consumer for a queue with automatic message deserialization.

  Supports JSON and EDN message formats (defaults to EDN if format property is absent).
  Failed messages are routed to a dead-letter queue (queue-name.DLQ).

  Args:
    connection-pool - MQ connection pool
    queue-name      - Queue name to consume from (string)
    consumer-fn     - Function called with (deserialized-message properties) for each message

  Returns:
    Consumer handle (map with :close fn to stop the consumer)"
  [connection-pool queue-name consumer-fn]
  (mq/create-consumer! connection-pool
                       queue-name
                       (fn [message properties]
                         (let [properties (update properties :format #(if %
                                                                        (keyword %)
                                                                        :edn))]
                           (if (= :json (:format properties))
                             (consumer-fn (json/read-str message)
                                          properties)
                             (consumer-fn (edn/read-string {:readers *data-readers*
                                                            :default (fn [tag value] {:tag tag :value value})} message)
                                          properties))))
                       {:dlq (str queue-name ".DLQ")}))

(defn stop-consumers!
  "Stop JMS consumers

   Args:
     consumers - list of consumers"
  [consumers]
  (run! (fn [consumer]
          ((:close consumer)))
        consumers))

(ns nurt.effect.jms
  (:require [clojure.spec.alpha :as s]
            [nurt.effect :as effect]
            [nurt.io.jms :as io-jms]
            [nurt.effect.command :as effect.commmand]))

(s/def ::message map?)
(s/def ::queue string?)
(s/def ::properties map?)
(s/def ::delay pos-int?)
(s/def ::jms-message
  (s/keys :req-un [::message ::queue]
          :opt-un [::properties ::delay]))

(s/def ::messages (s/coll-of ::jms-message :min-count 1))

(defmethod effect/effect-spec :jms [_]
  (s/keys :req-un [::messages]
          :req [:effect/type]))

(defn jms
  "Creates a JMS effect for sending messages to queues.

  Accepts either a single message or a collection of messages to be sent.
  Each message must contain:
  - :message - The message payload (map)
  - :queue - The destination queue name (string)
  - :properties - Optional message properties (map)
  - :delay - Optional delay in milliseconds for scheduled delivery (positive integer)

  Messages are sent using Kane MQ and are JSON-serialized using charred.

  Args:
    message-or-messages - Either a single message map or collection of message maps

  Returns:
    JMS effect map with :effect/type :jms and :messages collection

  Examples:
    ;; Single message
    (jms {:message {:user-id 123 :action \"created\"}
          :queue \"user.events\"})

    ;; Message with properties and delay
    (jms {:message {:order-id 456}
          :queue \"order.processing\"
          :properties {:priority 5}
          :delay 5000})

    ;; Multiple messages
    (jms [{:message {:user-id 123} :queue \"user.events\"}
          {:message {:order-id 456} :queue \"order.events\"}])"
  [message-or-messages]
  (let [messages (if (vector? message-or-messages)
                   message-or-messages
                   [message-or-messages])
        effect   {:effect/type :jms
                  :messages    messages}]
    effect))


(defn jms!
  "Executes a JMS effect by sending messages to their respective queues.

  This is the effect handler function that performs the actual message sending.
  It's automatically called by the Kane Broker when processing :jms effects.
  Messages are sent via Kane MQ with JSON serialization and support for
  properties and delayed delivery.

  Args:
    effect - JMS effect map containing :messages
    context - Execution context containing :mq (MQ connection)

  Returns:
    Result of the message sending operations

  Note:
    This function is typically not called directly. It's intended to be
    registered as an effect handler in the broker and called automatically
    during command processing."
  [{:keys [messages] :as effect} context]
  (if (get-in context [:configuration :broker/async?] true)
    (run! (fn [message]
            (io-jms/send! context message))
          messages)
    (effect.commmand/command! {:effect/type :command
                               :commands (map :message messages)}
                              context)))

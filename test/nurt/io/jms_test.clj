(ns nurt.io.jms-test
  (:require [clojure.test :refer [deftest is testing]]
            [nurt.io.jms :as io-jms]
            [kane.mq :as mq])
  (:import [org.apache.activemq ScheduledMessage]))

(deftest send-function-test
  (testing "send! function with various message configurations"
    (testing "sends basic message"
      (let [sent-messages (atom [])
            context {:mq {:mock-mq true}}
            message {:queue "test.queue"
                     :message {:user-id 123}}]

        (with-redefs [mq/send! (fn [mq queue json-msg opts]
                                      (swap! sent-messages conj
                                             {:mq mq :queue queue
                                              :message json-msg :opts opts}))]
          (io-jms/send! context message)
          (let [sent (first @sent-messages)]
            (is (= (:mq context) (:mq sent)))
            (is (= "test.queue" (:queue sent)))
            (is (= "{\"user-id\":123}" (:message sent)))
            (is (= {:properties nil} (:opts sent)))))))

    (testing "sends message with properties"
      (let [sent-messages (atom [])
            context {:mq {:mock-mq true}}
            message {:queue "test.queue"
                     :message {:user-id 123}
                     :properties {:priority 5}}]

        (with-redefs [mq/send! (fn [mq queue json-msg opts]
                                      (swap! sent-messages conj
                                             {:mq mq :queue queue
                                              :message json-msg :opts opts}))]
          (io-jms/send! context message)
          (let [sent (first @sent-messages)]
            (is (= {:properties {:priority 5}} (:opts sent)))))))

    (testing "sends message with delay"
      (let [sent-messages (atom [])
            context {:mq {:mock-mq true}}
            message {:queue "test.queue"
                     :message {:user-id 123}
                     :delay 5000}]

        (with-redefs [mq/send! (fn [mq queue json-msg opts]
                                      (swap! sent-messages conj
                                             {:mq mq :queue queue
                                              :message json-msg :opts opts}))]
          (io-jms/send! context message)
          (let [sent (first @sent-messages)
                expected-delay-key ScheduledMessage/AMQ_SCHEDULED_DELAY]
            (is (= {:properties {expected-delay-key 5000}} (:opts sent)))))))

    (testing "sends message with both properties and delay"
      (let [sent-messages (atom [])
            context {:mq {:mock-mq true}}
            message {:queue "test.queue"
                     :message {:user-id 123}
                     :properties {:priority 5}
                     :delay 5000}]

        (with-redefs [mq/send! (fn [mq queue json-msg opts]
                                      (swap! sent-messages conj
                                             {:mq mq :queue queue
                                              :message json-msg :opts opts}))]
          (io-jms/send! context message)
          (let [sent (first @sent-messages)
                expected-delay-key ScheduledMessage/AMQ_SCHEDULED_DELAY]
            (is (= {:properties (assoc {:priority 5} expected-delay-key 5000)}
                   (:opts sent)))))))))

(deftest send-error-handling-test
  (testing "send! function error scenarios"
    (testing "handles missing mq in context"
      (let [message {:queue "test.queue" :message {:test true}}]
        (is (thrown? Exception (io-jms/send! {} message)))))

    (testing "calls mq/send! with correct arguments"
      (let [call-args (atom nil)
            context {:mq {:mock-mq true}}
            message {:queue "test.queue" :message {:test true}}]

        (with-redefs [mq/send! (fn [mq queue json-msg opts]
                                      (reset! call-args [mq queue json-msg opts]))]
          (io-jms/send! context message)
          (let [[mq queue json-msg opts] @call-args]
            (is (= {:mock-mq true} mq))
            (is (= "test.queue" queue))
            (is (= "{\"test\":true}" json-msg))
            (is (= {:properties nil} opts))))))))

(deftest send-json-serialization-test
  (testing "JSON serialization of different message types"
    (testing "serializes complex nested structures"
      (let [sent-messages (atom [])
            context {:mq {:mock-mq true}}
            message {:queue "test.queue"
                     :message {:user {:id 123 :name "John"}
                               :orders [{:id 1 :total 99.99}
                                        {:id 2 :total 149.99}]
                               :metadata {:created-at "2023-01-01"
                                          :source "api"}}}]

        (with-redefs [mq/send! (fn [mq queue json-msg opts]
                                      (swap! sent-messages conj json-msg))]
          (io-jms/send! context message)
          (let [json-str (first @sent-messages)]
            (is (string? json-str))
            (is (.contains json-str "\"user\""))
            (is (.contains json-str "\"orders\""))
            (is (.contains json-str "\"metadata\""))))))

    (testing "handles nil and empty values"
      (let [sent-messages (atom [])
            context {:mq {:mock-mq true}}
            message {:queue "test.queue"
                     :message {:value nil
                               :empty-map {}
                               :empty-vector []}}]

        (with-redefs [mq/send! (fn [mq queue json-msg opts]
                                      (swap! sent-messages conj json-msg))]
          (io-jms/send! context message)
          (let [json-str (first @sent-messages)]
            (is (string? json-str))
            (is (.contains json-str "null"))
            (is (.contains json-str "{}"))
            (is (.contains json-str "[]"))))))))

(ns ^:parallel nurt.effect.jms-test
  (:require
   [clojure.spec.alpha :as s]
   [clojure.test :refer [deftest is testing]]
   [nurt.effect.jms :as jms]))

(deftest message-spec-test
  (testing "::message spec validation"
    (testing "accepts valid message maps"
      (is (s/valid? ::jms/message {:user-id 123}))
      (is (s/valid? ::jms/message {:event "user-created" :data {:name "John"}})))

    (testing "rejects non-maps"
      (is (not (s/valid? ::jms/message "string")))
      (is (not (s/valid? ::jms/message 123)))
      (is (not (s/valid? ::jms/message nil))))))

(deftest queue-spec-test
  (testing "::queue spec validation"
    (testing "accepts valid queue names"
      (is (s/valid? ::jms/queue "user.events"))
      (is (s/valid? ::jms/queue "order-processing")))

    (testing "rejects non-strings"
      (is (not (s/valid? ::jms/queue 123)))
      (is (not (s/valid? ::jms/queue nil)))
      (is (not (s/valid? ::jms/queue :keyword))))))

(deftest properties-spec-test
  (testing "::properties spec validation"
    (testing "accepts valid property maps"
      (is (s/valid? ::jms/properties {}))
      (is (s/valid? ::jms/properties {:priority 5}))
      (is (s/valid? ::jms/properties {:custom-header "value"})))

    (testing "rejects non-maps"
      (is (not (s/valid? ::jms/properties "string")))
      (is (not (s/valid? ::jms/properties 123))))))

(deftest delay-spec-test
  (testing "::delay spec validation"
    (testing "accepts positive integers"
      (is (s/valid? ::jms/delay 1000))
      (is (s/valid? ::jms/delay 5000)))

    (testing "rejects zero and negative values"
      (is (not (s/valid? ::jms/delay 0)))
      (is (not (s/valid? ::jms/delay -1000))))

    (testing "rejects non-integers"
      (is (not (s/valid? ::jms/delay "1000")))
      (is (not (s/valid? ::jms/delay 1000.5))))))

(deftest jms-message-spec-test
  (testing "::jms-message spec validation"
    (testing "accepts messages with required fields"
      (let [msg {:message {:user-id 123}
                 :queue "user.events"}]
        (is (s/valid? ::jms/jms-message msg))))

    (testing "accepts messages with optional fields"
      (let [msg {:message {:user-id 123}
                 :queue "user.events"
                 :properties {:priority 5}
                 :delay 1000}]
        (is (s/valid? ::jms/jms-message msg))))

    (testing "rejects messages missing required fields"
      (is (not (s/valid? ::jms/jms-message {:message {:user-id 123}})))
      (is (not (s/valid? ::jms/jms-message {:queue "user.events"}))))

    (testing "validates field types"
      (is (not (s/valid? ::jms/jms-message {:message "not-a-map"
                                            :queue "user.events"})))
      (is (not (s/valid? ::jms/jms-message {:message {:user-id 123}
                                            :queue 123}))))))

(deftest messages-spec-test
  (testing "::messages spec validation"
    (testing "accepts collections of valid messages"
      (let [msgs [{:message {:user-id 123} :queue "user.events"}]]
        (is (s/valid? ::jms/messages msgs))))

    (testing "requires at least one message"
      (is (not (s/valid? ::jms/messages []))))

    (testing "rejects invalid message formats"
      (is (not (s/valid? ::jms/messages [{}])))
      (is (not (s/valid? ::jms/messages ["invalid"]))))))

(deftest jms-function-test
  (testing "jms function creates proper effect maps"
    (testing "handles single message"
      (let [message {:message {:user-id 123} :queue "user.events"}
            result (jms/jms message)]
        (is (= :jms (:effect/type result)))
        (is (= [message] (:messages result)))))

    (testing "handles multiple messages"
      (let [messages [{:message {:user-id 123} :queue "user.events"}
                      {:message {:order-id 456} :queue "order.events"}]
            result (jms/jms messages)]
        (is (= :jms (:effect/type result)))
        (is (= messages (:messages result)))))

    (testing "creates spec-valid effects"
      (let [effect (jms/jms {:message {:test true} :queue "test.queue"})]
        (is (s/valid? :broker/effect effect))))))


(deftest jms-effect-execution-test
  (testing "jms! function execution"
    (testing "executes multiple messages"
      (let [sent-messages (atom [])
            context {:mq {:mock-mq true}}
            effect {:messages [{:message {:user-id 123} :queue "user.events"}
                               {:message {:order-id 456} :queue "order.events"}]}]

        (with-redefs [nurt.io.jms/send! (fn [ctx msg]
                                          (swap! sent-messages conj
                                                 {:queue (:queue msg)
                                                  :message (:message msg)}))]
          (jms/jms! effect context)
          (is (= 2 (count @sent-messages)))
          (is (= "user.events" (:queue (first @sent-messages))))
          (is (= "order.events" (:queue (second @sent-messages))))
          (is (= {:user-id 123} (:message (first @sent-messages))))
          (is (= {:order-id 456} (:message (second @sent-messages)))))))

    (testing "handles single message"
      (let [sent-messages (atom [])
            context {:mq {:mock-mq true}}
            effect {:messages [{:message {:user-id 123} :queue "user.events"}]}]

        (with-redefs [nurt.io.jms/send! (fn [ctx msg]
                                          (swap! sent-messages conj
                                                 {:queue (:queue msg)
                                                  :message (:message msg)}))]
          (jms/jms! effect context)
          (is (= 1 (count @sent-messages)))
          (is (= "user.events" (:queue (first @sent-messages))))
          (is (= {:user-id 123} (:message (first @sent-messages)))))))))

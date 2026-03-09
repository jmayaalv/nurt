(ns nurt.bus-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [nurt.bus :as bus]
   [nurt.bus.interceptor :as ki]))


(deftest ^:parallel broker-basic-tests
  (let [called+ (atom [])
        ;; Bus-level interceptor that records events
        bus-logger {:name ::bus-logger
                    :enter (fn [ctx]
                             (swap! called+ conj :bus)
                             ctx)}
        ;; Handler-level interceptor that records
        handler-logger {:name ::handler-logger
                        :enter (fn [ctx]
                                 (swap! called+ conj :handler-interceptor)
                                 ctx)}
        ;; Handler that records
        handler-fn (fn [ctx]
                     (swap! called+ conj :handler)
                     ctx)
        ;; Create broker with bus interceptor
        broker (bus/in-memory-broker [:command :command/type] bus-logger)]

    ;; Register handler with handler-level interceptor
    (bus/add-handler! broker :foo [:command] [handler-logger] handler-fn)

    ;; Dispatch event, should run bus interceptor, then handler interceptor, then handler
    (bus/dispatch broker {:command {:command/type :foo :payload {:x 1}}})

    (is (= @called+ [:bus :handler-interceptor :handler]))))

(deftest ^:parallel dispatch-no-handler
  (let [broker (bus/in-memory-broker [:command :command/type])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No handler registered"
                          (bus/dispatch broker {:command {:command/type :missing}})))))

(deftest ^:parallel effectful-pipeline-test
  (let [;; Track executed effects for verification
        executed-effects+ (atom [])

        now (java.time.LocalDate/of 2025 8 11)

        ;; Create coeffects - add request ID and timestamp
        request-id-coeffect (ki/coeffect :request/id (constantly "req-1"))
        timestamp-coeffect  (ki/coeffect :timestamp (fn [_] now))

        ;; Create transformer - convert currency to cents
        currency-transformer (ki/transformer {:path         [:command :amount]
                                              :transform-fn #(* % 100)})

        ;; Create effects processor
        effects-processor (ki/effects
                           {:audit-log    (fn [effect ctx]
                                            (swap! executed-effects+ conj
                                                   [:audit-log {:event-type (get-in ctx [:command :command/type])
                                                                :request-id (get-in ctx [:coeffects :request/id])
                                                                :timestamp  (get-in ctx [:coeffects :timestamp])
                                                                :message    (:message effect)}]))
                            :notification (fn [effect ctx]
                                            (swap! executed-effects+ conj
                                                   [:notification {:user-id           (:user-id effect)
                                                                   :amount            (get-in ctx [:command :amount])
                                                                   :notification-type (:notification-type effect)}]))})

        ;; Create broker with interceptors
        broker (bus/in-memory-broker [:command :command/type])]

    ;; Register handler with complete interceptor pipeline
    (bus/add-handler! broker :payment-processed
                      [:command :coeffects]
                      [request-id-coeffect ; Add request ID
                       timestamp-coeffect ; Add timestamp
                       currency-transformer ; Convert amount to cents
                       effects-processor] ; Process effects
                      (fn [{:keys [command coeffects]}]
                        ;; Handler processes payment and returns effects
                        (let [{:keys [amount user-id]} command]
                          (if (and (> amount 0) user-id)
                            ;; Success case - return result with effects
                            [:ok
                             {:payment-id   "pay_123456"
                              :status       "processed"
                              :amount-cents amount
                              :processed-at (:timestamp coeffects)
                              :request-id   (:request/id coeffects)}
                             [{:effect/type :audit-log
                               :message     (str "Payment of $" (/ amount 100) " processed for user " user-id)}
                              {:effect/type       :notification
                               :user-id           user-id
                               :notification-type :notification}]]
                            ;; Error case - return error with no effects
                            [:error (ex-info "error" {:validation-errors ["Invalid payment data"]})]))))
    (testing "successful payment processing"
      (reset! executed-effects+ [])
      (let [result (bus/dispatch broker
                                 {:command {:command/type :payment-processed
                                            :user-id      "user_789"
                                            :amount       25.50 ; Will be converted to 2550 cents
                                            :currency     "USD"}})]

        ;; Verify the handler result is properly unwrapped
        (is (= {:amount-cents 2550.0
                :payment-id   "pay_123456"
                :processed-at now
                :request-id   "req-1"
                :status       "processed"}
               result))

        ;; Verify effects were executed
        (is (= [[:audit-log
                 {:event-type :payment-processed
                  :message    "Payment of $25.5 processed for user user_789",
                  :request-id "req-1",
                  :timestamp  now}]
                [:notification
                 {:amount            2550.0,
                  :notification-type :notification,
                  :user-id           "user_789"}]]
               @executed-effects+))))

    (testing "Error case"
      (reset! executed-effects+ [])
      (try
        (bus/dispatch broker
                      {:command {:command/type :payment-processed
                                 :user-id      nil ; Invalid - no user ID
                                 :amount       -10 ; Invalid - negative amount
                                 :currency     "USD"}})
        (catch Exception e
          (is (= {:date    {:validation-errors ["Invalid payment data"]}
                  :message "error"}
                 {:message (.getMessage e)
                  :date    (ex-data e)}))))

      ;; Verify no effects were executed
      (is (empty? @executed-effects+))))

(deftest ^:parallel broker-dispatch-path-configurations
  (testing "default dispatch path [:type]"
    (let [result (atom nil)
          broker (bus/in-memory-broker)]
      (bus/add-handler! broker :test-event [:command]
        (fn [ctx] (reset! result ctx) ctx))

      (bus/dispatch broker {:type :test-event :command {:data "payload"}})
      (is (= {:command {:data "payload"}} @result))))

  (testing "custom vector dispatch path"
    (let [result (atom nil)
          broker (bus/in-memory-broker [:event :kind])]
      (bus/add-handler! broker :user-action [:event]
        (fn [ctx] (reset! result ctx) ctx))

      (bus/dispatch broker {:event {:kind :user-action :user-id 123}})
      (is (= {:event {:kind :user-action :user-id 123}} @result))))

  (testing "single keyword dispatch path"
    (let [result (atom nil)
          broker (bus/in-memory-broker :action)]
      (bus/add-handler! broker :create [:payload]
        (fn [ctx] (reset! result ctx) ctx))

      (bus/dispatch broker {:action :create :payload {:name "test"}})
      (is (= {:payload {:name "test"}} @result))))

  (testing "deeply nested dispatch path"
    (let [result (atom nil)
          broker (bus/in-memory-broker [:message :envelope :type])]
      (bus/add-handler! broker :notification [:message]
        (fn [ctx] (reset! result ctx) ctx))

      (bus/dispatch broker {:message {:envelope {:type :notification}
                                      :content  "Hello"}})
      (is (= {:message {:envelope {:type :notification}
                        :content  "Hello"}} @result)))))

(deftest ^:parallel broker-edge-cases
  (testing "dispatch with nil event"
    (let [broker (bus/in-memory-broker)]
      (is (thrown-with-msg? Exception #"No handler registered"
            (bus/dispatch broker nil)))))

  (testing "dispatch with empty event"
    (let [broker (bus/in-memory-broker)]
      (is (thrown-with-msg? Exception #"No handler registered"
                            (bus/dispatch broker {})))))

  (testing "dispatch with malformed event structure"
    (let [broker (bus/in-memory-broker [:command :type])]
      (is (thrown-with-msg? Exception #"No handler registered"
                            (bus/dispatch broker {:command "not-a-map"})))))

  (testing "handler replacement"
    (let [results  (atom [])
          broker   (bus/in-memory-broker)
          handler1 (fn [_] (swap! results conj :handler1))
          handler2 (fn [_] (swap! results conj :handler2))]

      ;; Register first handler
      (bus/add-handler! broker :test [:command] handler1)
      (bus/dispatch broker {:type :test :command {}})

      ;; Replace with second handler
      (bus/add-handler! broker :test [:command] handler2)
      (bus/dispatch broker {:type :test :command {}})

      (is (= [:handler1 :handler2] @results))))

  (testing "multiple handlers for different event types"
    (let [results   (atom {})
          broker    (bus/in-memory-broker)
          handler-a (fn [_] (swap! results assoc :a :called))
          handler-b (fn [_] (swap! results assoc :b :called))]

      (bus/add-handler! broker :event-a [:command] handler-a)
      (bus/add-handler! broker :event-b [:command] handler-b)

      (bus/dispatch broker {:type :event-a :command {}})
      (bus/dispatch broker {:type :event-b :command {}})

      (is (= {:a :called :b :called} @results))))

  (testing "inject-path with non-existent keys"
    (let [result (atom nil)
          broker (bus/in-memory-broker)]
      (bus/add-handler! broker :test [:missing-key :also-missing]
        (fn [ctx] (reset! result ctx) ctx))

      (bus/dispatch broker {:type :test :command {:data "exists"}})
      (is (= {} @result))))

  (testing "inject-path with partial key matches"
    (let [result (atom nil)
          broker (bus/in-memory-broker)]
      (bus/add-handler! broker :test [:command :missing-nested]
        (fn [ctx] (reset! result ctx) ctx))

      (bus/dispatch broker {:type :test :command {:data "exists"}})
      (is (= {:command {:data "exists"}} @result)))))

(deftest ^:parallel broker-interceptor-edge-cases
  (testing "empty bus-level interceptors"
    (let [result (atom nil)
          broker (bus/in-memory-broker [:type])]
      (bus/add-handler! broker :test [:command]
        (fn [ctx] (reset! result :handled) ctx))

      (bus/dispatch broker {:type :test :command {}})
      (is (= :handled @result))))

  (testing "interceptor that modifies context structure"
    (let [result           (atom nil)
          context-modifier {:name  ::context-modifier
                            :enter (fn [ctx]
                                     (assoc ctx :modified true :new-data "added"))}
          broker           (bus/in-memory-broker [:type] context-modifier)]

      (bus/add-handler! broker :test [:command :modified :new-data]
        (fn [ctx] (reset! result ctx) ctx))

      (bus/dispatch broker {:type :test :command {:original "data"}})
      (is (= {:command  {:original "data"}
              :modified true
              :new-data "added"} @result))))

  (testing "interceptor execution order with bus and handler interceptors"
    (let [execution-order     (atom [])
          bus-interceptor1    {:name  ::bus1
                               :enter (fn [ctx] (swap! execution-order conj :bus1) ctx)}
          bus-interceptor2    {:name  ::bus2
                               :enter (fn [ctx] (swap! execution-order conj :bus2) ctx)}
          handler-interceptor {:name  ::handler
                               :enter (fn [ctx] (swap! execution-order conj :handler-int) ctx)}
          broker              (bus/in-memory-broker [:type] bus-interceptor1 bus-interceptor2)]

      (bus/add-handler! broker :test [:command] [handler-interceptor]
        (fn [_] (swap! execution-order conj :handler) :done))

      (bus/dispatch broker {:type :test :command {}})
      (is (= [:bus1 :bus2 :handler-int :handler] @execution-order))))))

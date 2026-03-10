(ns nurt.bus.interceptor-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.spec.alpha :as s]
   [nurt.bus :as bus]
   [nurt.bus.interceptor :as i]))

(deftest ^:parallel logging-interceptor-test
  (testing "logging interceptor logs events"
    (let [log-output (atom [])
          custom-logger (i/logging {:level :info
                                    :message (fn [ctx]
                                               (swap! log-output conj (str "Custom: " (:command ctx)))
                                               "logged")})
          broker (bus/in-memory-broker [:command :type] custom-logger)]

      (bus/add-handler! broker :test-event [:command] (fn [ctx] ctx))
      (bus/dispatch broker {:command {:type :test-event}})

      (is (= ["Custom: {:type :test-event}"] @log-output)))))

(deftest ^:parallel coeffect-interceptor-test
  (testing "coeffect interceptor adds values with simple API"
    (let [timestamp-coeffect (i/coeffect :timestamp (fn [ctx] 12345))
          result (atom nil)
          broker (bus/in-memory-broker [:command :type])]

      (bus/add-handler! broker
                        :test-event
                        [:command :coeffects]
                        [timestamp-coeffect]
                        (fn [ctx] (reset! result ctx) ctx))

      (bus/dispatch broker {:command {:type :test-event :payload {:data 1}}})

      (is (= {:coeffects {:timestamp 12345}
              :command {:payload {:data 1}
                        :type :test-event}}
             @result))))

  (testing "coeffect interceptor adds dynamic values based on context"
    (let [dynamic-coeffect (i/coeffect :computed-value (fn [ctx] (* 2 (get-in ctx [:command :payload :input]))))
          result (atom nil)
          broker (bus/in-memory-broker [:command :type])]

      (bus/add-handler! broker
                        :test-event
                        [:command :coeffects]
                        [dynamic-coeffect]
                        (fn [ctx] (reset! result ctx) ctx))

      (bus/dispatch broker {:command {:type :test-event :payload {:input 5}}})

      (is (= {:coeffects {:computed-value 10}
              :command {:payload {:input 5}
                        :type :test-event}}
             @result))))

  (testing "coeffect interceptor can add Date objects"
    (let [now-coeffect (i/coeffect :now (fn [ctx]
                                          (java.util.Date. 1609459200000))) ; Fixed date for testing
          result (atom nil)
          broker (bus/in-memory-broker [:command :type])]

      (bus/add-handler! broker
                        :test-event
                        [:command :coeffects]
                        [now-coeffect]
                        (fn [ctx] (reset! result ctx) ctx))

      (bus/dispatch broker {:command {:type :test-event :payload {:data 1}}})

      (is (= {:coeffects {:now #inst "2021-01-01T00:00:00.000-00:00"}
              :command {:payload {:data 1} :type :test-event}}
             @result)))))

(deftest ^:parallel transformer-interceptor-test
  (testing "transformer interceptor transforms values"
    (let [amount-transformer (i/transformer {:path [:command :payload :amount]
                                             :transform-fn #(* % 100)})
          result (atom nil)
          broker (bus/in-memory-broker [:command :type])]

      (bus/add-handler! broker
                        :payment
                        [:command]
                        [amount-transformer]
                        (fn [ctx] (reset! result ctx) ctx))

      (bus/dispatch broker {:command {:type :payment :payload {:amount 12.50}}})

      (is (= {:command {:payload {:amount 1250.0}
                        :type :payment}}
             @result))))

  (testing "transformer interceptor handles missing paths gracefully"
    (let [name-transformer (i/transformer {:path [:user :name]
                                           :transform-fn clojure.string/upper-case})
          result (atom nil)
          broker (bus/in-memory-broker [:command :type])]

      (bus/add-handler! broker
                        :test-event
                        [:command]
                        [name-transformer]
                        (fn [ctx] (reset! result ctx) ctx))

      ;; Event without the expected path
      (bus/dispatch broker {:command {:type :test-event :payload {:data 1}}})

      ;; Should not throw and should preserve original context
      (is (= {:command {:payload {:data 1}
                        :type :test-event}}
             @result)))))

(deftest ^:parallel effects-interceptor-test
  (testing "effects interceptor executes effects on :ok status"
    (let [executed-effects+   (atom [])
          effect-fns          {:log     (fn [effect _ctx]
                                          (swap! executed-effects+ conj [:log effect]))
                               :db-save (fn [effect _ctx]
                                          (swap! executed-effects+ conj [:db-save effect]))
                               :email   (fn [effect _ctx]
                                          (swap! executed-effects+ conj [:email effect]))}
          effects-interceptor (i/effects effect-fns)
          broker              (bus/in-memory-broker [:command :type] effects-interceptor)]

      ;; Handler returns [:ok result effects]
      (bus/add-handler! broker
                        :test-event
                        [:command :coeffects]
                        (fn [_]
                          [:ok
                           {:success true :user-id 123}
                           [{:effect/type :log :message "User created"}
                            {:effect/type :db-save :data {:user-id 123 :name "Alice"}}
                            {:effect/type :email :to "alice@example.com" :body "Welcome!"}]]))

      (let [result (bus/dispatch broker {:command {:type :test-event :payload {:name "Alice"}}})]
        ;; Check that effects were executed
        (is (= [[:log {:message "User created" :effect/type :log}]
                [:db-save {:data {:name "Alice" :user-id 123} :effect/type :db-save}]
                [:email {:body "Welcome!" :to "alice@example.com" :effect/type :email}]]
               @executed-effects+))
        ;; map effect
        ;; Check that handler result is unwrapped to just the data part
        (is (= {:success true :user-id 123} result)))))

  (testing "effects interceptor passes through :error status"
    (let [executed-effects    (atom [])
          effect-fns          {:log (fn [effect ctx]
                                      (swap! executed-effects conj [:log effect]))}
          effects-interceptor (i/effects effect-fns)
          broker              (bus/in-memory-broker [:command :type])]

      ;; Handler returns [:error errors]
      (bus/add-handler! broker
                        :test-event
                        [:command :coeffects]
                        [effects-interceptor]
                        (fn [_ctx]
                          [:error (ex-info "an error" {:validation-errors ["Name is required"]})]))

      (try
        (bus/dispatch broker {:command {:type :test-event :payload {}}})
        (catch Exception e
          (is (= {:message "an error", :data {:validation-errors ["Name is required"]}}
                 {:message (.getMessage e)
                  :data    (ex-data e)}))))

        ;; Check that no effects were executed
      (is (empty? @executed-effects))))

  (testing "effects interceptor handles non-vector return values"
    (let [executed-effects    (atom [])
          effect-fns          {:log (fn [effect _ctx]
                                      (swap! executed-effects conj [:log effect]))}
          effects-interceptor (i/effects effect-fns)
          broker              (bus/in-memory-broker [:command :type])]

      ;; Handler returns plain value (not vector)
      (bus/add-handler! broker
                        :test-event
                        [:command]
                        [effects-interceptor]
                        (fn [_ctx]
                          {:plain-result true}))

      (try
        (bus/dispatch broker {:command {:type :test-event :payload {}}})
        (catch Exception e
          (is (= {:data    {:response {:plain-result true}}
                  :message "Invalid handler response"}
                 {:message (.getMessage e)
                  :data    (ex-data e)}))))

        ;; Check that no effects were executed
      (is (empty? @executed-effects))))

  (testing "effects interceptor doesnt execute if there are missing effect handlers"
    (let [executed-effects    (atom [])
          effect-fns          {:log (fn [effect ctx]
                                      (swap! executed-effects conj [:log effect]))}
          effects-interceptor (i/effects effect-fns)
          broker              (bus/in-memory-broker [:command :type])]

      ;; Handler returns effects with unknown types
      (bus/add-handler! broker
                        :test-event
                        [:command]
                        [effects-interceptor]
                        (fn [ctx]
                          [:ok
                           {:success true}
                           [{:effect/type :log :message "Known effect"}
                            {:effect/type :unknown :data "This won't execute"}]]))

      (try
        (bus/dispatch broker {:command {:type :test-event :payload {}}})
        (catch Exception e
          (is (= {:unknown {:data "This won't execute", :effect/type :unknown}}
                 (ex-data e))))))))

(deftest ^:parallel validator-interceptor-test
  (let [positive-validator (i/validator {:path        [:command]
                                         :validate-fn (fn [{:keys [amount] :as command}]
                                                        (if (and (number? amount) (pos? amount))
                                                          [:ok command]
                                                          [:error (ex-info "Amount must be positive" {:amount amount})]))})

        broker (bus/add-handler! (bus/in-memory-broker [:command :type])
                                 :payment
                                 [:command]
                                 [positive-validator]
                                 (fn [{:keys [command]}]
                                   (-> (select-keys command [:amount])
                                       (update  :amount inc))))]
    (testing "validator interceptor with successful validation"
      (is (= {:amount 101}
             (bus/dispatch broker {:command {:type :payment :amount 100}}))))

    (testing "validator interceptor with validation failure"
      (try
        (bus/dispatch broker {:command {:type :payment :amount -100}})
        (catch Exception e
          (is (= {:message "Amount must be positive"
                  :data    {:amount -100}}
                 {:message (.getMessage e)
                  :data    (ex-data e)}))))

      (deftest ^:parallel logging-interceptor-edge-cases
        (testing "logging with custom path extraction"
          (let [log-output (atom [])
                path-logger (i/logging {:level :debug
                                        :path (fn [ctx] (get-in ctx [:command :user-id]))
                                        :message (fn [user-id]
                                                   (swap! log-output conj (str "User: " user-id))
                                                   "logged")})
                broker (bus/in-memory-broker [:type] path-logger)]

            (bus/add-handler! broker :user-event [:command] (fn [ctx] ctx))
            (bus/dispatch broker {:type :user-event :command {:user-id "user123"}})

            (is (= ["User: user123"] @log-output))))

        (testing "logging with nil path function"
          (let [log-output (atom [])
                nil-path-logger (i/logging {:level :info
                                            :path nil
                                            :message (fn [ctx]
                                                       (swap! log-output conj "logged")
                                                       "logged")})
                broker (bus/in-memory-broker [:type] nil-path-logger)]

            (bus/add-handler! broker :test [:command] (fn [ctx] ctx))
            (bus/dispatch broker {:type :test :command {}})

            (is (= ["logged"] @log-output)))))

      (deftest ^:parallel coeffect-interceptor-edge-cases
        (testing "coeffect with exception in value function"
          (let [failing-coeffect (i/coeffect :failing (fn [_] (throw (ex-info "Coeffect failed" {}))))
                broker (bus/in-memory-broker [:type])]

            (bus/add-handler! broker :test [:command] [failing-coeffect] (fn [ctx] ctx))

            (is (thrown-with-msg? Exception #"Coeffect failed"
                                  (bus/dispatch broker {:type :test :command {}})))))

        (testing "coeffect with nil return value"
          (let [nil-coeffect (i/coeffect :nil-value (constantly nil))
                result (atom nil)
                broker (bus/in-memory-broker [:type])]

            (bus/add-handler! broker :test [:command :coeffects] [nil-coeffect]
                              (fn [ctx] (reset! result ctx) ctx))

            (bus/dispatch broker {:type :test :command {}})
            (is (= {:coeffects {:nil-value nil}
                    :command {}} @result))))

        (testing "coeffect with complex data structures"
          (let [complex-coeffect (i/coeffect :complex-data
                                             (fn [_] {:nested {:map "value"}
                                                      :vector [1 2 3]
                                                      :set #{:a :b :c}}))
                result (atom nil)
                broker (bus/in-memory-broker [:type])]

            (bus/add-handler! broker :test [:coeffects] [complex-coeffect]
                              (fn [ctx] (reset! result ctx) ctx))

            (bus/dispatch broker {:type :test})
            (is (= {:coeffects {:complex-data {:nested {:map "value"}
                                               :vector [1 2 3]
                                               :set #{:a :b :c}}}} @result))))

        (testing "multiple coeffects with same key overwrites"
          (let [coeffect1 (i/coeffect :same-key (constantly "first"))
                coeffect2 (i/coeffect :same-key (constantly "second"))
                result (atom nil)
                broker (bus/in-memory-broker [:type])]

            (bus/add-handler! broker :test [:coeffects] [coeffect1 coeffect2]
                              (fn [ctx] (reset! result ctx) ctx))

            (bus/dispatch broker {:type :test})
            (is (= {:coeffects {:same-key "second"}} @result)))))

      (deftest ^:parallel transformer-interceptor-edge-cases
        (testing "transformer with nil transform function result"
          (let [nil-transformer (i/transformer {:path [:command :value]
                                                :transform-fn (constantly nil)})
                result (atom nil)
                broker (bus/in-memory-broker [:type])]

            (bus/add-handler! broker :test [:command] [nil-transformer]
                              (fn [ctx] (reset! result ctx) ctx))

            (bus/dispatch broker {:type :test :command {:value "original"}})
            (is (= {:command {:value nil}} @result))))

        (testing "transformer with deeply nested path"
          (let [deep-transformer (i/transformer {:path [:command :level1 :level2 :level3 :value]
                                                 :transform-fn clojure.string/upper-case})
                result (atom nil)
                broker (bus/in-memory-broker [:type])]

            (bus/add-handler! broker :test [:command] [deep-transformer]
                              (fn [ctx] (reset! result ctx) ctx))

            (bus/dispatch broker {:type :test
                                  :command {:level1 {:level2 {:level3 {:value "hello"}}}}})
            (is (= {:command {:level1 {:level2 {:level3 {:value "HELLO"}}}}} @result))))

        (testing "transformer with path that partially exists"
          (let [partial-transformer (i/transformer {:path [:command :nested :missing]
                                                    :transform-fn str})
                result (atom nil)
                broker (bus/in-memory-broker [:type])]

            (bus/add-handler! broker :test [:command] [partial-transformer]
                              (fn [ctx] (reset! result ctx) ctx))

            (bus/dispatch broker {:type :test :command {:nested {}}})
            (is (= {:command {:nested {}}} @result))))

        (testing "transformer with exception in transform function"
          (let [failing-transformer (i/transformer {:path [:command :value]
                                                    :transform-fn (fn [_] (throw (Exception. "Transform failed")))})
                broker (bus/in-memory-broker [:type])]

            (bus/add-handler! broker :test [:command] [failing-transformer] (fn [ctx] ctx))

            (is (thrown-with-msg? Exception #"Transform failed"
                                  (bus/dispatch broker {:type :test :command {:value "test"}}))))))

      (deftest ^:parallel validator-interceptor-edge-cases
        (testing "validator with nil input value"
          (let [nil-validator (i/validator {:path [:command :value]
                                            :validate-fn (fn [value]
                                                           (if (nil? value)
                                                             [:error (ex-info "Value cannot be nil" {})]
                                                             [:ok value]))})
                broker (bus/in-memory-broker [:type])]

            (bus/add-handler! broker :test [:command] [nil-validator] (fn [ctx] ctx))

            (is (thrown-with-msg? Exception #"Value cannot be nil"
                                  (bus/dispatch broker {:type :test :command {:value nil}})))))

        (testing "validator that transforms on success"
          (let [transforming-validator (i/validator {:path [:command :email]
                                                     :validate-fn (fn [email]
                                                                    (if (and (string? email) (re-find #"@" email))
                                                                      [:ok (clojure.string/lower-case email)]
                                                                      [:error (ex-info "Invalid email" {:email email})]))})
                result (atom nil)
                broker (bus/in-memory-broker [:type])]

            (bus/add-handler! broker :test [:command] [transforming-validator]
                              (fn [ctx] (reset! result ctx) ctx))

            (bus/dispatch broker {:type :test :command {:email "USER@EXAMPLE.COM"}})
            (is (= {:command {:email "user@example.com"}} @result))))

        (testing "validator with complex validation logic"
          (let [complex-validator (i/validator {:path [:command]
                                                :validate-fn (fn [command]
                                                               (let [{:keys [age name email]} command
                                                                     errors (cond-> []
                                                                              (not (string? name)) (conj "Name must be string")
                                                                              (< (count (str name)) 2) (conj "Name too short")
                                                                              (not (number? age)) (conj "Age must be number")
                                                                              (< age 18) (conj "Must be 18+")
                                                                              (not (re-find #"@" (str email))) (conj "Invalid email"))]
                                                                 (if (empty? errors)
                                                                   [:ok command]
                                                                   [:error (ex-info "Validation failed" {:errors errors})])))})
                broker (bus/in-memory-broker [:type])]

            (bus/add-handler! broker :test [:command] [complex-validator] (fn [ctx] ctx))

      ;; Test valid case
            (is (some? (bus/dispatch broker {:type :test
                                             :command {:name "Alice" :age 25 :email "alice@example.com"}})))

      ;; Test invalid case
            (is (thrown-with-msg? Exception #"Validation failed"
                                  (bus/dispatch broker {:type :test
                                                        :command {:name "A" :age 16 :email "invalid"}})))

      ;; Check specific error details
            (try
              (bus/dispatch broker {:type :test :command {:name "A" :age 16 :email "invalid"}})
              (catch Exception e
                (let [errors (get-in (ex-data e) [:data :errors])]
                  (is (contains? (set errors) "Name too short"))
                  (is (contains? (set errors) "Must be 18+"))
                  (is (contains? (set errors) "Invalid email")))))))

        (testing "validator with malformed return value"
          (let [malformed-validator (i/validator {:path [:command :value]
                                                  :validate-fn (constantly "not-a-vector")})
                broker (bus/in-memory-broker [:type])]

            (bus/add-handler! broker :test [:command] [malformed-validator] (fn [ctx] ctx))

      ;; This should probably throw since validator doesn't return [:ok/:error vector]
            (is (thrown? Exception
                         (bus/dispatch broker {:type :test :command {:value "test"}}))))))

      (deftest ^:parallel effects-interceptor-edge-cases
        (testing "effects with empty effect list"
          (let [executed-effects (atom [])
                effect-fns {:log (fn [effect _] (swap! executed-effects conj effect))}
                effects-interceptor (i/effects effect-fns)
                broker (bus/in-memory-broker [:type])]

            (bus/add-handler! broker :test [:command] [effects-interceptor]
                              (fn [_] [:ok {:result "success"} []]))

            (let [result (bus/dispatch broker {:type :test :command {}})]
              (is (= {:result "success"} result))
              (is (empty? @executed-effects)))))

        (testing "effects registry is isolated per interceptor instance"
          ;; Two separate effects interceptors must NOT share registered effects
          (let [executed-a (atom [])
                executed-b (atom [])
                effects-a  (i/effects {:log-a (fn [_ _] (swap! executed-a conj :log-a))})
                effects-b  (i/effects {:log-b (fn [_ _] (swap! executed-b conj :log-b))})
                broker-a   (bus/in-memory-broker [:type] effects-a)
                broker-b   (bus/in-memory-broker [:type] effects-b)]

            ;; broker-a handler returns a log-a effect
            (bus/add-handler! broker-a :test [:command] []
                              (fn [_] [:ok {} [{:effect/type :log-a}]]))
            ;; broker-b handler returns a log-b effect — log-a is unknown to it
            (bus/add-handler! broker-b :test [:command] []
                              (fn [_] [:ok {} [{:effect/type :log-b}]]))

            (bus/dispatch broker-a {:type :test :command {}})
            (bus/dispatch broker-b {:type :test :command {}})

            (is (= [:log-a] @executed-a) "broker-a should only run its own effects")
            (is (= [:log-b] @executed-b) "broker-b should only run its own effects")

            ;; broker-b must not know about log-a
            (is (thrown? Exception
                         (do
                           (bus/add-handler! broker-b :test-unknown [:command] []
                                             (fn [_] [:ok {} [{:effect/type :log-a}]]))
                           (bus/dispatch broker-b {:type :test-unknown :command {}}))))))

        (testing "effects with exception in effect function"
          (let [failing-effects (i/effects {:fail (fn [_ _] (throw (Exception. "Effect failed")))
                                            :log (fn [effect _] effect)})
                broker (bus/in-memory-broker [:type])]

            (bus/add-handler! broker :test [:command] [failing-effects]
                              (fn [_] [:ok {} [{:effect/type :fail} {:effect/type :log}]]))

      ;; Effect failure should not prevent handler completion
      ;; but the specific effect will fail
            (is (thrown-with-msg? Exception #"Effect failed"
                                  (bus/dispatch broker {:type :test :command {}})))))

        (testing "effects interceptor in :leave phase only"
          (let [execution-order (atom [])
                tracking-effects (i/effects {:track (fn [_ _] (swap! execution-order conj :effect-executed))})
                enter-interceptor {:name ::enter-tracker
                                   :enter (fn [ctx] (swap! execution-order conj :enter) ctx)
                                   :leave (fn [ctx] (swap! execution-order conj :leave) ctx)}
                broker (bus/in-memory-broker [:type])]

            (bus/add-handler! broker :test [:command] [enter-interceptor tracking-effects]
                              (fn [_] (swap! execution-order conj :handler) [:ok {} [{:effect/type :track}]]))

            (bus/dispatch broker {:type :test :command {}})
      ;; Effects should run in :leave phase after handler
            (is (= [:enter :handler :effect-executed :leave] @execution-order))))))))

;;; =============================================================================
;;; Helper Function Tests (Using Existing Interceptors)
;;; =============================================================================

;; Define test specs
(s/def ::user-name (s/and string? #(>= (count %) 2)))
(s/def ::user-email (s/and string? #(re-matches #".+@.+\..+" %)))
(s/def ::user-age (s/and int? #(<= 13 % 120)))
(s/def ::user-spec (s/keys :req-un [::user-name ::user-email ::user-age]))

(s/def ::payment-amount (s/and number? pos?))
(s/def ::payment-spec (s/keys :req-un [::payment-amount]))

(deftest ^:parallel spec-validation-helper-test
  (testing "spec-validation helper creates validator interceptor"
    (let [broker (bus/in-memory-broker [:command :type])]
      (bus/add-handler! broker :user-creation [:command]
                        [(i/spec-validation ::user-spec)]
                        (fn [{:keys [command]}] {:validated true :user command}))

      ;; Successful validation
      (let [result (bus/dispatch broker
                                 {:command {:type :user-creation
                                            :user-name "Alice"
                                            :user-email "alice@example.com"
                                            :user-age 25}})]
        (is (= true (:validated result)))
        (is (= "Alice" (get-in result [:user :user-name]))))

      ;; Failed validation
      (is (thrown-with-msg? Exception #"Spec validation failed"
                            (bus/dispatch broker
                                          {:command {:type :user-creation
                                                     :user-name "A"  ; Too short
                                                     :user-email "invalid-email"  ; Invalid format
                                                     :user-age 5}})))))  ; Too young

  (testing "spec-validation with custom path"
    (let [broker (bus/in-memory-broker [:command :type])]
      (bus/add-handler! broker :payment [:command]
                        [(i/spec-validation ::payment-spec {:path [:command :payment-data]})]
                        (fn [{:keys [command]}] {:processed true}))

      (let [result (bus/dispatch broker
                                 {:command {:type :payment
                                            :payment-data {:payment-amount 100.50}}})]
        (is (= true (:processed result))))

      (is (thrown-with-msg? Exception #"Spec validation failed"
                            (bus/dispatch broker
                                          {:command {:type :payment
                                                     :payment-data {:payment-amount -50}}}))))))

(deftest ^:parallel field-coercions-helper-test
  (testing "field-coercions helper creates transformer interceptors"
    (let [coercions (i/field-coercions {:email clojure.string/lower-case
                                        :name clojure.string/trim})
          broker (bus/in-memory-broker [:command :type])]

      ;; Verify it returns a vector of interceptors
      (is (vector? coercions))
      (is (= 2 (count coercions)))

      (bus/add-handler! broker :user-signup [:command] coercions
                        (fn [{:keys [command]}] {:user command}))

      (let [result (bus/dispatch broker
                                 {:command {:type :user-signup
                                            :email "ALICE@EXAMPLE.COM"
                                            :name "  Bob Smith  "}})]
        (is (= "alice@example.com" (get-in result [:user :email])))
        (is (= "Bob Smith" (get-in result [:user :name]))))))

  (testing "field-coercions with nested paths"
    (let [coercions (i/field-coercions {[:user :email] clojure.string/lower-case
                                        [:payment :amount] #(bigdec %)})
          broker (bus/in-memory-broker [:command :type])]

      (bus/add-handler! broker :order [:command] coercions
                        (fn [{:keys [command]}] {:order command}))

      (let [result (bus/dispatch broker
                                 {:command {:type :order
                                            :user {:email "USER@EXAMPLE.COM"}
                                            :payment {:amount "199.99"}}})]
        (is (= "user@example.com" (get-in result [:order :user :email])))
        (is (= 199.99M (get-in result [:order :payment :amount]))))))

  (testing "field-coercions with custom base path"
    (let [coercions (i/field-coercions {:amount #(bigdec %)} {:base-path [:command :data]})
          broker (bus/in-memory-broker [:command :type])]

      (bus/add-handler! broker :process [:command] coercions
                        (fn [{:keys [command]}] {:result command}))

      (let [result (bus/dispatch broker
                                 {:command {:type :process
                                            :data {:amount "50.25"}}})]
        (is (= 50.25M (get-in result [:result :data :amount])))))))

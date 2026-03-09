(ns ^:parallel nurt.broker-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.spec.alpha :as s]
   [nurt.broker :as broker]
   [nurt.bus.macro :refer [defcommand]]))

;; Test specs
(s/def ::name string?)
(s/def ::amount pos?)
(s/def ::test-command-spec (s/keys :req-un [::name ::amount]))

(s/def ::trade-code string?)
(s/def ::fund-code string?)
(s/def ::register-trade-spec (s/keys :req-un [::trade-code ::fund-code]))

(deftest ^:parallel defcommand-generates-command-spec-method-test
  (testing "defcommand automatically generates nurt.broker/command-spec defmethod"
    ;; Define a command with :id - this should generate the defmethod
    (defcommand test-broker-command
      [{:keys [command coeffects]}]
      {:id ::test-command-spec}
      [:ok {:result "success"} []])
    
    ;; Test that the defmethod was created
    (is (= ::test-command-spec (broker/command-spec {:command/type ::test-command-spec}))
        "defmethod should be generated for the spec type")
    
    ;; Test that the multispec validation works
    (is (s/valid? :broker/command {:command/type ::test-command-spec
                                   :name "Test"
                                   :amount 100})
        "Valid command should pass spec validation")
    
    ;; Test invalid case
    (is (not (s/valid? :broker/command {:command/type ::test-command-spec
                                        :name "Test"
                                        :amount -50}))
        "Invalid command should fail spec validation")))

(deftest ^:parallel defcommand-trade-example-integration-test
  (testing "defcommand generates multimethod for trade-like example"
    ;; Define command similar to the trade registration example
    (defcommand register-trade-broker-test
      [{:keys [command coeffects]}]
      {:coeffects [{:now (fn [_] (System/currentTimeMillis))}
                   {:fund (fn [{:keys [command]}] 
                           (str "fund-" (:fund-code command)))}]
       :id ::register-trade-spec}
      (let [{:keys [now fund]} coeffects]
        [:ok {:trade-id (str "trade-" (:trade-code command))
              :fund-id fund  
              :registered-at now} []]))
    
    ;; Verify the defmethod was generated
    (is (= ::register-trade-spec (broker/command-spec {:command/type ::register-trade-spec}))
        "defmethod should be generated for trade command")
    
    ;; Test broker validation with the generated spec
    (is (s/valid? :broker/command {:command/type ::register-trade-spec
                                   :trade-code "T123"
                                   :fund-code "F456"})
        "Valid trade command should pass validation")
    
    ;; Test invalid case (missing required field)
    (is (not (s/valid? :broker/command {:command/type ::register-trade-spec
                                        :trade-code "T123"}))
        "Trade command missing fund-code should fail validation")))

(deftest ^:parallel defcommand-without-id-no-method-test
  (testing "defcommand without :id does not generate defmethod"
    ;; Define command without :id
    (defcommand no-id-command
      [{:keys [command]}]
      {}
      [:ok {:processed true} []])
    
    ;; This should throw since no defmethod was generated
    (is (thrown? Exception (broker/command-spec {:command/type :no-id-command}))
        "Commands without :id should not have defmethod generated")))

(deftest ^:parallel broker-integration-test
  (testing "Full integration with nurt.broker"
    ;; Define a command that will be used with the broker
    (defcommand integration-test-command
      [{:keys [command coeffects]}] 
      {:id ::test-command-spec
       :coeffects [{:timestamp (fn [_] (System/currentTimeMillis))}]}
      (let [{:keys [timestamp]} coeffects]
        [:ok {:name (:name command)
              :amount (:amount command) 
              :processed-at timestamp} []]))
    
    ;; Create broker and register command
    (let [broker (broker/create)]
      ;; Register using the defcommand function
      (integration-test-command broker)
      
      ;; Execute command through broker
      (let [result (broker/execute! {:command/type ::test-command-spec
                                     :name "Integration Test"
                                     :amount 250}
                                    {:broker broker})]
        ;; The broker returns the processed result directly, not the [:ok result effects] structure
        (is (= "Integration Test" (:name result)))
        (is (= 250 (:amount result)))
        (is (number? (:processed-at result)))))))
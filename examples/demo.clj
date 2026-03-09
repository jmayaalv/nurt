(ns nurt.bus.demo
  "Demonstration of complete interceptor pipeline with coeffects, transformer, validator, and effects"
  (:require [nurt.bus :as bus]
            [nurt.bus.interceptor :as i]))

(def executed-effects (atom []))

(defn demo-complete-pipeline []
  (println "=== Kane Bus Complete Pipeline Demo ===\n")

  ;; Reset effects tracking
  (reset! executed-effects [])

  ;; Create coeffects - add request ID and timestamp
  (def request-id-coeffect
    (i/coeffect :request/id (fn [_] (java.util.UUID/randomUUID))))

  (def timestamp-coeffect
    (i/coeffect :timestamp (fn [_] (System/currentTimeMillis))))

  (println "✓ Created 2 coeffects: request/id and timestamp")

  ;; Create transformer - convert currency to cents
  (def currency-transformer
    (i/transformer {:path [:command :amount]
                    :transform-fn #(* % 100)}))

  (println "✓ Created transformer: amount → cents")

  ;; Create validator - validate payment data
  (def payment-validator
    (i/validator {:path [:command]
                  :validate-fn (fn [payment-data]
                                 (cond
                                   (nil? (:user-id payment-data))
                                   [:error (ex-info "User ID required" {:field :user-id})]

                                   (not (number? (:amount payment-data)))
                                   [:error (ex-info "Amount must be a number" {:field :amount :value (:amount payment-data)})]

                                   (<= (:amount payment-data) 0)
                                   [:error (ex-info "Amount must be positive" {:field :amount :value (:amount payment-data)})]

                                   :else [:ok payment-data]))}))

  (println "✓ Created validator: payment data validation")

  ;; Create effects processor with 2 effects
  (def effects-processor
    (i/effects
     {:audit-log (fn [effect ctx]
                   (swap! executed-effects conj
                          [:audit-log {:event-type (get-in ctx [:command :command/type])
                                       :request-id (get-in ctx [:coeffects :request/id])
                                       :timestamp (get-in ctx [:coeffects :timestamp])
                                       :message (:message effect)}]))
      :notification (fn [effect ctx]
                      (swap! executed-effects conj
                             [:notification {:user-id (:user-id effect)
                                             :amount (get-in ctx [:command :amount])
                                             :notification-type (:type effect)}]))}))

  (println "✓ Created effects processor: audit-log and notification\n")

  ;; Create broker and register handler
  (def broker (bus/in-memory-broker [:command :command/type]))

  (bus/add-handler! broker :payment-processed
                    [:command :coeffects]
                    [request-id-coeffect ; Coeffect 1: Add request ID
                     timestamp-coeffect ; Coeffect 2: Add timestamp 
                     currency-transformer ; Transform: Convert to cents
                     payment-validator ; Validate: Check payment data
                     effects-processor] ; Process effects on success
                    (fn [ctx]
                      (println "Handler received context with keys:" (sort (keys ctx)))
                      (let [amount (get-in ctx [:command :amount])
                            user-id (get-in ctx [:command :user-id])]
                        (println (str "Processing payment: $" (/ amount 100) " for user " user-id))

                        ;; Since validation passed, we can proceed with confidence
                        [:ok
                         {:payment-id "pay_123456"
                          :status "processed"
                          :amount-cents amount
                          :processed-at (get-in ctx [:coeffects :timestamp])
                          :request-id (get-in ctx [:coeffects :request/id])}
                         [{:type :audit-log
                           :message (str "Payment of $" (/ amount 100) " processed for user " user-id)}
                          {:type :notification
                           :user-id user-id
                           :notification-type "payment-success"}]])))

  (println "✓ Registered handler with complete interceptor pipeline\n")

  ;; Demonstrate successful payment
  (println "--- Testing Successful Payment ---")
  (let [result (bus/dispatch broker
                             {:command {:command/type :payment-processed
                                        :user-id "user_789"
                                        :amount 25.50 ; Will be converted to 2550 cents
                                        :currency "USD"}})]

    (println "\nHandler result:")
    (clojure.pprint/pprint result)

    (println "\nExecuted effects:")
    (doseq [[effect-type effect-data] @executed-effects]
      (println (str "  " effect-type ":"))
      (clojure.pprint/pprint (str "    " effect-data))))

  (println "\n--- Testing Error Case ---")
  (reset! executed-effects [])

  (try
    (bus/dispatch broker
                  {:command {:command/type :payment-processed
                             :user-id nil ; Invalid - will fail validation
                             :amount -10 ; Invalid - will fail validation  
                             :currency "USD"}})
    (catch Exception e
      (println "\nValidation failed as expected:")
      (println "Error message:" (.getMessage e))
      (println "Error data:" (ex-data e))))

  (println "\nExecuted effects (should be empty since validation failed):")
  (println @executed-effects)

  (println "\n=== Demo Complete ==="))

;; To run this demo:
;; 1. Start a REPL: clj -M:dev
;; 2. Load this file: (load-file "examples/demo.clj")
;; 3. Run the demo: (nurt.bus.demo/demo-complete-pipeline)
;;
;; This demo shows:
;; - 2 coeffects adding request ID and timestamp
;; - 1 transformer converting amount to cents
;; - 1 validator ensuring payment data is valid (short-circuits on failure)
;; - 2 effects executing audit log and notification on success
;; - Error handling when validation fails (pipeline stops, no effects execute)

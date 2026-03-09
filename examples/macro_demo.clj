(ns nurt.bus.macro-demo
  "Comprehensive demonstration of Nurt defcommand macro system.

  This demo showcases all features of the macro system including:
  - Basic command registration
  - Coeffects for computed values
  - Field coercion and transformation
  - Spec validation
  - Custom interceptors
  - Effects processing
  - Error handling"
  (:require [nurt.bus :as bus]
            [nurt.bus.macro :refer [defcommand]]
            [nurt.bus.interceptor :as i]
            [nurt.bus.util :as util]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]))

;;; =============================================================================
;;; Specs for Validation
;;; =============================================================================

(s/def ::user-id string?)
(s/def ::name (s/and string? #(seq (str/trim %))))
(s/def ::email (s/and string? #(re-matches #".+@.+\..+" %)))
(s/def ::age (s/and int? #(<= 13 % 120)))
(s/def ::amount (s/and number? pos?))

(s/def ::user-creation (s/keys :req-un [::name ::email ::age]))
(s/def ::payment-request (s/keys :req-un [::user-id ::amount]))

;;; =============================================================================
;;; Effect Functions
;;; =============================================================================

(defn log-effect [effect ctx]
  (println "[LOG]" (:message effect)))

(defn email-effect [effect ctx]
  (println "[EMAIL] To:" (:to effect) "Subject:" (:subject effect)))

(defn audit-effect [effect ctx]
  (println "[AUDIT]" (:action effect) "by" (:user effect)))

(defn db-save-effect [effect ctx]
  (println "[DB] Saving:" (:entity effect) "ID:" (:id effect)))

;;; =============================================================================
;;; Basic Command Example
;;; =============================================================================

(defcommand hello
  "A simple greeting command that returns a personalized hello message."
  [{:keys [command]}]
  {}
  {:message (str "Hello, " (:name command) "!")
   :timestamp (java.time.Instant/now)})

;;; =============================================================================
;;; Command with Coeffects
;;; =============================================================================

(defcommand create-user
  "Creates a new user account with email validation, name trimming, and comprehensive audit trail.

  Generates a unique user ID, processes the user data through validation and transformation
  interceptors, and triggers welcome email and audit logging effects."
  [{:keys [command coeffects]}]
  {:coeffects [(i/coeffect :request-id (fn [_] (str "req_" (random-uuid))))
               (i/coeffect :timestamp (fn [_] (java.time.Instant/now)))]
   :id ::user-creation
   :transformers [(i/transformer {:path [:command :name] :transform-fn str/trim})
                  (i/transformer {:path [:command :email] :transform-fn str/lower-case})]}
  (let [{:keys [name email age]} command
        {:keys [request-id timestamp]} coeffects
        user-id (str "user_" (random-uuid))]
    [:ok
     {:user-id user-id
      :name name
      :email email
      :age age
      :created-at timestamp
      :request-id request-id}
     [{:type :db-save :entity "user" :id user-id}
      {:type :email :to email :subject "Welcome!"}
      {:type :audit :action "user-created" :user user-id}]]))

;;; =============================================================================
;;; Command with Complex Coercion
;;; =============================================================================

(defcommand process-payment
  "Processes a payment transaction with automatic amount coercion to BigDecimal for precision.

  Converts string amounts to BigDecimal to ensure accurate financial calculations,
  generates a unique payment ID, and creates audit trail with payment confirmation."
  [{:keys [command coeffects]}]
  {:coeffects [(i/coeffect :request-id (fn [_] (str "pay_" (random-uuid))))]
   :id ::payment-request
   :transformers [(i/transformer {:path [:command :amount] :transform-fn #(bigdec %)})]}  ; Convert to BigDecimal for precision
  (let [{:keys [user-id amount]} command
        {:keys [request-id]} coeffects]
    [:ok
     {:payment-id request-id
      :user-id user-id
      :amount amount
      :status "processed"
      :processed-at (java.time.Instant/now)}
     [{:type :log :message (str "Payment of $" amount " processed for " user-id)}
      {:type :audit :action "payment-processed" :user user-id}]]))

;;; =============================================================================
;;; Command with Custom Interceptors
;;; =============================================================================

(def rate-limit-interceptor
  {:name :rate-limiter
   :enter (fn [ctx]
            (let [user-id (get-in ctx [:command :user-id])]
              (if (= user-id "blocked-user")
                (throw (ex-info "Rate limit exceeded" {:user-id user-id}))
                ctx)))})

(defcommand admin-action
  "Executes administrative actions with rate limiting and comprehensive audit logging.

  Applies rate limiting to prevent abuse, normalizes action names to uppercase,
  and creates detailed audit trails for all administrative operations."
  [{:keys [command coeffects]}]
  {:coeffects [(i/coeffect :admin-user (fn [ctx] (get-in ctx [:command :admin-id])))]
   :interceptors [rate-limit-interceptor
                  (i/logging {:level :warn})]
   :transformers [(i/transformer {:path [:command :action] :transform-fn str/upper-case})]}
  (let [{:keys [action target]} command
        {:keys [admin-user]} coeffects]
    [:ok
     {:action action
      :target target
      :executed-by admin-user
      :executed-at (java.time.Instant/now)}
     [{:type :audit :action action :user admin-user}]]))

;;; =============================================================================
;;; Error Handling Examples
;;; =============================================================================

(defcommand validation-demo
  "Demonstrates spec validation using the user-creation spec."
  [{:keys [command]}]
  {:id ::user-creation}
  [:ok {:status "validated"} []])

;; Example without docstring (backward compatibility)
(defcommand backward-compatibility-demo
  [{:keys [command]}]
  {}
  [:ok {:message "This command has the default generated docstring"} []])

;;; =============================================================================
;;; Demo Functions
;;; =============================================================================

(defn create-demo-broker []
  "Create a broker with all necessary effects configured."
  (bus/in-memory-broker
   [:command :type]
   [(i/effects {:log log-effect
                :email email-effect
                :audit audit-effect
                :db-save db-save-effect})]))

(defn register-all-commands [broker]
  "Register all demo commands to the broker."
  (util/apply-commands broker
                       [hello
                        create-user
                        process-payment
                        admin-action
                        validation-demo
                        backward-compatibility-demo])
  broker)

(defn demo-basic-usage []
  "Demonstrate basic command usage."
  (println "\n=== Basic Command Demo ===")
  (let [broker (-> (create-demo-broker)
                   (register-all-commands))]

    ;; Simple hello command
    (println "Result:"
             (bus/dispatch broker
                           {:command {:type :hello :name "Alice"}}))))

(defn demo-coeffects-and-coercion []
  "Demonstrate coeffects and field coercion."
  (println "\n=== Coeffects & Coercion Demo ===")
  (let [broker (-> (create-demo-broker)
                   (register-all-commands))]

    ;; User creation with coercion (trimming and lowercasing)
    (println "Creating user with field coercion:")
    (let [result (bus/dispatch broker
                               {:command {:type :create-user
                                          :name "  John Doe  "  ; Will be trimmed
                                          :email "JOHN@EXAMPLE.COM"  ; Will be lowercased
                                          :age 30}})]
      (println "Result:" result))))

(defn demo-payment-processing []
  "Demonstrate payment processing with BigDecimal coercion."
  (println "\n=== Payment Processing Demo ===")
  (let [broker (-> (create-demo-broker)
                   (register-all-commands))]

    (println "Processing payment:")
    (let [result (bus/dispatch broker
                               {:command {:type :process-payment
                                          :user-id "user_123"
                                          :amount "99.99"}})]  ; String will be coerced to BigDecimal
      (println "Result:" result))))

(defn demo-custom-interceptors []
  "Demonstrate custom interceptors and rate limiting."
  (println "\n=== Custom Interceptors Demo ===")
  (let [broker (-> (create-demo-broker)
                   (register-all-commands))]

    ;; Successful admin action
    (println "Successful admin action:")
    (let [result (bus/dispatch broker
                               {:command {:type :admin-action
                                          :admin-id "admin_1"
                                          :action "delete-user"
                                          :target "user_456"}})]
      (println "Result:" result))

    ;; Rate limited user (will throw exception)
    (println "\nTrying rate-limited user:")
    (try
      (bus/dispatch broker
                    {:command {:type :admin-action
                               :admin-id "blocked-user"
                               :action "some-action"
                               :target "something"}})
      (catch Exception e
        (println "Caught exception:" (.getMessage e))))))

(defn demo-validation-errors []
  "Demonstrate spec validation errors."
  (println "\n=== Validation Demo ===")
  (let [broker (-> (create-demo-broker)
                   (register-all-commands))]

    ;; Valid data
    (println "Valid user data:")
    (let [result (bus/dispatch broker
                               {:command {:type :validation-demo
                                          :name "Jane Smith"
                                          :email "jane@example.com"
                                          :age 25}})]
      (println "Result:" result))

    ;; Invalid data (will throw validation error)
    (println "\nInvalid user data:")
    (try
      (bus/dispatch broker
                    {:command {:type :validation-demo
                               :name ""  ; Invalid: empty name
                               :email "not-an-email"  ; Invalid: bad email format
                               :age 5}})  ; Invalid: too young
      (catch Exception e
        (println "Validation error:" (.getMessage e))
        (println "Error data:" (ex-data e))))))

(defn demo-command-introspection []
  "Demonstrate command metadata introspection."
  (println "\n=== Command Introspection Demo ===")

  ;; Show command metadata
  (println "User creation command info:")
  (println "- Name:" (nurt.bus.macro/command-name #'create-user))
  (println "- Options:" (nurt.bus.macro/command-options create-user))
  (println "- Interceptor count:" (count (nurt.bus.macro/command-interceptors create-user)))

  ;; Using util functions
  (println "\nCommand analysis using utils:")
  (println "- Has coeffects:" (boolean (:coeffects (nurt.bus.macro/command-options create-user))))
  (println "- Has id:" (boolean (:id (nurt.bus.macro/command-options create-user))))
  (println "- Has transformers:" (boolean (:transformers (nurt.bus.macro/command-options create-user)))))

(defn demo-complete-workflow []
  "Run all demos in sequence."
  (println "🚀 Nurt Macro System Demo")
  (println "============================")

  (demo-basic-usage)
  (demo-coeffects-and-coercion)
  (demo-payment-processing)
  (demo-custom-interceptors)
  (demo-validation-errors)
  (demo-command-introspection)

  (println "\n✅ Demo completed successfully!")
  (println "\nTo run individual demos:")
  (println "  (nurt.bus.macro-demo/demo-basic-usage)")
  (println "  (nurt.bus.macro-demo/demo-coeffects-and-coercion)")
  (println "  (nurt.bus.macro-demo/demo-payment-processing)")
  (println "  (nurt.bus.macro-demo/demo-custom-interceptors)")
  (println "  (nurt.bus.macro-demo/demo-validation-errors)")
  (println "  (nurt.bus.macro-demo/demo-command-introspection)"))

;;; =============================================================================
;;; Development Quick Reference
;;; =============================================================================

(comment
  ;; Quick start guide:

  ;; 1. Load the demo
  (load-file "examples/macro-demo.clj")

  ;; 2. Run complete demo
  (nurt.bus.macro-demo/demo-complete-workflow)

  ;; 3. Create your own broker
  (def my-broker (nurt.bus.macro-demo/create-demo-broker))

  ;; 4. Register commands
  (nurt.bus.macro-demo/register-all-commands my-broker)

  ;; 5. Dispatch events
  (bus/dispatch my-broker {:command {:type :hello :name "World"}})

  ;; 6. Define your own commands with docstrings
  (defcommand my-custom-command
    "My custom command that demonstrates docstring usage."
    [{:keys [command coeffects]}]
    {:coeffects [(i/coeffect :timestamp (fn [_] (java.time.Instant/now)))]
     :id ::your-spec
     :transformers [(i/transformer {:path [:command :field-name] :transform-fn transform-fn})]}
    [:ok {:result "success"} [{:type :log :message "Command executed"}]])

  ;; Without docstring (backward compatible)
  (defcommand my-legacy-command
    [{:keys [command]}]
    {}
    [:ok {:result "legacy"} []])

  ;; 7. Register and use
  (my-custom-command my-broker)
  (bus/dispatch my-broker {:command {:type :my-custom-command :data "test"}}))

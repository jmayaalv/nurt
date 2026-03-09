# Nurt

A lightweight, composable event bus library for Clojure built on top of the [exoscale interceptor framework](https://github.com/exoscale/interceptor). Nurt provides a simple yet powerful foundation for event-driven architectures with built-in support for interceptors, effects, and flexible event routing.

## Features

- 🚌 **Protocol-based broker interface** for extensible event dispatch systems
- 🔄 **Hierarchical interceptor execution** (bus-level → handler-level → handler)
- 🎯 **Path-based event routing** with configurable dispatch keys
- ⚡ **Built-in interceptor library** (coeffects, transformers, validators, effects, logging)
- 🧪 **Effects system** for clean separation of business logic and side effects
- ✅ **Functional validation** with [:ok value] / [:error ex-info] patterns
- 🎭 **Declarative macro system** (`defcommand`) for composable command registration
- 🔧 **REPL-friendly** development workflow with namespace auto-registration
- 📦 **Zero external dependencies** (except exoscale/interceptor)
- 🔍 **clj-kondo integration** with LSP support for the macro system

## Quick Start

Add nurt to your `deps.edn`:

```clojure
{:deps {io.github.jmayaalv/nurt {:mvn/version "LATEST_VERSION"}}}
```

### Using defcommand (Recommended)

The `defcommand` macro provides clean, declarative command registration with automatic interceptor composition:

```clojure
(require '[nurt.bus :as bus]
         '[nurt.bus.macro :refer [defcommand]]
         '[clojure.spec.alpha :as s])

;; Define specs
(s/def ::email (s/and string? #(re-matches #".+@.+\..+" %)))
(s/def ::name string?)
(s/def ::user-creation (s/keys :req-un [::email ::name]))

(def broker (bus/in-memory-broker [:command :command/type]))

;; Define a command with transformers, validation, and effects
(defcommand create-user
  [{:keys [command coeffects]}]
  {:transformers [{:path [:command :email]
                   :transform-fn clojure.string/lower-case}]
   :id ::user-creation  ; automatic spec validation
   :coeffects [{:request-id (fn [_] (str "req-" (random-uuid)))}
               {:timestamp (fn [_] (System/currentTimeMillis))}]}
  (let [{:keys [email name]} command
        {:keys [request-id timestamp]} coeffects]
    [:ok {:user-id (random-uuid)
          :email email 
          :name name
          :created-at timestamp}
     [{:type :send-welcome-email :email email}
      {:type :audit-log :message "User created" :request-id request-id}]]))

;; Register and dispatch
(create-user broker)
(bus/dispatch broker {:command {:command/type :create-user
                               :email "ALICE@EXAMPLE.COM"  ; will be lowercased
                               :name "Alice Smith"}})

;; Direct testing (bypasses interceptors)
(create-user nil {:command {:email "alice@example.com" :name "Alice Smith"}
                  :coeffects {:request-id "req-456" :timestamp 1635724800000}})
```

### Why defcommand?

**🎯 Declarative**: Specify what you want, not how to wire it  
**📦 Automatic Composition**: Handles interceptor ordering automatically  
**✅ Built-in Validation**: Automatic spec validation with clear errors  
**🔄 Clean Transformations**: Data coercion without ceremony  
**🧪 Easy Testing**: Direct invocation for unit tests  
**📖 Self-Documenting**: All requirements visible at a glance

### Manual Interceptor Setup (Lower-level)

```clojure
;; Create interceptors
(def request-tracker (i/coeffect :request/id #(java.util.UUID/randomUUID)))
(def logger (i/logging {:level :info}))
(def email-normalizer (i/transformer {:path [:command :email]
                                      :transform-fn clojure.string/lower-case}))
(def email-validator (i/validator {:path [:command :email]
                                   :validate-fn #(if (re-matches #".+@.+" %)
                                                   [:ok %]
                                                   [:error (ex-info "Invalid email" {:value %})])}))

;; Register handler with interceptor pipeline
(bus/add-handler! broker :user-signup
  [request-tracker logger email-normalizer email-validator]
  (fn [ctx]
    (let [user-data (:command ctx)]
      {:status "success" :user-id (create-user! user-data)})))
```

### Effects System

Handle side effects cleanly by returning `[:ok result effects]` from handlers:

```clojure
;; Define effect processors
(def effect-processor
  (i/effects {:db-save (fn [effect ctx] (save-to-db! (:data effect)))
              :email   (fn [effect ctx] (send-email! (:to effect) (:body effect)))
              :audit   (fn [effect ctx) (log-event! (:message effect)))}))

;; Handler returns effects
(bus/add-handler! broker :payment-processed
  [effect-processor]
  (fn [ctx]
    (let [payment (:command ctx)]
      [:ok
       {:payment-id "pay_123" :status "processed"}
       [{:type :db-save :data payment}
        {:type :email :to (:email payment) :body "Payment confirmed!"}
        {:type :audit :message (str "Payment " (:amount payment) " processed")}]])))

;; Effects execute automatically on success
(bus/dispatch broker
  {:command {:command/type :payment-processed
             :amount 100.00
             :email "customer@example.com"}})
```

## Architecture

### Core Components

**Broker Protocol**
- `dispatch` - Routes events to registered handlers
- `add-handler!` - Registers handlers with optional interceptors

**InMemoryBroker**
- Default implementation with in-memory routing
- Configurable dispatch paths (e.g., `[:command :command/type]`)
- Supports bus-level and handler-specific interceptors

**Interceptor Library**
- **Coeffects** - Add values to event context
- **Transformers** - Modify event data
- **Validators** - Validate data and short-circuit on failure
- **Effects** - Execute side effects based on handler results
- **Logging** - Configurable event logging

### Event Flow

```
Event → Bus Interceptors → Handler Interceptors → Handler → Effects
```

1. **Event Dispatch**: Event sent to broker
2. **Bus Interceptors**: Applied to all events (e.g., logging, auth)
3. **Handler Interceptors**: Applied to specific event types (e.g., validation)
4. **Handler Execution**: Business logic processes the event
5. **Effects Processing**: Side effects executed if handler returns `[:ok result effects]`

## Built-in Interceptors

### Coeffects
Add computed values to the event context under `:coeffects`:

```clojure
;; Raw interceptor creation (for manual setup)
(i/coeffect :timestamp #(System/currentTimeMillis))
(i/coeffect :request/id #(java.util.UUID/randomUUID))
(i/coeffect :user-roles #(get-user-roles (get-in % [:command :user-id])))

;; Modern defcommand syntax (recommended)
:coeffects [{:timestamp (fn [_] (System/currentTimeMillis))}
            {:request-id (fn [_] (java.util.UUID/randomUUID))}
            {:user-roles (fn [ctx] (get-user-roles (get-in ctx [:command :user-id])))}]

;; Access in handler:
(defn my-handler [ctx]
  (let [timestamp (get-in ctx [:coeffects :timestamp])
        request-id (get-in ctx [:coeffects :request/id])
        user-roles (get-in ctx [:coeffects :user-roles])]
    ;; ... handler logic
    ))
```

### Transformers
Modify event data at specific paths:

```clojure
;; Raw interceptor creation (for manual setup)
(i/transformer {:path [:command :amount] :transform-fn #(* % 100)})
(i/transformer {:path [:command :email] :transform-fn clojure.string/lower-case})
(i/transformer {:path [:command :created-at] :transform-fn #(java.time.Instant/parse %)})

;; Modern defcommand syntax (recommended)
:transformers [{:path [:command :amount] :transform-fn #(* % 100)}
               {:path [:command :email] :transform-fn clojure.string/lower-case}
               {:path [:command :created-at] :transform-fn #(java.time.Instant/parse %)}]
```

### Validators
Validate data and short-circuit the pipeline on failure:

```clojure
;; Email validation
(i/validator {:path [:command :email]
              :validate-fn #(if (and (string? %) (re-matches #".+@.+" %))
                              [:ok (clojure.string/lower-case %)]  ; can transform on success
                              [:error (ex-info "Invalid email format" {:value %})])})

;; Age validation
(i/validator {:path [:command :age]
              :validate-fn #(if (and (number? %) (>= % 18))
                              [:ok %]
                              [:error (ex-info "Must be 18 or older" {:age %})])})

;; Multiple field validation
(i/validator {:path [:command]
              :validate-fn (fn [data]
                            (cond
                              (nil? (:name data)) [:error (ex-info "Name required" {})]
                              (< (count (:name data)) 2) [:error (ex-info "Name too short" {})]
                              :else [:ok data]))})
```

### Effects
Process side effects based on handler return values:

```clojure
(i/effects
  {:email      (fn [effect ctx] (send-email (:to effect) (:body effect)))
   :sms        (fn [effect ctx] (send-sms (:phone effect) (:message effect)))
   :webhook    (fn [effect ctx] (post-webhook (:url effect) (:payload effect)))
   :audit-log  (fn [effect ctx] (audit-log (:event effect) (:user-id ctx)))})
```

### Logging
Configurable event logging:

```clojure
;; Simple logging
(i/logging {:level :info})

;; Custom message format
(i/logging {:level :debug
            :message #(str "Processing: " (:command/type (:command %)))})
```

## Configuration

### Custom Dispatch Paths

Configure how events are routed by specifying the path to the event type:

```clojure
;; Default: [:command :command/type]
(bus/in-memory-broker)

;; Custom path
(bus/in-memory-broker [:event :type])
(bus/in-memory-broker [:message :kind])

;; Simple key (for backward compatibility)
(bus/in-memory-broker :type)
```

### Bus-level Interceptors

Apply interceptors to all events:

```clojure
(def broker
  (bus/in-memory-broker [:command :command/type]
    (i/logging {:level :info})
    (i/coeffect :request/id #(java.util.UUID/randomUUID))))
```

## Declarative Command System

Nurt includes a powerful `defcommand` macro for declarative command registration with automatic interceptor composition.

### Basic Command

```clojure
(require '[nurt.bus.macro :refer [defcommand]]
         '[nurt.bus.interceptor :as i])

;; Define and register a command with new simplified syntax
(defcommand create-user
  [{:keys [command coeffects]}]
  {:transformers [{:path [:command :email]
                   :transform-fn clojure.string/lower-case}]
   :spec ::user-creation-spec
   :coeffects [{:request-id (fn [_] (str "req-" (random-uuid)))}]
   :interceptors [audit-interceptor]}
  [:ok {:user-id (random-uuid) :email (:email command)} 
   [{:effect/type :email :email (:email command)}]])

;; Register to broker
(create-user broker)

;; Dispatch
(bus/dispatch broker {:command {:command/type :create-user
                               :email "ALICE@EXAMPLE.COM"
                               :name "Alice Smith"}})
```

### Interceptor Execution Order

The macro automatically composes interceptors in the optimal order:
```
transformers → spec validation → coeffects → custom interceptors → handler
```

### Multiple Commands Registration

```clojure
;; Method 1: Apply multiple commands
(require '[nurt.bus.util :as util])
(util/apply-commands broker [create-user update-user])

;; Method 2: Register entire namespace
(util/register-namespace-commands broker 'myapp.commands.user)

;; Method 3: Chained registration
(-> broker
    create-user
    update-user
    delete-user)
```

### Command Options

- **:transformers** - Vector of maps `[{:path [...] :transform-fn fn}]` (recommended) OR legacy interceptor vector
- **:spec** - Spec keyword for validation  
- **:coeffects** - Vector of maps `[{:name fn}]` (recommended) OR legacy map/vector formats
- **:interceptors** - Vector of custom interceptors
- **:inject-path** - Path keys to inject into handler (defaults to `[:command :coeffects]`)

#### New Simplified Syntax (Recommended)

The recommended syntax uses simple maps instead of explicit interceptor calls:

```clojure
;; New format - cleaner and more readable
:transformers [{:path [:command :email] :transform-fn clojure.string/lower-case}
               {:path [:command :amount] :transform-fn #(* % 100)}]

:coeffects [{:request-id (fn [_] (str "req-" (random-uuid)))}
            {:timestamp (fn [_] (System/currentTimeMillis))}]
```

#### Legacy Syntax (Still Supported)

```clojure
;; Legacy format - still works for backward compatibility
:transformers [(i/transformer {:path [:command :email] 
                               :transform-fn clojure.string/lower-case})]

:coeffects [(i/coeffect :request-id (fn [_] (str "req-" (random-uuid))))]
;; OR map format
:coeffects {:request-id (fn [_] (str "req-" (random-uuid)))
            :timestamp (fn [_] (System/currentTimeMillis))}
```

### Advanced Usage

```clojure
;; Full pipeline example with new syntax
(defcommand process-order
  [{:keys [command coeffects]}]
  {:transformers [{:path [:command :customer-email]
                   :transform-fn clojure.string/lower-case}
                  {:path [:command :items]
                   :transform-fn (fn [items]
                                   (mapv #(update % :price bigdec) items))}]
   :spec ::order-spec
   :coeffects [{:order-id (fn [_] (str "ORD-" (random-uuid)))}
               {:timestamp (fn [_] (java.time.Instant/now))}]
   :interceptors [(i/validator {:path [:command :discount-code]
                                :validate-fn validate-discount-code})
                  inventory-check-interceptor]}
  (let [{:keys [customer-email items]} command
        {:keys [order-id timestamp]} coeffects
        total (calculate-total items)]
    [:ok {:order-id order-id
          :customer-email customer-email
          :total total
          :created-at timestamp}
     [{:effect/type :send-confirmation-email :email customer-email}
      {:effect/type :update-inventory :items items}
      {:effect/type :process-payment :amount total}]]))
```

### Command Introspection

```clojure
(require '[nurt.bus.macro :as macro])

;; Get command metadata
(macro/command-name create-user)     ;; => :create-user
(macro/command-options create-user)  ;; => {:spec ::user-creation ...}
(macro/command-interceptors create-user) ;; => [interceptor1 interceptor2 ...]

;; Utility functions
(util/command-info create-user)
;; => {:name :create-user
;;     :has-coeffects true
;;     :has-spec true
;;     :interceptor-count 4}
```

## Examples

Check out the [examples directory](examples/) for complete demonstrations:

- [`demo.clj`](examples/demo.clj) - Complete pipeline with coeffects, transformers, and effects
- [`macro-demo.clj`](examples/macro-demo.clj) - Declarative command system showcase

To run the demos:

```bash
clj -M:dev
```

```clojure
;; Basic interceptor demo
(load-file "examples/demo.clj")
(nurt.bus.demo/demo-complete-pipeline)

;; Macro system demo  
(load-file "examples/macro-demo.clj")
(nurt.bus.macro-demo/demo-complete-workflow)
```

## Development

### REPL Workflow

```bash
# Start REPL with development dependencies
clj -M:dev

# Load and experiment
(require '[nurt.bus :as bus] '[nurt.bus.interceptor :as i] :reload)
(def b (bus/in-memory-broker))
```

### Running Tests

**CRITICAL**: Always run tests after making any code changes.

```bash
# Run all tests (RECOMMENDED)
clojure -X:test

# Alternative with Babashka (includes clean)
bb test

# Run specific namespace  
clj -M:test -e "(require 'nurt.bus-test) (clojure.test/run-tests 'nurt.bus-test)"

# Expected output: "All tests passed" with 0 failures, 0 errors
# Note: "no effect fn registered" warning is expected and harmless
```

**Test Suite Status**: ✅ All 26 tests passing with 72 assertions

### Project Structure

```
src/
├── nurt/
    ├── bus.clj              # Core broker protocol and implementation
    └── bus/
        ├── interceptor.clj  # Built-in interceptor implementations
        ├── macro.clj        # Declarative command macro system
        └── util.clj         # Namespace registration utilities
test/
├── nurt/
    ├── bus_test.clj         # Core functionality tests
    └── bus/
        ├── interceptor_test.clj # Interceptor tests
        ├── macro_test.clj       # Macro system tests
        └── effects_test.clj     # Effects system tests
examples/
├── demo.clj                 # Interactive demonstrations
└── macro-demo.clj          # Declarative command system demo
.clj-kondo/
└── hooks/nurt/bus/          # clj-kondo LSP integration
```

## Advanced Usage

### Custom Interceptors

Create your own interceptors following the [exoscale interceptor](https://github.com/exoscale/interceptor) conventions:

```clojure
(defn auth-interceptor [required-role]
  {:name ::auth
   :enter (fn [ctx]
            (if (authorized? ctx required-role)
              ctx
              (throw (ex-info "Unauthorized" {:required required-role}))))})

(defn rate-limit-interceptor [limit-fn]
  {:name ::rate-limit
   :enter (fn [ctx]
            (if (limit-fn ctx)
              ctx
              (throw (ex-info "Rate limit exceeded" {}))))})
```

### Custom Broker Implementation

Implement the `Broker` protocol for different storage backends:

```clojure
(defrecord DatabaseBroker [db-spec routing-table]
  bus/Broker
  (dispatch [this event]
    ;; Custom dispatch logic
    )
  (add-handler! [this event-type handler]
    ;; Custom handler registration
    ))
```

### Error Handling

Interceptors can handle errors in the `:error` phase:

```clojure
{:name ::error-handler
 :error (fn [ctx error]
          (log/error "Processing failed:" error)
          (assoc ctx :error-handled true))}
```

## API Reference

See [PROJECT_SUMMARY.md](PROJECT_SUMMARY.md) for complete API documentation and implementation details.

## Dependencies

- **Clojure** 1.12.1+
- **exoscale/interceptor** 0.1.17

## Contributing

1. Fork the repository
2. Create a feature branch
3. Add tests for new functionality
4. Ensure all tests pass
5. Submit a pull request

## Changelog

### v1.0.2 (Latest)
- **New simplified macro syntax**: Added support for cleaner transformer and coeffects syntax
  - Transformers: `[{:path [...] :transform-fn fn}]` instead of explicit interceptor calls
  - Coeffects: `[{:name fn}]` format for easier readability
  - Full backward compatibility with legacy syntax maintained
- **Enhanced test coverage**: Added 4 new tests covering new syntax and backward compatibility
- **Updated documentation**: All examples now showcase the new recommended syntax

### v1.0.1
- **Fixed all failing tests**: Resolved 13 test failures and 1 error
- **Enhanced error handling**: Improved coercion error reporting in transformer interceptors
- **Fixed metadata extraction**: Command metadata now properly accessible from both vars and functions
- **Improved test reliability**: Updated test expectations for consistent handler return formats
- **Enhanced documentation**: Added comprehensive test validation guidelines to CLAUDE.md

### v1.0.0
- Initial release
- Core broker protocol and in-memory implementation
- Built-in interceptor library (coeffects, transformers, validators, effects, logging)
- Path-based event routing
- Effects system for side effect management
- Functional validation with [:ok value] / [:error ex-info] patterns
- Declarative `defcommand` macro system with automatic interceptor composition
- Namespace auto-registration utilities
- clj-kondo integration with LSP support
- Comprehensive test suite and documentation

# Nurt

A lightweight, composable event bus library for Clojure built on top of the [exoscale interceptor framework](https://github.com/exoscale/interceptor). Nurt provides a simple yet powerful foundation for event-driven architectures with built-in support for interceptors, effects, and flexible event routing.

## Features

- **Protocol-based broker interface** for extensible event dispatch
- **Hierarchical interceptor execution** (bus-level → handler-level → handler)
- **Path-based event routing** with configurable dispatch keys
- **Built-in interceptor library** (coeffects, transformers, validators, effects, logging)
- **Effects system** for clean separation of business logic and side effects
- **Functional validation** with `[:ok value]` / `[:error ex-info]` patterns
- **Declarative macro system** (`defcommand`) for composable command registration
- **Built-in integrations**: database (next.jdbc), HTTP (hato), email, CSV (charred), JMS
- **clj-kondo integration** with LSP support for the macro system

## Installation

Add to your `deps.edn`:

```clojure
{:deps {io.github.jmayaalv/nurt {:git/tag "v0.1.0" :git/sha "40501f3a937dd0c6a87e298e48180abc5a703421"}}}
```

## Quick Start

### Using `defcommand` (Recommended)

```clojure
(require '[nurt.bus :as bus]
         '[nurt.bus.macro :refer [defcommand]]
         '[clojure.spec.alpha :as s])

(s/def ::email (s/and string? #(re-matches #".+@.+\..+" %)))
(s/def ::name string?)
(s/def ::user-creation (s/keys :req-un [::email ::name]))

(def broker (bus/in-memory-broker [:command :command/type]))

(defcommand create-user
  [{:keys [command coeffects]}]
  {:transformers [{:path [:command :email]
                   :transform-fn clojure.string/lower-case}]
   :id ::user-creation
   :coeffects [{:request-id (fn [_] (str "req-" (random-uuid)))}
               {:timestamp  (fn [_] (System/currentTimeMillis))}]}
  (let [{:keys [email name]} command
        {:keys [request-id timestamp]} coeffects]
    [:ok {:user-id    (random-uuid)
          :email      email
          :name       name
          :created-at timestamp}
     [{:effect/type :send-welcome-email :email email}]]))

(create-user broker)

(bus/dispatch broker {:command {:command/type :create-user
                                :email "ALICE@EXAMPLE.COM"
                                :name  "Alice Smith"}})
```

### Manual Setup (Lower-level)

```clojure
(require '[nurt.bus :as bus]
         '[nurt.bus.interceptor :as i])

(def broker (bus/in-memory-broker [:command :command/type]
              (i/logging {:level :debug})))

(bus/add-handler! broker :user-created [:command]
  (fn [{:keys [command]}]
    (println "Handling:" command)
    [:ok {:processed true} []]))

(bus/dispatch broker {:command {:command/type :user-created
                                :name "Alice"}})
```

### Using the Broker (Pre-configured)

```clojure
(require '[nurt.broker :as broker])

(def b (broker/create))

(broker/register-command! b :user/create
  (fn [{:keys [command]}]
    [:ok {:created true} []]))

(broker/execute! {:command/type :user/create :name "Alice"} {:broker b})
```

## Architecture

```
Event → Bus Interceptors → Handler Interceptors → Handler → Effects
```

### Core Namespaces

| Namespace | Description |
|-----------|-------------|
| `nurt.bus` | Core broker protocol and `InMemoryBroker` |
| `nurt.bus.interceptor` | Built-in interceptors (coeffect, transformer, validator, effects, logging) |
| `nurt.bus.macro` | `defcommand` macro system |
| `nurt.bus.util` | Command registration utilities |
| `nurt.broker` | Pre-configured broker with common integrations |
| `nurt.effect.*` | Effect constructors (db, http, email, csv, jms, command) |
| `nurt.io.*` | Low-level IO operations |
| `nurt.internal.json` | JSON abstraction layer |

## `defcommand` Options

```clojure
(defcommand my-command
  [{:keys [command coeffects]}]
  {:transformers  [...]   ; data transformations (run first)
   :id            ::spec  ; spec validation (after transforms)
   :coeffects     [...]   ; inject computed values
   :interceptors  [...]   ; custom interceptors
   :inject-path   [...]}  ; keys to inject into handler (default: [:command :coeffects])
  handler-body)
```

## License

MIT

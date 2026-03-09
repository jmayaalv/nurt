# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
bb nrepl       # Start nREPL server on port 7888
bb test        # Run all tests (clean first)
bb ci          # Full CI: test + build JAR
bb install     # Build and install JAR to local Maven repo
bb clean       # Remove build artifacts
```

Run tests directly:
```bash
clojure -X:test
```

Run a single test namespace:
```bash
clojure -X:test :nses '[nurt.bus-test]'
```

## Architecture

Nurt is a composable event bus library built on [exoscale/interceptor](https://github.com/exoscale/interceptor). The core abstraction is the `Broker` protocol with an `InMemoryBroker` implementation that routes events through hierarchical interceptor pipelines.

### Event Flow

```
dispatch(event) → bus interceptors → handler interceptors → handler fn → effects processing
```

### Layer Overview

**`nurt.bus`** — Core `Broker` protocol and `InMemoryBroker`. Handlers are registered with `add-handler!` specifying an event type, injection path (where the event payload is merged into the interceptor context), optional per-handler interceptors, and a handler function.

**`nurt.bus.interceptor`** — Built-in interceptors: `coeffect` (inject computed values), `transformer` (transform data), `validator` (validate with fn or spec), `effects` (process side effects), `logging`. Use `spec-validation` and `field-coercions` helpers for common patterns.

**`nurt.bus.macro`** — `defcommand` macro for declarative command registration. Generates a dual-arity function: 1-arity registers the command with a broker, 2-arity dispatches an event. Automatically composes interceptors from options.

**`nurt.bus.util`** — Utilities for bulk command registration: `apply-commands`, `register-namespace-commands`, `auto-register-commands`.

**`nurt.broker`** — Pre-configured broker with a standard interceptor chain (transformer → logging → validator → effects) and all built-in effects pre-registered. Entry point for most applications.

**`nurt.effect.*`** — Effect constructors (return effect maps) and executors (registered as interceptors). Built-in effects: `:db`, `:http`, `:email`, `:csv`, `:jms`, `:command`.

**`nurt.io.*`** — Low-level I/O wrappers: next.jdbc (`:db`), hato (`:http`), Apache Commons Email (`:email`), charred (`:csv`), JMS (`:jms`).

### Typical Usage Pattern

```clojure
;; High-level: defcommand macro
(defcommand create-user
  {:spec ::create-user-params
   :transformers [{:fields [:age] :fn parse-long}]
   :coeffects [{:id :current-time :fn (fn [_] (Instant/now))}]}
  [broker event]
  {:effects [(db/insert! :users event)]})

;; Register all commands in namespace
(register-namespace-commands! broker *ns*)
```

The `defcommand` options are validated at macro-expansion time. The `:spec` key integrates with `nurt.effect/effect-spec` multimethod for automatic spec dispatch.

### clj-kondo Integration

The library ships macro hooks for `defcommand` in `resources/clj-kondo.exports/`. Fixtures for hook testing live in `kondo_fixtures/`.

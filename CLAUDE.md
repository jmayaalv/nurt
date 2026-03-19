# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
bb nrepl        # Start dev nREPL (all modules) on port 7888
bb test         # Run all module tests
bb ci           # Full CI: test + install all modules
bb install      # Build and install all modules to local Maven
bb clean        # Remove all build artifacts
bb deploy       # Deploy all modules to Clojars
```

Per-module test tasks (each module has its own `deps.edn`):
```bash
bb test-core    # nurt-core
bb test-broker  # nurt-broker
bb test-db      # nurt-db
bb test-http    # nurt-http
bb test-email   # nurt-email
bb test-csv     # nurt-csv
bb test-async   # nurt-async
```

Run tests directly inside a module directory:
```bash
cd modules/nurt-core
clojure -X:test
```

Run a single test namespace inside a module:
```bash
clojure -X:test :nses '[nurt.bus-test]'
```

## Monorepo Structure

This is a multi-module monorepo. Each module under `modules/` is an independent library with its own `deps.edn` and test suite. The root `deps.edn` aggregates all modules except `nurt-async` (which is optional/standalone).

| Module | Description |
|---|---|
| `nurt-core` | Core bus, interceptors, effect protocol |
| `nurt-broker` | Pre-configured broker, `defcommand` macro, bulk registration utils |
| `nurt-async` | Async job queue effect (Postgres-backed) |
| `nurt-db` | DB effect/IO (next.jdbc) |
| `nurt-http` | HTTP effect/IO (hato) |
| `nurt-email` | Email effect/IO (Apache Commons Email) |
| `nurt-csv` | CSV effect/IO (charred) |

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

**`nurt.effect.*`** — Effect constructors (return effect maps) and executors (registered as interceptors). Built-in effects: `:db`, `:http`, `:email`, `:csv`, `:jms`, `:command`, `:async`.

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

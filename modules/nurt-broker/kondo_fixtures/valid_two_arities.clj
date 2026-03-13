;; Fixture: exercises both arities of the generated function.
;; clj-kondo should not warn about wrong-number-of-args for either call.
(ns kondo-fixtures.valid-two-arities
  (:require [nurt.bus.macro :refer [defcommand]]))

(defcommand process
  [{:keys [command]}]
  {}
  [:ok {:processed true} []])

(defn register! [broker]
  ;; 1-arity: registers the handler, returns broker
  (process broker))

(defn invoke! [broker ctx]
  ;; 2-arity: calls handler directly or dispatches through broker
  (process broker ctx))

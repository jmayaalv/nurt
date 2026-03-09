;; Fixture: basic valid defcommand usage.
;; clj-kondo should report 0 errors.
(ns kondo-fixtures.valid-basic
  (:require [nurt.bus.macro :refer [defcommand]]))

(defcommand create-user
  [{:keys [command]}]
  {}
  [:ok {:result "success"} []])

;; Generated function should be callable as 1-arg (returns broker for chaining)
;; clj-kondo should not warn about wrong arity here.
(defn register! [broker]
  (create-user broker))

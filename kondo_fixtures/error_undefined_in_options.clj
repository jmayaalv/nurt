;; Fixture: options map references an undefined interceptor var.
;; clj-kondo MUST catch this — this is what the old hook missed because
;; options-node was stuffed inside the handler body at the wrong position,
;; causing analysis context confusion. The fixed hook emits options-node
;; as a standalone expression so this reference IS validated.
(ns kondo-fixtures.error-undefined-in-options
  (:require [nurt.bus.macro :refer [defcommand]]))

(defcommand bad-options-command
  [{:keys [command]}]
  ;; undefined-interceptor is not defined anywhere — should be flagged
  {:interceptors [undefined-interceptor]}
  [:ok {} []])

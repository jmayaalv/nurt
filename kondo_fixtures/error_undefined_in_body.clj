;; Fixture: handler body references an undefined symbol.
;; clj-kondo MUST catch this as an unresolved-symbol error/warning.
(ns kondo-fixtures.error-undefined-in-body
  (:require [nurt.bus.macro :refer [defcommand]]))

(defcommand bad-command
  [{:keys [command]}]
  {}
  ;; this-var-does-not-exist is not defined anywhere — should be flagged
  (this-var-does-not-exist command))

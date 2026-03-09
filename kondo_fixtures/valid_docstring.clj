;; Fixture: defcommand with optional docstring.
;; clj-kondo should report 0 errors.
(ns kondo-fixtures.valid-docstring
  (:require [nurt.bus.macro :refer [defcommand]]))

(defcommand create-user
  "Creates a new user account."
  [{:keys [command]}]
  {}
  [:ok {:result "success"} []])

(defn register! [broker]
  (create-user broker))

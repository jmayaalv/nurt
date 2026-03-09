(ns nurt.effect.command
  (:require [clojure.spec.alpha :as s]
            [nurt.effect :as effect]
            [nurt.bus :as bus]))

(s/def ::commands (s/coll-of :broker/command))

(defmethod effect/effect-spec :command [_]
  (s/keys :req-un [::commands]
          :req [:effect/type]))

(defn command
  "Creates a command effect

  Args:
    command-or-cmmands - Either a single command map or collection of command maps

  Returns:
    Command effect map with :effect/type :command

  Examples:
    ;; Single message
    (command {....})


    (jms [{...}
          {....}])"
  [command-or-commands]
  (let [commands (if (vector? command-or-commands)
                   command-or-commands
                   [command-or-commands])
        effect   {:effect/type :command
                  :commands    commands}]
    effect))


(defn command!
  "Executes a command effect by sending the command the the kane broker

  This is the effect handler function that performs the actual call .

  Args:
    effect -  Command effect map containing :commands
    context - Execution context containing :broker

  Note:
    This function is typically not called directly. It's intended to be
    registered as an effect handler in the broker and called automatically
    during command processing."
  [{:keys [commands] :as effect} {:keys [broker] :as context}]
  (run! (fn [command]
          (bus/dispatch broker (assoc context :command command)))
        commands))

(ns hooks.nurt.bus.macro
  (:require [clj-kondo.hooks-api :as api]))

(defn defcommand-hook
  "clj-kondo hook for the defcommand macro.

   defcommand generates:
   1. An inner private handler <name>-handler with the user's binding-vec and body
   2. A public registration function <name> with two arities:
      - [broker]       - registers the handler to the broker
      - [broker ctx]   - calls handler directly or dispatches through broker

   Supports optional docstring:
     (defcommand name binding-vec options & body)
     (defcommand name \"docstring\" binding-vec options & body)"
  [{:keys [node]}]
  (let [children       (:children node)
        [_ name-node & args] children
        has-docstring? (and (seq args) (api/string-node? (first args)))
        [_docstring binding-vec options-node & body-nodes]
        (if has-docstring? args (cons nil args))
        handler-sym    (symbol (str (api/sexpr name-node) "-handler"))]
    (when (and name-node binding-vec)
      {:node (api/list-node
              (list*
               (api/token-node 'do)
               (concat
                   ;; Include options-node as a standalone expression so clj-kondo
                   ;; validates references to interceptors/vars/specs used in options
                (when options-node [options-node])
                   ;; Inner handler function - clj-kondo analyses binding + body
                [(api/list-node
                  (list*
                   (api/token-node 'defn)
                   (api/token-node handler-sym)
                   binding-vec
                   body-nodes))
                    ;; Public registration function visible to callers
                 (api/list-node
                  (list
                   (api/token-node 'defn)
                   name-node
                   (api/list-node
                    (list
                     (api/vector-node [(api/token-node 'broker)])
                     (api/token-node 'broker)))
                   (api/list-node
                    (list
                     (api/vector-node [(api/token-node 'broker)
                                       (api/token-node 'ctx)])
                     (api/list-node
                      (list
                       (api/token-node handler-sym)
                       (api/token-node 'ctx)))))))])))})))

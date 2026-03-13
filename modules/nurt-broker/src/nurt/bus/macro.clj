(ns nurt.bus.macro
  "Declarative command registration macro for Nurt.

  The defcommand macro provides a clean, configuration-driven way to register
  event handlers with automatic interceptor composition, spec validation,
  and field coercion."
  (:require [nurt.bus :as bus]
            [nurt.bus.interceptor :as i]
            [nurt.broker]
            [clojure.string :as str]))

(defn- normalize-command-type
  "Convert command name to kebab-case keyword."
  [name-symbol]
  (-> (str name-symbol)
      (str/replace #"_" "-")
      keyword))

(defn- expand-coeffects
  "Expand coeffects into interceptor form. Supports these formats:
  1. Vector of interceptors: [(i/coeffect :key fn) ...] (legacy)
  2. Map format: {:key fn, :key2 fn2, ...} (legacy)
  3. Vector of maps: [{:key fn} {:key2 fn2} ...] (new)"
  [coeffects]
  (cond
    (vector? coeffects)
    (if (and (seq coeffects) (map? (first coeffects)))
      ;; New format: vector of maps like [{:request-id fn}]
      (mapv (fn [coeff-map]
              (when-not (= 1 (count coeff-map))
                (throw (ex-info "Each coeffect map must contain exactly one key-value pair" {:coeffect coeff-map})))
              (let [[k f] (first coeff-map)]
                `(i/coeffect ~k ~f)))
            coeffects)
      ;; Legacy format: vector of interceptors
      coeffects)

    (map? coeffects)
    ;; Legacy format: map of key->fn
    (mapv (fn [[k f]] `(i/coeffect ~k ~f)) coeffects)

    :else
    (throw (ex-info "coeffects must be a vector or map" {:coeffects coeffects}))))

(defn- expand-transformers
  "Expand transformers into interceptor form. Supports these formats:
  1. Vector of interceptors: [(i/transformer {...}) ...] (legacy)
  2. Vector of maps: [{:path [...] :transform-fn fn} ...] (new)"
  [transformers]
  (when transformers
    (if (and (vector? transformers)
             (seq transformers)
             (map? (first transformers))
             (contains? (first transformers) :path))
      ;; New format: vector of maps like [{:path [:command :email] :transform-fn fn}]
      (mapv (fn [transformer-map]
              (when-not (and (:path transformer-map) (:transform-fn transformer-map))
                (throw (ex-info "Each transformer map must contain :path and :transform-fn keys"
                                {:transformer transformer-map})))
              `(i/transformer ~transformer-map))
            transformers)
      ;; Legacy format: assume it's already a vector of interceptors
      transformers)))

(defn- build-interceptor-chain
  "Build interceptor chain from options map in proper order:
  transformers → validation → coeffects → custom interceptors."
  [{:keys [coeffects id transformers interceptors]}]
  (let [expanded-coeffects (when coeffects (expand-coeffects coeffects))
        expanded-transformers (expand-transformers transformers)]
    (vec (concat
          (or expanded-transformers [])
          (when id `[(i/spec-validation ~id)])
          expanded-coeffects
          (or interceptors [])))))

(defn- validate-options
  "Validate options map at compile time."
  [options]
  (when-not (map? options)
    (throw (ex-info "Options must be a map" {:options options})))

  (let [valid-keys #{:coeffects :id :transformers :interceptors :inject-path}
        invalid-keys (remove valid-keys (keys options))]
    (when (seq invalid-keys)
      (throw (ex-info "Invalid option keys"
                      {:invalid-keys invalid-keys
                       :valid-keys valid-keys}))))

  ;; Validate specific option types
  (when-let [coeffects (:coeffects options)]
    (when-not (or (vector? coeffects) (map? coeffects))
      (throw (ex-info ":coeffects must be a vector or map" {:coeffects coeffects})))
    ;; Additional validation for new vector of maps format
    (when (and (vector? coeffects) (seq coeffects) (map? (first coeffects)))
      (doseq [coeff-map coeffects]
        (when-not (and (map? coeff-map) (= 1 (count coeff-map)))
          (throw (ex-info "Each coeffect in vector must be a map with exactly one key-value pair"
                          {:coeffect coeff-map}))))))

  (when-let [id (:id options)]
    (when-not (keyword? id)
      (throw (ex-info ":id must be a keyword" {:id id}))))

  (when-let [transformers (:transformers options)]
    (when-not (or (vector? transformers) (list? transformers))
      (throw (ex-info ":transformers must be a vector or function call returning a vector" {:transformers transformers})))
    ;; Additional validation for new vector of maps format
    (when (and (vector? transformers) (seq transformers) (map? (first transformers)))
      (doseq [transformer-map transformers]
        (when-not (and (map? transformer-map)
                       (:path transformer-map)
                       (:transform-fn transformer-map))
          (throw (ex-info "Each transformer in vector must be a map with :path and :transform-fn keys"
                          {:transformer transformer-map}))))))

  (when-let [interceptors (:interceptors options)]
    (when-not (vector? interceptors)
      (throw (ex-info ":interceptors must be a vector" {:interceptors interceptors}))))

  options)

(defmacro defcommand
  "Defines a command handler function that registers itself to a broker.

  Syntax:
  (defcommand name [docstring] binding-vec options-map & body)

  Parameters:
  - name: Symbol for the command name (converted to kebab-case keyword)
  - docstring: Optional docstring for the generated function (string)
  - binding-vec: Destructuring vector for handler function (typically [{:keys [command coeffects]}])
  - options-map: Configuration map with optional keys:
    - :coeffects - Vector of maps [{:name fn} ...] OR legacy formats
    - :id - Command ID keyword (used for registration and spec validation)
    - :transformers - Vector of maps [{:path [...] :transform-fn fn} ...] OR legacy format
    - :interceptors - Vector of custom interceptors
    - :inject-path - Path keys to inject into handler (defaults to [:command :coeffects])
  - body: Handler function body

  Creates a dual-arity function:
  - 1-arity [broker]: Registers the command handler to the broker
  - 2-arity [broker context]: Intelligent invocation based on broker parameter:
    * If broker is nil: Direct handler call (bypasses all interceptors)
    * If broker is non-nil: Auto-registers if needed, then dispatches via broker (full pipeline)

  The function var has :defcommand metadata for introspection and auto-registration.

  When :id is provided, automatically generates a defmethod for nurt.broker/command-spec
  multimethod that maps the command type to its spec keyword.

  Interceptor Order (1-arity and 2-arity with non-nil broker):
  transformers → spec validation → coeffects → custom interceptors → handler

  The 2-arity form provides two testing modes:
  - Pure handler testing: Pass nil as broker to bypass all interceptors
  - Integration testing: Pass broker to use full interceptor pipeline with auto-registration

  Examples:
  (defcommand create-user
    \"Creates a new user account with validation and effects processing.\"
    [{:keys [command coeffects]}]
    {:transformers [{:path [:command :email]
                     :transform-fn clojure.string/lower-case}]
     :id ::user-creation-spec
     :coeffects [{:request-id (fn [_] (str \"req-\" (random-uuid)))}]
     :interceptors [audit-interceptor]}
    [:ok {:user-id (random-uuid) :email (:email command)}
     [{:effect/type :email :email (:email command)}]])

  ;; Without docstring (backward compatible):
  (defcommand update-user
    [{:keys [command coeffects]}]
    {:id ::user-update-spec}
    [:ok {:updated true} []])

  ;; This automatically generates:
  ;; (defmethod nurt.broker/command-spec :create-user [_] ::user-creation-spec)

  ;; Usage patterns:

  ;; 1-arity: Register to broker
  (create-user my-broker)

  ;; 2-arity: Pure handler testing (nil broker - bypasses all interceptors)
  (create-user nil {:command {:name \"Alice\" :email \"alice@example.com\"}
                    :coeffects {:request-id \"req-123\"}})

  ;; 2-arity: Integration testing (non-nil broker - auto-registers + full pipeline)
  (create-user my-broker {:command {:name \"Alice\" :email \"ALICE@EXAMPLE.COM\"}})
  ;; Note: Email will be lowercased by transformer, coeffects will be injected

  ;; Chain registrations:
  (-> my-broker create-user update-user delete-user)

  ;; Enhanced testing benefits:
  ;; - Pure handler testing: nil broker bypasses all interceptors
  ;; - Integration testing: non-nil broker uses full pipeline with auto-registration
  ;; - Clean abstraction: callers don't need to understand bus internals
  ;; - Smart auto-registration: no need to manually register before testing"
  [name & args]
  ;; Parse arguments to detect optional docstring
  (let [[docstring binding-vec options & body]
        (if (and (seq args) (string? (first args)))
          ;; First arg is docstring
          args
          ;; No docstring, add nil as placeholder
          (cons nil args))]

    ;; Validate options at compile time
    (validate-options options)

    (let [command-type        (or (:id options) (normalize-command-type name))
          inject-path         (get options :inject-path [:command :coeffects])
          interceptors        (build-interceptor-chain options)
          handler-metadata    {:defcommand       {:name         command-type
                                                  :options      options
                                                  :inject-path  inject-path
                                                  :interceptors interceptors}
                               :clj-kondo/ignore [:unused-public-var]}
          id-spec             (:id options)
          generated-docstring (or docstring
                                  (str "Command handler function for " command-type ". Generated by defcommand macro."))]

      (let [handler-name# (symbol (str name "-handler"))]
        `(do
         ;; Generate defmethod for nurt.broker/command-spec if :id is provided
           ~@(when id-spec
               `((defmethod nurt.broker/command-spec ~id-spec [~'_] ~id-spec)))

         ;; Generate the named handler function
           (defn- ~(with-meta handler-name# {:clj-kondo/ignore [:unused-public-var]})
             ~(str "Handler function for " command-type ". Generated by defcommand macro.")
             ~binding-vec
             ~@body)

         ;; Generate the command registration function
           (defn ~(with-meta name handler-metadata)
             ~generated-docstring
             ([~'broker]
              (bus/add-handler! ~'broker ~command-type ~inject-path ~interceptors
                                (var ~handler-name#))
              ~'broker)
             ([~'broker ~'context]
              (if (nil? ~'broker)
              ;; Direct handler call - bypass broker and interceptors
                (~handler-name# ~'context)
              ;; Broker provided - use 1-arity to register, then dispatch
                (do
                  (when-not (bus/registered? ~'broker ~command-type)
                    (~name ~'broker))  ; Call 1-arity function to register
                ;; Convert context to proper dispatch format and execute
                  (let [dispatch-event# (if (and (map? ~'context) (contains? ~'context ~(first (get options :inject-path [:command :coeffects]))))
                                        ;; Context already has proper keys, just add command type
                                          (assoc-in ~'context [:command :command/type] ~command-type)
                                        ;; Simple context, wrap it properly
                                          {:command (assoc ~'context :command/type ~command-type)})]
                    (bus/dispatch ~'broker dispatch-event#)))))))))))

(defn command-metadata
  "Extract metadata from a command registration function.
  Works with both vars and functions."
  [command-fn-or-var]
  (let [metadata (meta command-fn-or-var)]
    (if (:defcommand metadata)
      (:defcommand metadata)
      ;; If it's a function without metadata, try to find its var
      ;; by checking all vars in all loaded namespaces
      (when (fn? command-fn-or-var)
        (->> (all-ns)
             (mapcat ns-publics)
             (map second)
             (filter #(= (var-get %) command-fn-or-var))
             (first)
             (meta)
             (:defcommand))))))

(defn command-name
  "Get the command name from a command registration function or var."
  [command-fn-or-var]
  (-> command-fn-or-var command-metadata :name))

(defn command-options
  "Get the options map from a command registration function."
  [command-fn]
  (-> command-fn command-metadata :options))

(defn command-interceptors
  "Get the computed interceptor chain from a command registration function."
  [command-fn]
  (-> command-fn command-metadata :interceptors))

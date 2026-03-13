(ns nurt.bus.interceptor
  (:require [exoscale.interceptor :as i]
            [clojure.tools.logging :as log]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(defn logging
  "Creates a logging interceptor that logs events at the specified level.

  Options:
  - :level - Log level (:info, :debug, :warn, :error), defaults to :info
  - :path -  path-fn to log. defaults to nil
  - :message - Custom message format function, receives selected path.
  - :name - Interceptor name, defaults to ::logging"
  ([] (logging {}))
  ([{:keys [level message name path]
     :or {level :info
          message (fn [x]
                    (log/log level x))
          name ::logging}}]
   {:name name
    :enter (fn [ctx]
             (if (seq path)
               (message (get-in ctx path))
               (message ctx))
             ctx)}))

(defn coeffect
  "Creates a coeffect interceptor that adds a new value to the context.

  Parameters:
  - key - Keyword to add to the context
  - value-fn - Function or var that receives context and returns the value to add

  Examples:
  (coeffect :now (fn [ctx] (java.util.Date.)))
  (coeffect :timestamp (fn [ctx] (System/currentTimeMillis)))
  (coeffect :user-id (fn [ctx] (get-in ctx [:session :user-id])))"
  [key value-fn]
  {:pre [(keyword? key) (or (fn? value-fn) (var? value-fn))]}
  {:name (keyword (str (name ::coeffect) "/" (if (namespace key)
                                               (str (namespace key) "/" (name key))
                                               (name key))))
   :enter (fn [ctx]
            (assoc-in ctx [:coeffects key] (value-fn ctx)))})

(defn transformer
  "Creates a transformer interceptor that transforms values in the context.

  Options:
  - :path - Path to the value to transform (vector of keys)
  - :transform-fn - Function to apply to the value at path
  - :name - Interceptor name, defaults to ::transformer

  Examples:
  (transformer {:path [:payload :amount] :transform-fn #(* % 100)})
  (transformer {:path [:user :name] :transform-fn clojure.string/upper-case})"
  [{:keys [path transform-fn name]
    :or {name ::transformer}}]
  {:pre [(vector? path) (fn? transform-fn)]}
  {:name name
   :enter (fn [ctx]
            (if (get-in ctx path)
              (try
                (update-in ctx path transform-fn)
                (catch Exception e
                  (i/error ctx (ex-info "Coercion failed"
                                        {:path path
                                         :value (get-in ctx path)
                                         :transform-fn transform-fn
                                         :cause e}))))
              ctx))})

(defn validator
  "Creates a validator interceptor that adds validation results to context.

  Options:
  - :path - Path to the value to validate (vector of keys)
  - :validate-fn - Function that returns [:ok value] on success or [:error errors] on failure
  - :name - Interceptor name, defaults to ::validator

  The validation function should return:
  [:ok validated-value]        ; for success (value may be transformed/normalized)
  [:error ex-info] ; for failure with list of error messages

  Examples:
  (validator {:path [:command :email]
              :validate-fn #(if (and (string? %) (re-matches #\".+@.+\" %))
                             [:ok (clojure.string/lower-case %)]
                             [:error (ex-info \"invalid mail {:details [\"Invalid email format\"]})])})"
  [{:keys [path validate-fn name]
    :or   {name ::validator}}]
  {:pre [(vector? path) (fn? validate-fn)]}
  {:name  name
   :enter (fn [ctx]
            (let [value         (get-in ctx path)
                  [status data] (validate-fn value)]
              (if (= :ok status)
                ctx
                (i/error ctx data))))})

(defn- missing-effects
  [effect-type->fn effects]
  (reduce (fn [missing effect]
            (if (nil? (get effect-type->fn (:effect/type effect)))
              (assoc missing (:effect/type effect) effect)
              missing))
          {}
          effects))

(defn- get-effect-fn
  [effect-type-fn effect-type]
  (when-let [fn-or-var (get effect-type-fn effect-type)]
    (if (var? fn-or-var)
      (deref fn-or-var)
      fn-or-var)))

(defn effects
  "Creates an effects interceptor that processes handler return values and executes effects.

  Each call creates an isolated effect registry — multiple broker instances do not
  share effect registrations, preventing cross-test and cross-broker contamination.

  The interceptor map exposes a :registry+ atom so additional effects can be
  registered after creation via nurt.bus/register-effect!.

  Handler should return: [:ok result effects] or [:error error-data]
  On :error the pipeline is stopped and no effects are executed.

  Parameters:
  - effect-fns - Map of effect keywords to functions that execute the effect

  Examples:
  (effects {:log (fn [effect ctx] (println (:message effect)))
            :db-save (fn [effect ctx] (save-to-db (:data effect)))
            :email (fn [effect ctx] (send-email (:to effect) (:body effect)))})"
  [effect-fns]
  {:pre [(map? effect-fns)]}
  (let [registry+ (atom effect-fns)]
    {:name      ::effects
     :registry+ registry+
     :leave     (fn [ctx]
                  (let [result (get ctx :response)]
                    (try
                      (if (vector? result)
                        (condp = (first result)
                          :ok    (let [[_ data effects] result
                                       missing          (missing-effects @registry+ effects)]
                                   (if (seq missing)
                                     (i/error ctx (ex-info "Effects without registered handler" missing))
                                     (do (run! (fn [effect]
                                                 (if-let [effect-fn (get-effect-fn @registry+ (:effect/type effect))]
                                                   (do (log/debug "Executing effect" effect)
                                                       (effect-fn effect ctx))
                                                   (log/warn "No effect fn registered for" effect)))
                                               effects)
                                         (assoc ctx :response data))))
                          :error (let [[_ error] result]
                                   (i/error ctx error))
                          (i/error ctx (ex-info "Invalid handler response" {:response result})))
                        ctx)
                      (catch Exception e
                        (i/error ctx e)))))}))

(defn register-effect!
  "Registers an additional effect handler with an existing effects interceptor.

  Parameters:
  - effects-interceptor - The interceptor map returned by (effects {...})
  - effect-type         - Keyword identifying the effect type to handle
  - effect-fn           - Function or var that processes the effect, receives (effect ctx)

  Returns the effects-interceptor (for threading).

  Examples:
  (register-effect! effects-int :send-email (fn [effect ctx] (send-email (:to effect) (:body effect))))
  (register-effect! effects-int :log #'my-log-handler)"
  [effects-interceptor effect-type effect-fn]
  {:pre [(map? effects-interceptor)
         (:registry+ effects-interceptor)
         (keyword? effect-type)
         (or (fn? effect-fn) (var? effect-fn))]}
  (swap! (:registry+ effects-interceptor) assoc effect-type effect-fn)
  effects-interceptor)

;;; =============================================================================
;;; Helper Functions Using Existing Interceptors
;;; =============================================================================

(defn spec-validation
  "Creates a spec validation interceptor using existing validator interceptor.

  Parameters:
  - spec-key - A spec keyword to validate against
  - options - Optional map with:
    - :path - Path to validate (defaults to [:command])
    - :name - Interceptor name

  The validator returns [:ok value] on success or [:error ex-info] on failure.

  Examples:
  (spec-validation ::user-creation-spec)
  (spec-validation ::payment-spec {:path [:command :payment]})"
  ([spec-key] (spec-validation spec-key {}))
  ([spec-key {:keys [path name]
              :or {path [:command]
                   name (keyword (str "spec-validation/" (name spec-key)))}}]
   {:pre [(keyword? spec-key)]}
   (validator {:path path
               :name name
               :validate-fn (fn [value]
                              (if (s/valid? spec-key value)
                                [:ok value]
                                (let [explain-data (s/explain-data spec-key value)
                                      error-msg (str "Spec validation failed for " spec-key)
                                      error-data {:spec spec-key
                                                  :path path
                                                  :value value
                                                  :problems (:clojure.spec.alpha/problems explain-data)}]
                                  [:error (ex-info error-msg error-data)])))})))

(defn field-coercions
  "Creates multiple transformer interceptors for field coercion.

  Parameters:
  - coercion-map - Map of field paths to transformation functions
    - Keys can be keywords for simple fields: {:amount #(BigDecimal. %)}
    - Or vectors for nested paths: {[:user :name] clojure.string/trim}
  - options - Optional map with:
    - :base-path - Base path for coercion (defaults to [:command])

  Returns a vector of transformer interceptors.

  Examples:
  (field-coercions {:amount #(BigDecimal. %)
                    :name clojure.string/trim})
  (field-coercions {[:user :email] clojure.string/lower-case
                    [:payment :amount] #(* % 100)}
                   {:base-path [:data]})"
  ([coercion-map] (field-coercions coercion-map {}))
  ([coercion-map {:keys [base-path]
                  :or {base-path [:command]}}]
   {:pre [(map? coercion-map)]}
   (mapv (fn [[field-path transform-fn]]
           (let [full-path (if (vector? field-path)
                             (into base-path field-path)
                             (conj base-path field-path))
                 field-name (if (vector? field-path)
                              (str/join "-" (map name field-path))
                              (name field-path))]
             (transformer {:path full-path
                           :transform-fn transform-fn
                           :name (keyword (str "coerce/" field-name))})))
         coercion-map)))

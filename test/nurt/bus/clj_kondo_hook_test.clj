(ns ^:parallel nurt.bus.clj-kondo-hook-test
  "Tests for the defcommand clj-kondo hook.

  These tests verify that the hook produces correct static analysis results:
  - Valid usage produces no errors
  - Undefined vars in handler body are caught (standard analysis)
  - Undefined vars in options map are caught (verifies the hook emits
    options-node as a standalone expression, not buried in handler body)
  - Both arities of generated function are recognized without arity warnings"
  (:require [clojure.test :refer [deftest is testing]]
            [clj-kondo.core :as kondo]))

;; Fixtures live outside the classpath (kondo-fixtures/) so the test runner
;; does not try to compile intentionally-invalid Clojure files.
(def ^:private fixtures-dir "kondo_fixtures")

(defn- lint [filename]
  (let [path (str fixtures-dir "/" filename)]
    (-> (kondo/run! {:lint [path]})
        (select-keys [:findings :summary]))))

(defn- errors [result]
  (filter #(= :error (:level %)) (:findings result)))

(defn- warnings-of-type [result type]
  (filter #(and (= :warning (:level %)) (= type (:type %))) (:findings result)))

(defn- has-finding? [result level message-substr]
  (some #(and (= level (:level %))
              (.contains ^String (:message %) message-substr))
        (:findings result)))

;; ---------------------------------------------------------------------------
;; Valid usage — no errors expected
;; ---------------------------------------------------------------------------

(deftest ^:parallel valid-basic-no-errors-test
  (testing "Basic defcommand produces no errors"
    (let [result (lint "valid_basic.clj")]
      (is (empty? (errors result))
          (str "Unexpected errors: " (map :message (errors result)))))))

(deftest ^:parallel valid-docstring-no-errors-test
  (testing "defcommand with optional docstring produces no errors"
    (let [result (lint "valid_docstring.clj")]
      (is (empty? (errors result))
          (str "Unexpected errors: " (map :message (errors result)))))))

(deftest ^:parallel valid-with-options-no-errors-test
  (testing "defcommand with transformers/coeffects/interceptors produces no errors when all vars are resolved"
    (let [result (lint "valid_with_options.clj")]
      (is (empty? (errors result))
          (str "Unexpected errors: " (map :message (errors result)))))))

(deftest ^:parallel valid-two-arities-no-arity-errors-test
  (testing "Both 1-arg and 2-arg calls to generated function produce no arity errors"
    (let [result (lint "valid_two_arities.clj")]
      (is (empty? (errors result))
          (str "Unexpected errors: " (map :message (errors result))))
      (is (empty? (warnings-of-type result :wrong-number-of-args))
          "Should not warn about wrong number of args for either arity"))))

;; ---------------------------------------------------------------------------
;; Error detection — hook must surface real problems
;; ---------------------------------------------------------------------------

(deftest ^:parallel error-undefined-in-body-test
  (testing "Unresolved symbol in handler body is caught"
    (let [result (lint "error_undefined_in_body.clj")]
      (is (has-finding? result :error "this-var-does-not-exist")
          "Should flag unresolved symbol in handler body"))))

(deftest ^:parallel error-undefined-in-options-test
  (testing "Unresolved symbol in options map is caught"
    ;; This is the key regression test for the hook fix.
    ;; The old hook embedded options-node inside the handler function body,
    ;; which confused clj-kondo's analysis context for qualified references.
    ;; The fixed hook emits options-node as a standalone top-level expression,
    ;; so undefined vars in :interceptors/:coeffects/:transformers ARE found.
    (let [result (lint "error_undefined_in_options.clj")]
      (is (has-finding? result :error "undefined-interceptor")
          "Should flag unresolved symbol used in :interceptors option"))))

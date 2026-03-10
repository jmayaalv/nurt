(ns ^:parallel nurt.effect-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [nurt.effect :as effect]
            [nurt.effect.db]
            [nurt.effect.csv]
            [nurt.effect.email]
            [nurt.effect.http]))

(deftest effect-spec-multimethod-test
  (testing "effect-spec multimethod dispatches on effect type"
    (is (some? (effect/effect-spec {:effect/type :db})))))

(deftest broker-effect-spec-test
  (testing ":broker/effect spec validation"
    (testing "validates DB effects"
      (let [valid-db-effect {:effect/type :db
                             :statements  [["SELECT * FROM users WHERE id = ?" 1]]}]
        (is (s/valid? :broker/effect valid-db-effect))
        (is (nil? (s/explain-data :broker/effect valid-db-effect)))))

    (testing "rejects invalid effect types"
      (let [invalid-effect {:effect/type :unknown}]
        (is (not (s/valid? :broker/effect invalid-effect)))
        (is (some? (s/explain-data :broker/effect invalid-effect)))))

    (testing "rejects effects without :effect/type"
      (let [effect-without-type {:statements [["SELECT 1"]]}]
        (is (not (s/valid? :broker/effect effect-without-type)))
        (is (some? (s/explain-data :broker/effect effect-without-type)))))))

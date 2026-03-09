(ns nurt.effect
  (:require [clojure.spec.alpha :as s]))

(defmulti effect-spec :effect/type)
(s/def :broker/effect (s/multi-spec effect-spec :effect/type))

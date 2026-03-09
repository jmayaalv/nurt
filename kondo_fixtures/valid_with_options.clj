;; Fixture: defcommand with transformers, coeffects, and interceptors.
;; All references in options are properly required.
;; clj-kondo should report 0 errors.
(ns kondo-fixtures.valid-with-options
  (:require [clojure.string :as str]
            [nurt.bus.macro :refer [defcommand]]
            [nurt.bus.interceptor :as i]))

(def my-interceptor {:name ::my-interceptor :enter identity})

(defcommand create-user
  [{:keys [command coeffects]}]
  {:transformers [(i/transformer {:path [:command :email]
                                  :transform-fn str/lower-case})]
   :coeffects    [(i/coeffect :request-id (fn [_] (str (random-uuid))))]
   :interceptors [my-interceptor]}
  [:ok {:email (:email command)
        :id    (:request-id coeffects)} []])

(defn register! [broker]
  (create-user broker))

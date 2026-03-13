(ns nurt.integration-test
  "Root integration tests exercising the full wired system across all modules."
  (:require
   [clojure.test :refer [deftest is testing]]
   [nurt.broker :as broker]
   [nurt.bus :as bus]
   [nurt.bus.macro :refer [defcommand]]
   [nurt.effect.db :as effect.db]
   [nurt.effect.csv :as effect.csv]
   [nurt.effect.http :as effect.http]
   [nurt.effect.email :as effect.email]
   [clojure.spec.alpha :as s]))

(s/def ::name string?)
(s/def ::amount pos?)
(s/def ::integration-cmd-spec (s/keys :req-un [::name ::amount]))

(deftest ^:parallel broker-wired-integration-test
  (testing "Full broker with all effects wired"
    (defcommand wired-integration-command
      [{:keys [command coeffects]}]
      {:id ::integration-cmd-spec
       :coeffects [{:ts (fn [_] (System/currentTimeMillis))}]}
      (let [{:keys [ts]} coeffects]
        [:ok {:name (:name command) :ts ts} []]))

    (let [b (broker/create)]
      (wired-integration-command b)
      (let [result (broker/execute! {:command/type ::integration-cmd-spec
                                     :name "Integration"
                                     :amount 42}
                                    {:broker b})]
        (is (= "Integration" (:name result)))
        (is (number? (:ts result)))))))

(deftest ^:parallel effect-constructors-test
  (testing "Effect constructor functions from all modules are available"
    (is (= :db   (:effect/type (effect.db/db ["SELECT 1"]))))
    (is (= :csv  (:effect/type (effect.csv/csv {:data [{:a 1}] :path "/tmp/x.csv"}))))
    (is (= :http (:effect/type (effect.http/http {:method :get :url "http://example.com"}))))
    (is (= :email (:effect/type (effect.email/email {:to ["a@b.com"] :from "c@d.com"
                                                     :subject "Hi" :text-body "Hello"}))))))

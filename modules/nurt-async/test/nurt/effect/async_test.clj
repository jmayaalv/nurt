(ns ^:parallel nurt.effect.async-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [nurt.effect :as nurt.effect]
            [nurt.effect.async :as effect-async]))

(deftest async-effect-spec-test
  (testing "Async effect specification validation"
    (testing "validates valid async effects"
      (testing "basic enqueue"
        (let [valid-effect {:effect/type :async
                            :queue       :send-welcome-email
                            :payload     {:user-id 123}}]
          (is (s/valid? (nurt.effect/effect-spec valid-effect) valid-effect))))

      (testing "enqueue with opts"
        (let [valid-effect {:effect/type :async
                            :queue       :process-order
                            :payload     {:order-id 456}
                            :opts        {:job-table "custom_jobs"}}]
          (is (s/valid? (nurt.effect/effect-spec valid-effect) valid-effect))))

      (testing "enqueue with all opts"
        (let [valid-effect {:effect/type :async
                            :queue       :process-order
                            :payload     {:order-id 456}
                            :opts        {:job-table          "custom_jobs"
                                          :archived-job-table "custom_archived_jobs"}}]
          (is (s/valid? (nurt.effect/effect-spec valid-effect) valid-effect)))))

    (testing "rejects invalid async effects"
      (testing "missing queue"
        (let [invalid-effect {:effect/type :async
                              :payload     {:user-id 123}}]
          (is (not (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect)))))

      (testing "missing payload"
        (let [invalid-effect {:effect/type :async
                              :queue       :send-email}]
          (is (not (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect)))))

      (testing "queue not a keyword"
        (let [invalid-effect {:effect/type :async
                              :queue       "send-email"
                              :payload     {:user-id 123}}]
          (is (not (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect)))))

      (testing "payload not a map"
        (let [invalid-effect {:effect/type :async
                              :queue       :send-email
                              :payload     [1 2 3]}]
          (is (not (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect))))))))

(deftest enqueue-constructor-test
  (testing "enqueue creates valid effect maps"
    (testing "2-arity form"
      (let [effect (effect-async/enqueue :send-welcome-email {:user-id 123})]
        (is (= :async (:effect/type effect)))
        (is (= :send-welcome-email (:queue effect)))
        (is (= {:user-id 123} (:payload effect)))
        (is (nil? (:opts effect)))
        (is (s/valid? (nurt.effect/effect-spec effect) effect))))

    (testing "3-arity form with opts"
      (let [opts   {:job-table "custom_jobs"}
            effect (effect-async/enqueue :process-order {:order-id 456} opts)]
        (is (= :async (:effect/type effect)))
        (is (= :process-order (:queue effect)))
        (is (= {:order-id 456} (:payload effect)))
        (is (= opts (:opts effect)))
        (is (s/valid? (nurt.effect/effect-spec effect) effect))))))

(deftest enqueue-handler-test
  (testing "enqueue! effect handler"
    (testing "calls IO function with correct parameters (no opts)"
      (let [effect  {:effect/type :async
                     :queue       :send-welcome-email
                     :payload     {:user-id 123}}
            context {:db ::fake-datasource}
            calls   (atom [])]
        (with-redefs [nurt.io.async/enqueue! (fn [ctx q p o]
                                               (swap! calls conj {:ctx ctx :queue q :payload p :opts o})
                                               :enqueued)]
          (let [result (effect-async/enqueue! effect context)]
            (is (= :enqueued result))
            (is (= 1 (count @calls)))
            (let [call (first @calls)]
              (is (= context (:ctx call)))
              (is (= :send-welcome-email (:queue call)))
              (is (= {:user-id 123} (:payload call)))
              (is (nil? (:opts call))))))))

    (testing "calls IO function with opts"
      (let [opts    {:job-table "custom_jobs"}
            effect  {:effect/type :async
                     :queue       :process-order
                     :payload     {:order-id 456}
                     :opts        opts}
            context {:db ::fake-datasource}
            calls   (atom [])]
        (with-redefs [nurt.io.async/enqueue! (fn [ctx q p o]
                                               (swap! calls conj {:ctx ctx :queue q :payload p :opts o})
                                               :enqueued)]
          (effect-async/enqueue! effect context)
          (let [call (first @calls)]
            (is (= opts (:opts call)))))))

    (testing "propagates IO exceptions"
      (let [effect  {:effect/type :async
                     :queue       :failing-job
                     :payload     {}}
            context {:db ::fake-datasource}
            error   (ex-info "DB connection lost" {})]
        (with-redefs [nurt.io.async/enqueue! (fn [& _] (throw error))]
          (is (thrown-with-msg? Exception #"DB connection lost"
                                (effect-async/enqueue! effect context))))))))

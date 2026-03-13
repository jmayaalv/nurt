(ns ^:parallel nurt.io.async-test
  (:require [clojure.test :refer [deftest is testing]]
            [nurt.io.async :as io-async]))

(deftest enqueue-test
  (testing "enqueue! delegates to proletarian"
    (testing "enqueues without opts"
      (let [db      ::fake-db
            context {:db db}
            calls   (atom [])]
        (with-redefs [proletarian.job/enqueue! (fn [d q p]
                                                 (swap! calls conj {:db d :queue q :payload p})
                                                 :ok)]
          (let [result (io-async/enqueue! context :send-email {:to "user@example.com"} nil)]
            (is (= :ok result))
            (is (= 1 (count @calls)))
            (let [call (first @calls)]
              (is (= db (:db call)))
              (is (= :send-email (:queue call)))
              (is (= {:to "user@example.com"} (:payload call))))))))

    (testing "enqueues with opts"
      (let [db      ::fake-db
            context {:db db}
            opts    {:job-table "custom_jobs"}
            calls   (atom [])]
        (with-redefs [proletarian.job/enqueue! (fn [d q p o]
                                                 (swap! calls conj {:db d :queue q :payload p :opts o})
                                                 :ok)]
          (io-async/enqueue! context :process-order {:order-id 1} opts)
          (let [call (first @calls)]
            (is (= opts (:opts call)))))))

    (testing "uses 3-arity proletarian call when opts is nil"
      (let [three-arity-calls (atom 0)
            four-arity-calls  (atom 0)]
        (with-redefs [proletarian.job/enqueue! (fn
                                                 ([_db _q _p]
                                                  (swap! three-arity-calls inc)
                                                  :ok)
                                                 ([_db _q _p _o]
                                                  (swap! four-arity-calls inc)
                                                  :ok))]
          (io-async/enqueue! {:db ::db} :job {} nil)
          (is (= 1 @three-arity-calls))
          (is (= 0 @four-arity-calls)))))

    (testing "uses 4-arity proletarian call when opts provided"
      (let [three-arity-calls (atom 0)
            four-arity-calls  (atom 0)]
        (with-redefs [proletarian.job/enqueue! (fn
                                                 ([_db _q _p]
                                                  (swap! three-arity-calls inc)
                                                  :ok)
                                                 ([_db _q _p _o]
                                                  (swap! four-arity-calls inc)
                                                  :ok))]
          (io-async/enqueue! {:db ::db} :job {} {:job-table "t"})
          (is (= 0 @three-arity-calls))
          (is (= 1 @four-arity-calls)))))

    (testing "propagates proletarian exceptions"
      (with-redefs [proletarian.job/enqueue! (fn [& _] (throw (ex-info "Queue full" {})))]
        (is (thrown-with-msg? Exception #"Queue full"
                              (io-async/enqueue! {:db ::db} :job {} nil)))))))

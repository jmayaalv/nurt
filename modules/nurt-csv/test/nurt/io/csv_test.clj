(ns nurt.io.csv-test
  (:require [clojure.test :refer [deftest is testing]]
            [nurt.io.csv :as io-csv]
            [clojure.java.io :as io]
            [charred.api :as charred])
  (:import [java.io StringWriter ByteArrayOutputStream]))

(deftest csv-write-to-file-test
  (testing "write! function with file output"
    (testing "writes basic CSV data to file"
      (let [temp-file (java.io.File/createTempFile "test" ".csv")
            path (.getAbsolutePath temp-file)
            data [{:name "John" :age 30} {:name "Jane" :age 25}]
            written-calls (atom [])]

        (with-redefs [charred/write-csv (fn [path data-arg opts]
                                               (swap! written-calls conj
                                                      {:path path :data data-arg :opts opts}))]
          (io-csv/write! {} {:data data
                             :path path
                             :options {:headers true}})

          (let [call (first @written-calls)]
            (is (= path (:path call)))
            (is (= data (:data call)))
            (is (= {:headers true} (:opts call)))))

        (.delete temp-file)))

    (testing "merges default options with provided options"
      (let [temp-file (java.io.File/createTempFile "test" ".csv")
            path (.getAbsolutePath temp-file)
            written-calls (atom [])]

        (with-redefs [charred/write-csv (fn [path data opts]
                                               (swap! written-calls conj opts))]
          (io-csv/write! {} {:data [{:a 1}]
                             :path path
                             :options {:separator \|}})

          (let [opts (first @written-calls)]
            (is (= true (:headers opts)))
            (is (= \| (:separator opts)))))

        (.delete temp-file)))))

(deftest csv-write-to-stream-test
  (testing "write! function with stream output"
    (testing "writes CSV data to output stream"
      (let [output-stream (ByteArrayOutputStream.)
            data [{:id 1 :value "A"} {:id 2 :value "B"}]
            written-calls (atom [])]

        (with-redefs [charred/write-csv (fn [stream data-arg opts]
                                               (swap! written-calls conj
                                                      {:stream stream :data data-arg :opts opts}))]
          (io-csv/write! {} {:data data
                             :output-stream output-stream
                             :options {:headers false}})

          (let [call (first @written-calls)]
            (is (= output-stream (:stream call)))
            (is (= data (:data call)))
            (is (= {:headers false} (:opts call)))))))

    (testing "uses default options when none provided"
      (let [output-stream (ByteArrayOutputStream.)
            written-calls (atom [])]

        (with-redefs [charred/write-csv (fn [stream data opts]
                                               (swap! written-calls conj opts))]
          (io-csv/write! {} {:data [{:test true}]
                             :output-stream output-stream})

          (let [opts (first @written-calls)]
            (is (= {:headers true} opts))))))))

(deftest csv-error-handling-test
  (testing "write! function error scenarios"
    (testing "throws exception when neither path nor output-stream provided"
      (is (thrown-with-msg? Exception #"Must provide either :path or :output-stream"
                            (io-csv/write! {} {:data [{:a 1}]}))))

    (testing "handles empty data gracefully"
      (let [temp-file (java.io.File/createTempFile "test" ".csv")
            path (.getAbsolutePath temp-file)
            written-calls (atom [])]

        (with-redefs [charred/write-csv (fn [path data opts]
                                               (swap! written-calls conj data))]
          (io-csv/write! {} {:data []
                             :path path})

          (is (= [[]] @written-calls)))

        (.delete temp-file)))))

(deftest csv-options-handling-test
  (testing "CSV options processing"
    (testing "handles various CSV formatting options"
      (let [temp-file (java.io.File/createTempFile "test" ".csv")
            path (.getAbsolutePath temp-file)
            written-calls (atom [])]

        (with-redefs [charred/write-csv (fn [path data opts]
                                               (swap! written-calls conj opts))]
          (io-csv/write! {} {:data [{:a 1}]
                             :path path
                             :options {:headers false
                                       :separator \|
                                       :quote-char \'
                                       :escape-char \\}})

          (let [opts (first @written-calls)]
            (is (= false (:headers opts)))
            (is (= \| (:separator opts)))
            (is (= \' (:quote-char opts)))
            (is (= \\ (:escape-char opts)))))

        (.delete temp-file)))

    (testing "defaults headers to true when not specified"
      (let [temp-file (java.io.File/createTempFile "test" ".csv")
            path (.getAbsolutePath temp-file)
            written-calls (atom [])]

        (with-redefs [charred/write-csv (fn [path data opts]
                                               (swap! written-calls conj opts))]
          (io-csv/write! {} {:data [{:a 1}]
                             :path path
                             :options {:separator \;}})

          (let [opts (first @written-calls)]
            (is (= true (:headers opts)))))

        (.delete temp-file)))))

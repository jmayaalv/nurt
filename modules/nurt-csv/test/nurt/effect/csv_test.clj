(ns ^:parallel nurt.effect.csv-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [nurt.effect :as nurt.effect]
            [nurt.effect.csv :as effect-csv]))

(deftest csv-effect-spec-test
  (testing "CSV effect specification validation"
    (testing "validates valid CSV effect"
      (let [valid-effect {:effect/type :csv
                          :csv-data {:data [{:name "John" :age 30}]
                                     :path "/tmp/output.csv"}}]
        (is (s/valid? (nurt.effect/effect-spec valid-effect) valid-effect))))
    
    (testing "validates CSV effect with output stream"
      (let [valid-effect {:effect/type :csv
                          :csv-data {:data [{:id 1 :value "A"}]
                                     :output-stream (java.io.ByteArrayOutputStream.)}}]
        (is (s/valid? (nurt.effect/effect-spec valid-effect) valid-effect))))
    
    (testing "validates CSV effect with options"
      (let [valid-effect {:effect/type :csv
                          :csv-data {:data [{:a 1 :b 2}]
                                     :path "/tmp/test.csv"
                                     :options {:headers false :separator \|}}}]
        (is (s/valid? (nurt.effect/effect-spec valid-effect) valid-effect))))
    
    (testing "rejects invalid CSV effects"
      (testing "missing effect type"
        (let [invalid-effect {:csv-data {:data [] :path "/tmp/test.csv"}}]
          (is (thrown? Exception (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect)))))
      
      (testing "missing csv-data"
        (let [invalid-effect {:effect/type :csv}]
          (is (not (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect)))))
      
      (testing "missing data"
        (let [invalid-effect {:effect/type :csv
                              :csv-data {:path "/tmp/test.csv"}}]
          (is (not (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect)))))
      
      (testing "missing both path and output-stream"
        (let [invalid-effect {:effect/type :csv
                              :csv-data {:data [{:a 1}]}}]
          (is (not (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect)))))
      
      (testing "invalid data type"
        (let [invalid-effect {:effect/type :csv
                              :csv-data {:data "not-a-collection"
                                         :path "/tmp/test.csv"}}]
          (is (not (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect)))))
      
      (testing "invalid path type"
        (let [invalid-effect {:effect/type :csv
                              :csv-data {:data []
                                         :path 123}}]
          (is (not (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect)))))
      
      (testing "invalid options type"
        (let [invalid-effect {:effect/type :csv
                              :csv-data {:data []
                                         :path "/tmp/test.csv"
                                         :options "not-a-map"}}]
          (is (not (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect))))))))

(deftest csv-effect-data-spec-test
  (testing "CSV data specification validation"
    (testing "validates with file path"
      (let [csv-data {:data [{:name "Alice" :age 25}]
                      :path "/home/user/export.csv"}]
        (is (s/valid? ::effect-csv/csv-data csv-data))))
    
    (testing "validates with output stream"
      (let [csv-data {:data [{:id 1}]
                      :output-stream (java.io.StringWriter.)}]
        (is (s/valid? ::effect-csv/csv-data csv-data))))
    
    (testing "validates with both file path and output stream"
      (let [csv-data {:data []
                      :path "/tmp/test.csv"
                      :output-stream (java.io.StringWriter.)}]
        (is (s/valid? ::effect-csv/csv-data csv-data))))
    
    (testing "validates empty data"
      (let [csv-data {:data []
                      :path "/tmp/empty.csv"}]
        (is (s/valid? ::effect-csv/csv-data csv-data))))
    
    (testing "validates with various data types"
      (let [csv-data {:data [{:string "text" :number 42 :boolean true :nil nil}]
                      :path "/tmp/mixed.csv"}]
        (is (s/valid? ::effect-csv/csv-data csv-data))))
    
    (testing "rejects missing required output destination"
      (let [csv-data {:data [{:a 1}]}]
        (is (not (s/valid? ::effect-csv/csv-data csv-data)))))
    
    (testing "rejects invalid data types"
      (let [csv-data {:data "not-a-collection"
                      :path "/tmp/test.csv"}]
        (is (not (s/valid? ::effect-csv/csv-data csv-data)))))))

(deftest csv-options-spec-test
  (testing "CSV options specification validation"
    (testing "validates empty options"
      (is (s/valid? ::effect-csv/options {})))
    
    (testing "validates headers option"
      (is (s/valid? ::effect-csv/options {:headers true}))
      (is (s/valid? ::effect-csv/options {:headers false})))
    
    (testing "validates separator option"
      (is (s/valid? ::effect-csv/options {:separator \,}))
      (is (s/valid? ::effect-csv/options {:separator \|}))
      (is (s/valid? ::effect-csv/options {:separator \tab})))
    
    (testing "validates quote-char option"
      (is (s/valid? ::effect-csv/options {:quote-char \"}))
      (is (s/valid? ::effect-csv/options {:quote-char \'})))
    
    (testing "validates escape-char option"
      (is (s/valid? ::effect-csv/options {:escape-char \\}))
      (is (s/valid? ::effect-csv/options {:escape-char \/})))
    
    (testing "validates combination of options"
      (is (s/valid? ::effect-csv/options {:headers false
                                          :separator \|
                                          :quote-char \'
                                          :escape-char \\})))
    
    (testing "rejects invalid option types"
      (is (not (s/valid? ::effect-csv/options {:headers "true"})))
      (is (not (s/valid? ::effect-csv/options {:separator "comma"})))
      (is (not (s/valid? ::effect-csv/options {:quote-char "quote"})))
      (is (not (s/valid? ::effect-csv/options {:escape-char "escape"}))))))

(deftest csv-function-test
  (testing "csv function creates valid effects"
    (testing "creates basic CSV effect with file path"
      (let [csv-data {:data [{:name "John" :age 30}]
                      :path "/tmp/users.csv"}
            effect (effect-csv/csv csv-data)]
        
        (is (= :csv (:effect/type effect)))
        (is (= csv-data (:csv-data effect)))
        (is (s/valid? (nurt.effect/effect-spec effect) effect))))
    
    (testing "creates CSV effect with output stream"
      (let [output-stream (java.io.ByteArrayOutputStream.)
            csv-data {:data [{:id 1 :value "A"}]
                      :output-stream output-stream}
            effect (effect-csv/csv csv-data)]
        
        (is (= :csv (:effect/type effect)))
        (is (= csv-data (:csv-data effect)))
        (is (s/valid? (nurt.effect/effect-spec effect) effect))))
    
    (testing "creates CSV effect with options"
      (let [csv-data {:data [{:a 1 :b 2}]
                      :path "/tmp/data.csv"
                      :options {:headers false :separator \|}}
            effect (effect-csv/csv csv-data)]
        
        (is (= :csv (:effect/type effect)))
        (is (= csv-data (:csv-data effect)))
        (is (s/valid? (nurt.effect/effect-spec effect) effect))))
    
    (testing "creates CSV effect with complex data"
      (let [complex-data [{:name "Alice" :details {:age 30 :city "NYC"}}
                          {:name "Bob" :details {:age 25 :city "SF"}}]
            csv-data {:data complex-data
                      :path "/tmp/complex.csv"
                      :options {:headers true}}
            effect (effect-csv/csv csv-data)]
        
        (is (= :csv (:effect/type effect)))
        (is (= csv-data (:csv-data effect)))
        (is (s/valid? (nurt.effect/effect-spec effect) effect))))))

(deftest csv-effect-handler-test
  (testing "csv! effect handler function"
    (testing "calls IO function with correct parameters"
      (let [csv-data {:data [{:name "Test" :value 123}]
                      :path "/tmp/test.csv"
                      :options {:headers true}}
            effect {:effect/type :csv :csv-data csv-data}
            context {:some "context"}
            io-calls (atom [])]
        
        (with-redefs [nurt.io.csv/write! (fn [ctx data]
                                           (swap! io-calls conj {:context ctx :data data})
                                           "write-result")]
          (let [result (effect-csv/csv! effect context)]
            
            (is (= "write-result" result))
            (is (= 1 (count @io-calls)))
            (let [call (first @io-calls)]
              (is (= context (:context call)))
              (is (= csv-data (:data call))))))))
    
    (testing "passes through IO function return value"
      (let [effect {:effect/type :csv
                    :csv-data {:data [] :path "/tmp/empty.csv"}}
            expected-result {:bytes-written 42}]
        
        (with-redefs [nurt.io.csv/write! (constantly expected-result)]
          (let [result (effect-csv/csv! effect {})]
            (is (= expected-result result))))))
    
    (testing "propagates IO function exceptions"
      (let [effect {:effect/type :csv
                    :csv-data {:data [] :path "/invalid/path.csv"}}
            expected-error (ex-info "Write failed" {})]
        
        (with-redefs [nurt.io.csv/write! (fn [_ _] (throw expected-error))]
          (is (thrown-with-msg? Exception #"Write failed"
                                (effect-csv/csv! effect {}))))))))
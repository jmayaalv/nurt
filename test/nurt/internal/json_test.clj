(ns ^:parallel nurt.internal.json-test
  (:require [clojure.test :refer [deftest is testing]]
            [nurt.internal.json :as json]))

(deftest json-write-str-test
  (testing "JSON write-str function"
    (testing "serializes simple maps"
      (is (= "{\"user-id\":123}" (json/write-str {:user-id 123}))))
    
    (testing "serializes nested structures"
      (let [data {:user {:id 123 :name "John"}
                  :orders [{:id 1 :total 99.99}]}
            result (json/write-str data)]
        (is (string? result))
        (is (.contains result "\"user\""))
        (is (.contains result "\"orders\""))))))

(deftest json-read-str-test
  (testing "JSON read-str function"
    (testing "parses simple JSON"
      (is (= {"user-id" 123} (json/read-str "{\"user-id\":123}"))))
    
    (testing "parses JSON with keyword keys"
      (is (= {:user-id 123} (json/read-str "{\"user-id\":123}" :key-fn keyword))))
    
    (testing "parses nested JSON with keyword keys"
      (let [json-str "{\"user\":{\"id\":123,\"name\":\"John\"}}"
            result (json/read-str json-str :key-fn keyword)]
        (is (= {:user {:id 123 :name "John"}} result))))))
(ns ^:parallel nurt.io.http-test
  (:require [clojure.test :refer [deftest is testing]]
            [nurt.io.http :as io-http]
            [nurt.internal.json :as json]))

(deftest process-request-body-test
  (testing "JSON serialization of request bodies"
    (testing "serializes maps to JSON when Content-Type is application/json"
      (let [request {:method :post
                     :url "https://api.example.com/users"
                     :headers {"Content-Type" "application/json"}
                     :body {:name "John" :email "john@example.com"}}]
        (with-redefs [hato.client/request (fn [req]
                                            {:processed-request req})]
          (let [result (io-http/request! {} request)
                processed-req (:processed-request result)]
            (is (string? (:body processed-req)))
            (is (.contains (:body processed-req) "\"name\""))
            (is (.contains (:body processed-req) "\"John\""))
            (is (.contains (:body processed-req) "\"email\""))
            (is (= "application/json" (get-in processed-req [:headers "Content-Type"])))))))
    
    (testing "serializes maps to JSON when no Content-Type is specified"
      (let [request {:method :post
                     :url "https://api.example.com/users"
                     :body {:user-id 123 :active true}}]
        (with-redefs [hato.client/request (fn [req]
                                            {:processed-request req})]
          (let [result (io-http/request! {} request)
                processed-req (:processed-request result)]
            (is (string? (:body processed-req)))
            (is (.contains (:body processed-req) "\"user-id\""))
            (is (.contains (:body processed-req) "123"))
            (is (= "application/json" (get-in processed-req [:headers "Content-Type"])))))))
    
    (testing "serializes vectors to JSON"
      (let [request {:method :post
                     :url "https://api.example.com/batch"
                     :body [{:id 1 :name "John"} {:id 2 :name "Jane"}]}]
        (with-redefs [hato.client/request (fn [req]
                                            {:processed-request req})]
          (let [result (io-http/request! {} request)
                processed-req (:processed-request result)]
            (is (string? (:body processed-req)))
            (is (.startsWith (:body processed-req) "["))
            (is (.endsWith (:body processed-req) "]"))
            (is (.contains (:body processed-req) "\"name\""))
            (is (= "application/json" (get-in processed-req [:headers "Content-Type"])))))))
    
    (testing "preserves string bodies unchanged"
      (let [json-string "{\"custom\": \"json\"}"
            request {:method :post
                     :url "https://api.example.com/custom"
                     :headers {"Content-Type" "application/json"}
                     :body json-string}]
        (with-redefs [hato.client/request (fn [req]
                                            {:processed-request req})]
          (let [result (io-http/request! {} request)
                processed-req (:processed-request result)]
            (is (= json-string (:body processed-req)))
            (is (= "application/json" (get-in processed-req [:headers "Content-Type"])))))))
    
    (testing "preserves maps when Content-Type is not JSON"
      (let [request {:method :post
                     :url "https://api.example.com/form"
                     :headers {"Content-Type" "application/x-www-form-urlencoded"}
                     :body {:username "user" :password "pass"}}]
        (with-redefs [hato.client/request (fn [req]
                                            {:processed-request req})]
          (let [result (io-http/request! {} request)
                processed-req (:processed-request result)]
            (is (map? (:body processed-req)))
            (is (= {:username "user" :password "pass"} (:body processed-req)))
            (is (= "application/x-www-form-urlencoded" 
                   (get-in processed-req [:headers "Content-Type"])))))))
    
    (testing "handles case-insensitive Content-Type headers"
      (let [request {:method :post
                     :url "https://api.example.com/users"
                     :headers {"content-type" "application/json"}
                     :body {:name "John"}}]
        (with-redefs [hato.client/request (fn [req]
                                            {:processed-request req})]
          (let [result (io-http/request! {} request)
                processed-req (:processed-request result)]
            (is (string? (:body processed-req)))
            (is (.contains (:body processed-req) "\"name\""))))))
    
    (testing "handles requests without body"
      (let [request {:method :get
                     :url "https://api.example.com/users"}]
        (with-redefs [hato.client/request (fn [req]
                                            {:processed-request req})]
          (let [result (io-http/request! {} request)
                processed-req (:processed-request result)]
            (is (nil? (:body processed-req)))))))))

(deftest request-context-handling-test
  (testing "HTTP client context handling"
    (testing "merges http-client config from context"
      (let [request {:method :get :url "https://example.com"}
            context {:http-client {:connect-timeout 3000 :user-agent "test"}}]
        (with-redefs [hato.client/request (fn [req]
                                            {:processed-request req})]
          (let [result (io-http/request! context request)
                processed-req (:processed-request result)]
            (is (= 3000 (get-in processed-req [:http-client :connect-timeout])))
            (is (= "test" (get-in processed-req [:http-client :user-agent])))))))
    
    (testing "works without http-client config"
      (let [request {:method :get :url "https://example.com"}
            context {}]
        (with-redefs [hato.client/request (fn [req]
                                            {:processed-request req})]
          (let [result (io-http/request! context request)
                processed-req (:processed-request result)]
            (is (nil? (:http-client processed-req))))))))

(deftest response-processing-test
  (testing "JSON response parsing"
    (testing "parses JSON response with default string keys"
      (let [request {:method :get :url "https://api.example.com/users"}
            json-response {:status 200 
                          :headers {"content-type" "application/json"} 
                          :body "{\"user-id\":123,\"name\":\"John\"}"}]
        (with-redefs [hato.client/request (fn [_] json-response)]
          (let [result (io-http/request! {} request)]
            (is (= 200 (:status result)))
            (is (map? (:body result)))
            (is (= {"user-id" 123 "name" "John"} (:body result)))))))
    
    (testing "parses JSON response with keyword keys"
      (let [request {:method :get :url "https://api.example.com/users" :key-fn keyword}
            json-response {:status 200 
                          :headers {"content-type" "application/json"} 
                          :body "{\"user-id\":123,\"name\":\"John\"}"}]
        (with-redefs [hato.client/request (fn [_] json-response)]
          (let [result (io-http/request! {} request)]
            (is (= 200 (:status result)))
            (is (map? (:body result)))
            (is (= {:user-id 123 :name "John"} (:body result)))))))
    
    (testing "handles nested JSON structures"
      (let [request {:method :get :url "https://api.example.com/users" :key-fn keyword}
            json-response {:status 200 
                          :headers {"content-type" "application/json"} 
                          :body "{\"user\":{\"id\":123,\"name\":\"John\"},\"orders\":[{\"id\":1}]}"}]
        (with-redefs [hato.client/request (fn [_] json-response)]
          (let [result (io-http/request! {} request)]
            (is (= {:user {:id 123 :name "John"} :orders [{:id 1}]} (:body result)))))))
    
    (testing "skips parsing when parse-json? is false"
      (let [request {:method :get :url "https://api.example.com/users" :parse-json? false}
            json-response {:status 200 
                          :headers {"content-type" "application/json"} 
                          :body "{\"user-id\":123}"}]
        (with-redefs [hato.client/request (fn [_] json-response)]
          (let [result (io-http/request! {} request)]
            (is (string? (:body result)))
            (is (= "{\"user-id\":123}" (:body result)))))))
    
    (testing "skips parsing for non-JSON content types"
      (let [request {:method :get :url "https://api.example.com/data"}
            response {:status 200 
                     :headers {"content-type" "text/plain"} 
                     :body "{\"user-id\":123}"}]
        (with-redefs [hato.client/request (fn [_] response)]
          (let [result (io-http/request! {} request)]
            (is (string? (:body result)))
            (is (= "{\"user-id\":123}" (:body result)))))))
    
    (testing "handles malformed JSON gracefully"
      (let [request {:method :get :url "https://api.example.com/users"}
            bad-json-response {:status 200 
                              :headers {"content-type" "application/json"} 
                              :body "{invalid json}"}]
        (with-redefs [hato.client/request (fn [_] bad-json-response)]
          (let [result (io-http/request! {} request)]
            (is (string? (:body result)))
            (is (= "{invalid json}" (:body result)))))))
    
    (testing "handles case-insensitive content-type headers"
      (let [request {:method :get :url "https://api.example.com/users"}
            json-response {:status 200 
                          :headers {"Content-Type" "application/json"} 
                          :body "{\"user-id\":123}"}]
        (with-redefs [hato.client/request (fn [_] json-response)]
          (let [result (io-http/request! {} request)]
            (is (map? (:body result)))
            (is (= {"user-id" 123} (:body result))))))))

(deftest json-utility-functions-test
  (testing "parse-json function"
    (testing "parses JSON with default string keys"
      (is (= {"user-id" 123} (io-http/parse-json "{\"user-id\":123}"))))
    
    (testing "parses JSON with keyword keys"
      (is (= {:user-id 123} (io-http/parse-json "{\"user-id\":123}" {:key-fn keyword}))))
    
    (testing "handles nested structures"
      (let [json-str "{\"user\":{\"id\":123,\"name\":\"John\"}}"
            result (io-http/parse-json json-str {:key-fn keyword})]
        (is (= {:user {:id 123 :name "John"}} result)))))
  
  (testing "generate-json function"
    (testing "generates JSON from map"
      (let [result (io-http/generate-json {:user-id 123 :name "John"})]
        (is (string? result))
        (is (.contains result "\"user-id\""))
        (is (.contains result "123"))
        (is (.contains result "\"name\""))
        (is (.contains result "\"John\""))))
    
    (testing "generates JSON from vector"
      (let [result (io-http/generate-json [{:id 1} {:id 2}])]
        (is (string? result))
        (is (.startsWith result "["))
        (is (.endsWith result "]"))
        (is (.contains result "\"id\""))))))

(deftest integration-test
  (testing "Full request processing with JSON"
    (testing "POST request with map body gets proper JSON serialization"
      (let [request {:method :post
                     :url "https://api.example.com/users"
                     :body {:name "Alice" :age 30 :active true}
                     :headers {"Authorization" "Bearer token123"}}
            context {:http-client {:timeout 5000}}
            expected-response {:status 201 :body {:id 456}}]
        
        (with-redefs [hato.client/request (fn [processed-req]
                                            ;; Verify the request was processed correctly
                                            (is (string? (:body processed-req)))
                                            (is (.contains (:body processed-req) "\"name\""))
                                            (is (.contains (:body processed-req) "\"Alice\""))
                                            (is (.contains (:body processed-req) "30"))
                                            (is (= "application/json" 
                                                   (get-in processed-req [:headers "Content-Type"])))
                                            (is (= "Bearer token123" 
                                                   (get-in processed-req [:headers "Authorization"])))
                                            (is (= 5000 (get-in processed-req [:http-client :timeout])))
                                            expected-response)]
          (let [result (io-http/request! context request)]
            (is (= expected-response result))))))
    
    (testing "GET request with JSON response parsing"
      (let [request {:method :get 
                     :url "https://api.example.com/users/123"
                     :key-fn keyword}
            context {}
            json-response {:status 200
                          :headers {"content-type" "application/json"}
                          :body "{\"user\":{\"id\":123,\"name\":\"Alice\",\"email\":\"alice@example.com\"}}"}]
        
        (with-redefs [hato.client/request (fn [_] json-response)]
          (let [result (io-http/request! context request)]
            (is (= 200 (:status result)))
            (is (map? (:body result)))
            (is (= {:user {:id 123 :name "Alice" :email "alice@example.com"}} (:body result)))))))))))

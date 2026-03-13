(ns ^:parallel nurt.effect.http-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [nurt.effect :as nurt.effect]
            [nurt.effect.http :as effect-http]))

(deftest http-effect-spec-test
  (testing "HTTP effect specification validation"
    (testing "validates valid HTTP effects"
      (testing "basic GET request"
        (let [valid-effect {:effect/type :http
                            :http-request {:method :get
                                           :url "https://api.example.com/users"}}]
          (is (s/valid? (nurt.effect/effect-spec valid-effect) valid-effect))))
      
      (testing "POST request with body"
        (let [valid-effect {:effect/type :http
                            :http-request {:method :post
                                           :url "https://api.example.com/users"
                                           :headers {"Content-Type" "application/json"}
                                           :body "{\"name\": \"John\"}"}}]
          (is (s/valid? (nurt.effect/effect-spec valid-effect) valid-effect))))
      
      (testing "request with all optional parameters"
        (let [valid-effect {:effect/type :http
                            :http-request {:method :put
                                           :url "https://api.example.com/users/123"
                                           :headers {"Authorization" "Bearer token"}
                                           :query-params {:include "profile"}
                                           :body "{\"name\": \"Updated\"}"
                                           :form-params {:field "value"}
                                           :as :json
                                           :timeout 5000
                                           :throw-exceptions false}}]
          (is (s/valid? (nurt.effect/effect-spec valid-effect) valid-effect)))))
    
    (testing "rejects invalid HTTP effects"
      (testing "missing effect type"
        (let [invalid-effect {:http-request {:method :get :url "http://example.com"}}]
          (is (thrown? Exception (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect)))))
      
      (testing "missing http-request"
        (let [invalid-effect {:effect/type :http}]
          (is (not (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect)))))
      
      (testing "missing method"
        (let [invalid-effect {:effect/type :http
                              :http-request {:url "http://example.com"}}]
          (is (not (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect)))))
      
      (testing "missing url"
        (let [invalid-effect {:effect/type :http
                              :http-request {:method :get}}]
          (is (not (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect)))))
      
      (testing "invalid method"
        (let [invalid-effect {:effect/type :http
                              :http-request {:method :invalid-method
                                             :url "http://example.com"}}]
          (is (not (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect)))))
      
      (testing "invalid url type"
        (let [invalid-effect {:effect/type :http
                              :http-request {:method :get
                                             :url 123}}]
          (is (not (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect))))))))

(deftest http-request-spec-test
  (testing "HTTP request specification validation"
    (testing "validates required fields"
      (let [minimal-request {:method :get :url "https://example.com"}]
        (is (s/valid? ::effect-http/http-request minimal-request))))
    
    (testing "validates all HTTP methods"
      (doseq [method [:get :post :put :delete :patch :head :options]]
        (let [request {:method method :url "https://example.com"}]
          (is (s/valid? ::effect-http/http-request request)))))
    
    (testing "validates headers"
      (let [request {:method :get
                     :url "https://example.com"
                     :headers {"Content-Type" "application/json"
                               "Authorization" "Bearer token"}}]
        (is (s/valid? ::effect-http/http-request request))))
    
    (testing "validates query parameters"
      (let [request {:method :get
                     :url "https://example.com"
                     :query-params {:q "search" :limit 10 :sort "name"}}]
        (is (s/valid? ::effect-http/http-request request))))
    
    (testing "validates form parameters"
      (let [request {:method :post
                     :url "https://example.com"
                     :form-params {:username "user" :password "pass"}}]
        (is (s/valid? ::effect-http/http-request request))))
    
    (testing "validates response format options"
      (doseq [format [:json :text :stream :auto :bytes]]
        (let [request {:method :get :url "https://example.com" :as format}]
          (is (s/valid? ::effect-http/http-request request)))))
    
    (testing "validates timeout"
      (let [request {:method :get :url "https://example.com" :timeout 5000}]
        (is (s/valid? ::effect-http/http-request request))))
    
    (testing "validates throw-exceptions flag"
      (let [request {:method :get :url "https://example.com" :throw-exceptions false}]
        (is (s/valid? ::effect-http/http-request request))))
    
    (testing "validates parse-json? flag"
      (let [request {:method :get :url "https://example.com" :parse-json? false}]
        (is (s/valid? ::effect-http/http-request request))))
    
    (testing "validates key-fn function"
      (let [request {:method :get :url "https://example.com" :key-fn keyword}]
        (is (s/valid? ::effect-http/http-request request))))
    
    (testing "rejects invalid field types"
      (testing "invalid headers"
        (let [request {:method :get :url "https://example.com" :headers "not-a-map"}]
          (is (not (s/valid? ::effect-http/http-request request)))))
      
      (testing "invalid query-params"
        (let [request {:method :get :url "https://example.com" :query-params "not-a-map"}]
          (is (not (s/valid? ::effect-http/http-request request)))))
      
      (testing "invalid as format"
        (let [request {:method :get :url "https://example.com" :as :invalid}]
          (is (not (s/valid? ::effect-http/http-request request)))))
      
      (testing "invalid timeout"
        (let [request {:method :get :url "https://example.com" :timeout -1}]
          (is (not (s/valid? ::effect-http/http-request request)))))
      
      (testing "invalid throw-exceptions"
        (let [request {:method :get :url "https://example.com" :throw-exceptions "true"}]
          (is (not (s/valid? ::effect-http/http-request request)))))
      
      (testing "invalid parse-json?"
        (let [request {:method :get :url "https://example.com" :parse-json? "false"}]
          (is (not (s/valid? ::effect-http/http-request request)))))
      
      (testing "invalid key-fn"
        (let [request {:method :get :url "https://example.com" :key-fn "not-a-function"}]
          (is (not (s/valid? ::effect-http/http-request request))))))))

(deftest http-function-test
  (testing "http function creates valid effects"
    (testing "creates basic GET effect"
      (let [request {:method :get :url "https://api.example.com/users"}
            effect (effect-http/http request)]
        
        (is (= :http (:effect/type effect)))
        (is (= request (:http-request effect)))
        (is (s/valid? (nurt.effect/effect-spec effect) effect))))
    
    (testing "creates POST effect with body"
      (let [request {:method :post
                     :url "https://api.example.com/users"
                     :headers {"Content-Type" "application/json"}
                     :body "{\"name\": \"John\", \"email\": \"john@example.com\"}"}
            effect (effect-http/http request)]
        
        (is (= :http (:effect/type effect)))
        (is (= request (:http-request effect)))
        (is (s/valid? (nurt.effect/effect-spec effect) effect))))
    
    (testing "creates effect with query parameters"
      (let [request {:method :get
                     :url "https://api.example.com/search"
                     :query-params {:q "clojure" :limit 50 :sort "relevance"}}
            effect (effect-http/http request)]
        
        (is (= :http (:effect/type effect)))
        (is (= request (:http-request effect)))
        (is (s/valid? (nurt.effect/effect-spec effect) effect))))
    
    (testing "creates effect with all options"
      (let [request {:method :put
                     :url "https://api.example.com/users/123"
                     :headers {"Authorization" "Bearer abc123"
                               "Content-Type" "application/json"}
                     :query-params {:version 2}
                     :body "{\"name\": \"Updated Name\"}"
                     :as :json
                     :timeout 10000
                     :throw-exceptions false}
            effect (effect-http/http request)]
        
        (is (= :http (:effect/type effect)))
        (is (= request (:http-request effect)))
        (is (s/valid? (nurt.effect/effect-spec effect) effect))))
    
    (testing "creates effect with form parameters"
      (let [request {:method :post
                     :url "https://api.example.com/login"
                     :form-params {:username "user@example.com"
                                   :password "secret123"}
                     :timeout 5000}
            effect (effect-http/http request)]
        
        (is (= :http (:effect/type effect)))
        (is (= request (:http-request effect)))
        (is (s/valid? (nurt.effect/effect-spec effect) effect))))))

(deftest http-effect-handler-test
  (testing "http! effect handler function"
    (testing "calls IO function with correct parameters"
      (let [http-request {:method :get
                          :url "https://api.example.com/users"
                          :headers {"Authorization" "Bearer token"}}
            effect {:effect/type :http :http-request http-request}
            context {:http-client {:connect-timeout 3000}}
            io-calls (atom [])]
        
        (with-redefs [nurt.io.http/request! (fn [ctx req]
                                              (swap! io-calls conj {:context ctx :request req})
                                              {:status 200 :body []})]
          (let [result (effect-http/http! effect context)]
            
            (is (= {:status 200 :body []} result))
            (is (= 1 (count @io-calls)))
            (let [call (first @io-calls)]
              (is (= context (:context call)))
              (is (= http-request (:request call))))))))
    
    (testing "passes through IO function return value"
      (let [effect {:effect/type :http
                    :http-request {:method :post :url "https://api.example.com/data"}}
            expected-response {:status 201
                               :body {:id 123 :created true}
                               :headers {"Content-Type" "application/json"}}]
        
        (with-redefs [nurt.io.http/request! (constantly expected-response)]
          (let [result (effect-http/http! effect {})]
            (is (= expected-response result))))))
    
    (testing "propagates IO function exceptions"
      (let [effect {:effect/type :http
                    :http-request {:method :get :url "https://invalid.example.com"}}
            expected-error (ex-info "Connection timeout" {:type :timeout})]
        
        (with-redefs [nurt.io.http/request! (fn [_ _] (throw expected-error))]
          (is (thrown-with-msg? Exception #"Connection timeout"
                                (effect-http/http! effect {}))))))
    
    (testing "handles different HTTP methods"
      (let [methods [:get :post :put :delete :patch :head :options]
            requests (atom [])]
        
        (with-redefs [nurt.io.http/request! (fn [ctx req]
                                              (swap! requests conj (:method req))
                                              {:status 200})]
          (doseq [method methods]
            (let [effect {:effect/type :http
                          :http-request {:method method :url "https://example.com"}}]
              (effect-http/http! effect {})))
          
          (is (= methods @requests)))))
    
    (testing "handles JSON serialization in request body"
      (let [request-data {:name "John" :email "john@example.com" :age 30}
            effect {:effect/type :http
                    :http-request {:method :post
                                   :url "https://api.example.com/users"
                                   :body request-data
                                   :as :json}}
            captured-requests (atom [])]
        
        (with-redefs [nurt.io.http/request! (fn [ctx req]
                                              (swap! captured-requests conj req)
                                              {:status 201 :body {:id 123}})]
          (let [result (effect-http/http! effect {})]
            (is (= {:status 201 :body {:id 123}} result))
            (is (= 1 (count @captured-requests)))
            (let [captured-req (first @captured-requests)]
              (is (= request-data (:body captured-req))))))))
    
    (testing "handles complex request with all parameters"
      (let [complex-request {:method :post
                             :url "https://api.example.com/complex"
                             :headers {"Content-Type" "application/json"
                                       "X-API-Key" "secret"}
                             :query-params {:version 1 :format "json"}
                             :body "{\"data\": \"complex\"}"
                             :as :json
                             :timeout 15000
                             :throw-exceptions false}
            effect {:effect/type :http :http-request complex-request}
            context {:http-client {:user-agent "test-client"}}
            captured-calls (atom [])]
        
        (with-redefs [nurt.io.http/request! (fn [ctx req]
                                              (swap! captured-calls conj {:ctx ctx :req req})
                                              {:status 200 :body {:success true}})]
          (let [result (effect-http/http! effect context)]
            
            (is (= {:status 200 :body {:success true}} result))
            (is (= 1 (count @captured-calls)))
            (let [call (first @captured-calls)]
              (is (= context (:ctx call)))
              (is (= complex-request (:req call))))))))

(deftest http-json-options-test
  (testing "HTTP effect with JSON-related options"
    (testing "validates effects with parse-json? option"
      (let [request {:method :get 
                     :url "https://api.example.com/users"
                     :parse-json? false}
            effect (effect-http/http request)]
        
        (is (= :http (:effect/type effect)))
        (is (= request (:http-request effect)))
        (is (s/valid? (nurt.effect/effect-spec effect) effect))))
    
    (testing "validates effects with key-fn option"
      (let [request {:method :get 
                     :url "https://api.example.com/users"
                     :key-fn keyword}
            effect (effect-http/http request)]
        
        (is (= :http (:effect/type effect)))
        (is (= request (:http-request effect)))
        (is (s/valid? (nurt.effect/effect-spec effect) effect))))
    
    (testing "validates effects with both JSON options"
      (let [request {:method :get 
                     :url "https://api.example.com/users"
                     :parse-json? true
                     :key-fn keyword}
            effect (effect-http/http request)]
        
        (is (= :http (:effect/type effect)))
        (is (= request (:http-request effect)))
        (is (s/valid? (nurt.effect/effect-spec effect) effect))))))))

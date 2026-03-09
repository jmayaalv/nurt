(ns ^:parallel nurt.effect.db-test
  (:require
   [clojure.spec.alpha :as s]
   [clojure.test :refer [deftest is testing]]
   [nurt.effect.db :as db]
   [next.jdbc :as next.jdbc]))

(deftest sql-statement-spec-test
  (testing "::sql-statement spec validation"
    (testing "accepts vector statements with SQL and params"
      (is (s/valid? ::db/sql-statement ["SELECT * FROM users WHERE id = ?" 1]))
      (is (s/valid? ::db/sql-statement ["INSERT INTO users (name) VALUES (?)" "John"]))
      (is (s/valid? ::db/sql-statement ["UPDATE users SET name = ? WHERE id = ?" "Jane" 2])))
    
    (testing "accepts string statements"
      (is (s/valid? ::db/sql-statement "SELECT * FROM users"))
      (is (s/valid? ::db/sql-statement "DELETE FROM users WHERE id = 1")))
    
    (testing "rejects invalid formats"
      (is (not (s/valid? ::db/sql-statement [123])))
      (is (not (s/valid? ::db/sql-statement [])))
      (is (not (s/valid? ::db/sql-statement {:sql "SELECT 1"}))))))

(deftest statements-spec-test
  (testing "::statements spec validation"
    (testing "accepts collections of valid statements"
      (is (s/valid? ::db/statements [["SELECT * FROM users WHERE id = ?" 1]]))
      (is (s/valid? ::db/statements ["SELECT 1" "SELECT 2"]))
      (is (s/valid? ::db/statements [["INSERT INTO users (name) VALUES (?)" "John"]
                                     "SELECT * FROM users"])))
    
    (testing "requires at least one statement"
      (is (not (s/valid? ::db/statements []))))
    
    (testing "rejects invalid statement formats"
      (is (not (s/valid? ::db/statements [123])))
      (is (not (s/valid? ::db/statements [{:invalid true}]))))))

(deftest db-function-test
  (testing "db function creates proper effect maps"
    (testing "handles single statement as vector"
      (let [statement ["SELECT * FROM users WHERE id = ?" 1]
            result (db/db statement)]
        (is (= :db (:effect/type result)))
        (is (= [statement] (:statements result)))))
    
    (testing "handles single statement as string"
      (let [statement "SELECT * FROM users"
            result (db/db statement)]
        (is (= :db (:effect/type result)))
        (is (= [statement] (:statements result)))))
    
    (testing "handles multiple statements"
      (let [statements [["SELECT * FROM users WHERE id = ?" 1]
                        "SELECT * FROM orders"]
            result (db/db statements)]
        (is (= :db (:effect/type result)))
        (is (= statements (:statements result)))))
    
    (testing "creates spec-valid effects"
      (let [effect (db/db ["SELECT 1"])]
        (is (s/valid? :broker/effect effect))))))

(deftest db-effect-execution-test
  (testing "db! function execution"
    (testing "executes statements in transaction"
      (let [executed-statements (atom [])
            context {:db "jdbc:h2:mem:test"}
            effect {:statements [["SELECT * FROM users WHERE id = ?" 1]
                                 "SELECT * FROM orders"]}]
        
        (with-redefs [next.jdbc/execute! (fn [tx statement]
                                           (swap! executed-statements conj statement))]
          (db/db! effect context)
          (is (= [["SELECT * FROM users WHERE id = ?" 1]
                  "SELECT * FROM orders"]
                 @executed-statements)))))
    
    (testing "handles single statement"
      (let [executed-statements (atom [])
            context {:db "jdbc:h2:mem:test"}
            effect {:statements ["SELECT 1"]}]
        
        (with-redefs [next.jdbc/execute! (fn [tx statement]
                                           (swap! executed-statements conj statement))]
          (db/db! effect context)
          (is (= ["SELECT 1"] @executed-statements)))))))
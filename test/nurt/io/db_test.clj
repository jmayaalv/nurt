(ns nurt.io.db-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nurt.io.db :as db]))

(defn reset-tables-fixture [f]
  (db/clear-tables!)
  (f)
  (db/clear-tables!))

(use-fixtures :each reset-tables-fixture)

(deftest register-tables-initial-test
  (testing "Initial registration succeeds"
    (db/register-tables! {"trd_fund" "fund"})
    (is (= {"trd_fund" "fund"} @db/table->ns+))))

(deftest register-tables-idempotent-test
  (testing "Re-registration with same namespace succeeds"
    (db/register-tables! {"trd_fund" "fund"})
    (db/register-tables! {"trd_fund" "fund"})
    (is (= {"trd_fund" "fund"} @db/table->ns+))))

(deftest register-tables-non-conflicting-test
  (testing "Adding different tables succeeds"
    (db/register-tables! {"trd_fund" "fund"})
    (db/register-tables! {"usr_account" "account"})
    (is (= {"trd_fund" "fund"
            "usr_account" "account"}
           @db/table->ns+))))

(deftest register-tables-conflict-test
  (testing "Override attempt throws exception"
    (db/register-tables! {"trd_fund" "fund"})
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"The table trd_fund has already been registered with namespace fund"
         (db/register-tables! {"trd_fund" "edge.fund"})))))

(deftest register-tables-exception-data-test
  (testing "Exception contains correct error data"
    (db/register-tables! {"trd_fund" "fund"})
    (try
      (db/register-tables! {"trd_fund" "edge.fund"})
      (is false "Should have thrown exception")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= "trd_fund" (:table data)))
          (is (= "fund" (:existing-namespace data)))
          (is (= "edge.fund" (:attempted-namespace data)))
          (is (= {"trd_fund" "edge.fund"}
                 (:attempted-registration data))))))))

(deftest clear-tables-test
  (testing "clear-tables! allows re-registration"
    (db/register-tables! {"trd_fund" "fund"})
    (db/clear-tables!)
    (db/register-tables! {"trd_fund" "edge.fund"})
    (is (= {"trd_fund" "edge.fund"} @db/table->ns+))))

(deftest register-multiple-tables-test
  (testing "Registering multiple tables at once succeeds"
    (db/register-tables! {"trd_fund" "fund"
                          "usr_account" "account"
                          "prd_order" "order"})
    (is (= {"trd_fund" "fund"
            "usr_account" "account"
            "prd_order" "order"}
           @db/table->ns+))))

(deftest register-empty-map-test
  (testing "Registering empty map succeeds"
    (db/register-tables! {})
    (is (= {} @db/table->ns+))))

(ns ^:parallel nurt.effect.email-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [nurt.effect :as nurt.effect]
            [nurt.effect.email :as effect-email]))

(deftest email-effect-spec-test
  (testing "Email effect specification validation"
    (testing "validates valid email effects"
      (testing "basic HTML email"
        (let [valid-effect {:effect/type :email
                            :email-data {:to ["user@example.com"]
                                         :from "sender@example.com"
                                         :subject "Welcome"
                                         :html-body "<h1>Welcome!</h1>"}}]
          (is (s/valid? (nurt.effect/effect-spec valid-effect) valid-effect))))
      
      (testing "plain text email"
        (let [valid-effect {:effect/type :email
                            :email-data {:to ["user@example.com"]
                                         :from "sender@example.com"
                                         :subject "Welcome"
                                         :text-body "Welcome to our service!"}}]
          (is (s/valid? (nurt.effect/effect-spec valid-effect) valid-effect))))
      
      (testing "email with both HTML and text"
        (let [valid-effect {:effect/type :email
                            :email-data {:to ["user@example.com"]
                                         :from "sender@example.com"
                                         :subject "Newsletter"
                                         :html-body "<p>HTML version</p>"
                                         :text-body "Text version"}}]
          (is (s/valid? (nurt.effect/effect-spec valid-effect) valid-effect))))
      
      (testing "email with multiple recipients and CC/BCC"
        (let [valid-effect {:effect/type :email
                            :email-data {:to ["user1@example.com" "user2@example.com"]
                                         :cc ["manager@example.com"]
                                         :bcc ["archive@example.com"]
                                         :from "sender@example.com"
                                         :subject "Team Update"
                                         :html-body "<p>Team update</p>"}}]
          (is (s/valid? (nurt.effect/effect-spec valid-effect) valid-effect))))
      
      (testing "email with attachments"
        (let [valid-effect {:effect/type :email
                            :email-data {:to ["user@example.com"]
                                         :from "sender@example.com"
                                         :subject "Report"
                                         :html-body "<p>Please find report attached</p>"
                                         :attachments [{:file-path "/tmp/report.pdf"
                                                        :name "monthly-report.pdf"
                                                        :description "Monthly Report"}]}}]
          (is (s/valid? (nurt.effect/effect-spec valid-effect) valid-effect)))))
    
    (testing "rejects invalid email effects"
      (testing "missing effect type"
        (let [invalid-effect {:email-data {:to ["user@example.com"]
                                           :from "sender@example.com"
                                           :subject "Test"
                                           :text-body "Test"}}]
          (is (thrown? Exception (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect)))))
      
      (testing "missing email-data"
        (let [invalid-effect {:effect/type :email}]
          (is (not (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect)))))
      
      (testing "missing required fields"
        (testing "missing to"
          (let [invalid-effect {:effect/type :email
                                :email-data {:from "sender@example.com"
                                             :subject "Test"
                                             :text-body "Test"}}]
            (is (not (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect)))))
        
        (testing "missing from"
          (let [invalid-effect {:effect/type :email
                                :email-data {:to ["user@example.com"]
                                             :subject "Test"
                                             :text-body "Test"}}]
            (is (not (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect)))))
        
        (testing "missing subject"
          (let [invalid-effect {:effect/type :email
                                :email-data {:to ["user@example.com"]
                                             :from "sender@example.com"
                                             :text-body "Test"}}]
            (is (not (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect))))))
      
      (testing "missing both html-body and text-body"
        (let [invalid-effect {:effect/type :email
                              :email-data {:to ["user@example.com"]
                                           :from "sender@example.com"
                                           :subject "Empty email"}}]
          (is (not (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect)))))
      
      (testing "empty recipients list"
        (let [invalid-effect {:effect/type :email
                              :email-data {:to []
                                           :from "sender@example.com"
                                           :subject "Test"
                                           :text-body "Test"}}]
          (is (not (s/valid? (nurt.effect/effect-spec invalid-effect) invalid-effect))))))))

(deftest email-address-spec-test
  (testing "Email address specification validation"
    (testing "validates correct email formats"
      (is (s/valid? ::effect-email/email-address "user@example.com"))
      (is (s/valid? ::effect-email/email-address "test.email+tag@domain.co.uk"))
      (is (s/valid? ::effect-email/email-address "user123@sub.domain.org"))
      (is (s/valid? ::effect-email/email-address "a@b.co")))
    
    (testing "rejects invalid email formats"
      (is (not (s/valid? ::effect-email/email-address "invalid-email")))
      (is (not (s/valid? ::effect-email/email-address "user@")))
      (is (not (s/valid? ::effect-email/email-address "@domain.com")))
      (is (not (s/valid? ::effect-email/email-address "user@domain")))
      (is (not (s/valid? ::effect-email/email-address "user.domain.com")))
      (is (not (s/valid? ::effect-email/email-address 123)))
      (is (not (s/valid? ::effect-email/email-address nil))))))

(deftest email-data-spec-test
  (testing "Email data specification validation"
    (testing "validates minimal valid email data"
      (let [email-data {:to ["user@example.com"]
                        :from "sender@example.com"
                        :subject "Test"
                        :text-body "Hello"}]
        (is (s/valid? ::effect-email/email-data email-data))))
    
    (testing "validates email with HTML body only"
      (let [email-data {:to ["user@example.com"]
                        :from "sender@example.com"
                        :subject "HTML Email"
                        :html-body "<h1>Hello</h1>"}]
        (is (s/valid? ::effect-email/email-data email-data))))
    
    (testing "validates email with multiple recipients"
      (let [email-data {:to ["user1@example.com" "user2@example.com" "user3@example.com"]
                        :from "sender@example.com"
                        :subject "Broadcast"
                        :text-body "Message for everyone"}]
        (is (s/valid? ::effect-email/email-data email-data))))
    
    (testing "validates email with CC and BCC"
      (let [email-data {:to ["primary@example.com"]
                        :cc ["cc1@example.com" "cc2@example.com"]
                        :bcc ["bcc@example.com"]
                        :from "sender@example.com"
                        :subject "CC/BCC Test"
                        :html-body "<p>Test message</p>"}]
        (is (s/valid? ::effect-email/email-data email-data))))
    
    (testing "validates email with attachments"
      (let [email-data {:to ["user@example.com"]
                        :from "sender@example.com"
                        :subject "With Attachments"
                        :text-body "See attachments"
                        :attachments [{:file-path "/tmp/doc1.pdf"}
                                      {:file-path "/tmp/doc2.txt"
                                       :name "document.txt"
                                       :description "Important document"}]}]
        (is (s/valid? ::effect-email/email-data email-data))))
    
    (testing "rejects invalid email data"
      (testing "invalid recipient email format"
        (let [email-data {:to ["invalid-email"]
                          :from "sender@example.com"
                          :subject "Test"
                          :text-body "Test"}]
          (is (not (s/valid? ::effect-email/email-data email-data)))))
      
      (testing "invalid sender email format"
        (let [email-data {:to ["user@example.com"]
                          :from "invalid-email"
                          :subject "Test"
                          :text-body "Test"}]
          (is (not (s/valid? ::effect-email/email-data email-data)))))
      
      (testing "invalid CC email format"
        (let [email-data {:to ["user@example.com"]
                          :cc ["invalid-email"]
                          :from "sender@example.com"
                          :subject "Test"
                          :text-body "Test"}]
          (is (not (s/valid? ::effect-email/email-data email-data)))))
      
      (testing "invalid attachment specification"
        (let [email-data {:to ["user@example.com"]
                          :from "sender@example.com"
                          :subject "Test"
                          :text-body "Test"
                          :attachments [{:name "file.txt"}]}] ; Missing file-path
          (is (not (s/valid? ::effect-email/email-data email-data))))))))

(deftest attachment-spec-test
  (testing "Attachment specification validation"
    (testing "validates minimal attachment"
      (let [attachment {:file-path "/tmp/document.pdf"}]
        (is (s/valid? ::effect-email/attachment attachment))))
    
    (testing "validates attachment with name"
      (let [attachment {:file-path "/tmp/report.xlsx"
                        :name "monthly-report.xlsx"}]
        (is (s/valid? ::effect-email/attachment attachment))))
    
    (testing "validates attachment with all fields"
      (let [attachment {:file-path "/home/user/important.doc"
                        :name "important-document.doc"
                        :description "Important business document"}]
        (is (s/valid? ::effect-email/attachment attachment))))
    
    (testing "rejects invalid attachments"
      (testing "missing file-path"
        (let [attachment {:name "file.txt"
                          :description "A file"}]
          (is (not (s/valid? ::effect-email/attachment attachment)))))
      
      (testing "invalid file-path type"
        (let [attachment {:file-path 123
                          :name "file.txt"}]
          (is (not (s/valid? ::effect-email/attachment attachment)))))
      
      (testing "invalid name type"
        (let [attachment {:file-path "/tmp/file.txt"
                          :name 123}]
          (is (not (s/valid? ::effect-email/attachment attachment)))))
      
      (testing "invalid description type"
        (let [attachment {:file-path "/tmp/file.txt"
                          :description 123}]
          (is (not (s/valid? ::effect-email/attachment attachment))))))))

(deftest email-function-test
  (testing "email function creates valid effects"
    (testing "creates basic email effect"
      (let [email-data {:to ["user@example.com"]
                        :from "noreply@company.com"
                        :subject "Welcome to our service"
                        :html-body "<h1>Welcome!</h1><p>Thanks for joining us.</p>"}
            effect (effect-email/email email-data)]
        
        (is (= :email (:effect/type effect)))
        (is (= email-data (:email-data effect)))
        (is (s/valid? (nurt.effect/effect-spec effect) effect))))
    
    (testing "creates text email effect"
      (let [email-data {:to ["user@example.com"]
                        :from "support@company.com"
                        :subject "Password Reset"
                        :text-body "Click here to reset your password: https://example.com/reset"}
            effect (effect-email/email email-data)]
        
        (is (= :email (:effect/type effect)))
        (is (= email-data (:email-data effect)))
        (is (s/valid? (nurt.effect/effect-spec effect) effect))))
    
    (testing "creates email with multiple recipients"
      (let [email-data {:to ["user1@example.com" "user2@example.com"]
                        :cc ["manager@example.com"]
                        :from "hr@company.com"
                        :subject "Company Meeting"
                        :html-body "<p>Meeting scheduled for tomorrow at 2 PM.</p>"}
            effect (effect-email/email email-data)]
        
        (is (= :email (:effect/type effect)))
        (is (= email-data (:email-data effect)))
        (is (s/valid? (nurt.effect/effect-spec effect) effect))))
    
    (testing "creates email with attachments"
      (let [email-data {:to ["client@example.com"]
                        :from "reports@company.com"
                        :subject "Monthly Report"
                        :html-body "<p>Please find the monthly report attached.</p>"
                        :text-body "Please find the monthly report attached."
                        :attachments [{:file-path "/tmp/report.pdf"
                                       :name "monthly-report.pdf"
                                       :description "Monthly Sales Report"}
                                      {:file-path "/tmp/summary.xlsx"
                                       :name "summary.xlsx"}]}
            effect (effect-email/email email-data)]
        
        (is (= :email (:effect/type effect)))
        (is (= email-data (:email-data effect)))
        (is (s/valid? (nurt.effect/effect-spec effect) effect))))))

(deftest email-effect-handler-test
  (testing "email! effect handler function"
    (testing "calls IO function with correct parameters"
      (let [email-data {:to ["user@example.com"]
                        :from "sender@example.com"
                        :subject "Test Email"
                        :html-body "<h1>Test</h1>"}
            effect {:effect/type :email :email-data email-data}
            context {:smtp-host "smtp.example.com"
                     :smtp-port 587
                     :username "user@smtp.com"
                     :password "password"}
            io-calls (atom [])]
        
        (with-redefs [nurt.io.email/send! (fn [ctx data]
                                            (swap! io-calls conj {:context ctx :data data})
                                            "message-id-123")]
          (let [result (effect-email/email! effect context)]
            
            (is (= "message-id-123" result))
            (is (= 1 (count @io-calls)))
            (let [call (first @io-calls)]
              (is (= context (:context call)))
              (is (= email-data (:data call))))))))
    
    (testing "passes through IO function return value"
      (let [effect {:effect/type :email
                    :email-data {:to ["user@example.com"]
                                 :from "sender@example.com"
                                 :subject "Test"
                                 :text-body "Test message"}}
            expected-message-id "<1234567890@smtp.example.com>"]
        
        (with-redefs [nurt.io.email/send! (constantly expected-message-id)]
          (let [result (effect-email/email! effect {})]
            (is (= expected-message-id result))))))
    
    (testing "propagates IO function exceptions"
      (let [effect {:effect/type :email
                    :email-data {:to ["user@example.com"]
                                 :from "sender@example.com"
                                 :subject "Failed Email"
                                 :text-body "This will fail"}}
            expected-error (ex-info "SMTP connection failed" {:host "smtp.example.com"})]
        
        (with-redefs [nurt.io.email/send! (fn [_ _] (throw expected-error))]
          (is (thrown-with-msg? Exception #"SMTP connection failed"
                                (effect-email/email! effect {}))))))
    
    (testing "handles complex email with all features"
      (let [complex-email-data {:to ["user1@example.com" "user2@example.com"]
                                :cc ["manager@example.com"]
                                :bcc ["archive@example.com"]
                                :from "system@company.com"
                                :subject "Complex Email Test"
                                :html-body "<h1>Complex Email</h1><p>With attachments</p>"
                                :text-body "Complex Email\nWith attachments"
                                :attachments [{:file-path "/tmp/report.pdf"
                                               :name "report.pdf"
                                               :description "Monthly Report"}]}
            effect {:effect/type :email :email-data complex-email-data}
            context {:smtp-host "smtp.company.com"
                     :smtp-port 465
                     :username "system@company.com"
                     :password "secure-password"
                     :ssl true
                     :tls false}
            captured-calls (atom [])]
        
        (with-redefs [nurt.io.email/send! (fn [ctx data]
                                            (swap! captured-calls conj {:ctx ctx :data data})
                                            "<complex-message-id@company.com>")]
          (let [result (effect-email/email! effect context)]
            
            (is (= "<complex-message-id@company.com>" result))
            (is (= 1 (count @captured-calls)))
            (let [call (first @captured-calls)]
              (is (= context (:ctx call)))
              (is (= complex-email-data (:data call))))))))))
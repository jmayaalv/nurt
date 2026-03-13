(ns nurt.effect.email
  (:require [clojure.spec.alpha :as s]
            [nurt.effect :as effect]
            [nurt.io.email :as io-email]))

(s/def ::email-address
  (s/and string? #(re-matches #".+@.+\..+" %)))

(s/def ::to
  (s/coll-of ::email-address :min-count 1))

(s/def ::from ::email-address)

(s/def ::subject string?)

(s/def ::html-body string?)

(s/def ::text-body string?)

(s/def ::cc
  (s/coll-of ::email-address))

(s/def ::bcc
  (s/coll-of ::email-address))

(s/def ::file-path string?)
(s/def ::name string?)
(s/def ::description string?)

(s/def ::attachment
  (s/keys :req-un [::file-path]
          :opt-un [::name ::description]))

(s/def ::attachments
  (s/coll-of ::attachment))

(s/def ::email-data
  (s/and (s/keys :req-un [::to ::from ::subject]
                 :opt-un [::html-body ::text-body ::cc ::bcc ::attachments])
         #(or (:html-body %) (:text-body %))))

(defmethod effect/effect-spec :email [_]
  (s/keys :req-un [::email-data]
          :req [:effect/type]))

(defn email
  "Creates an email effect for sending HTML emails.
  
  Accepts email data including recipients, sender, subject, content, and
  optional attachments. Either :html-body or :text-body (or both) must be provided.
  
  Args:
    email-data - Map containing:
      :to - Collection of recipient email addresses (required)
      :from - Sender email address (required)
      :subject - Email subject line (required)
      :html-body - HTML content of the email (optional)
      :text-body - Plain text content of the email (optional)
      :cc - Collection of CC email addresses (optional)
      :bcc - Collection of BCC email addresses (optional)
      :attachments - Collection of attachment maps (optional)
  
  Returns:
    Email effect map with :effect/type :email and :email-data
  
  Examples:
    ;; Simple HTML email
    (email {:to [\"user@example.com\"]
            :from \"noreply@company.com\"
            :subject \"Welcome!\"
            :html-body \"<h1>Welcome to our service!</h1>\"
            :text-body \"Welcome to our service!\"})
    
    ;; Email with attachments and CC
    (email {:to [\"user@example.com\"]
            :cc [\"manager@company.com\"]
            :from \"reports@company.com\"
            :subject \"Monthly Report\"
            :html-body \"<p>Please find the monthly report attached.</p>\"
            :attachments [{:file-path \"/tmp/report.pdf\"
                           :name \"monthly-report.pdf\"
                           :description \"Monthly Sales Report\"}]})
    
    ;; Plain text email
    (email {:to [\"user@example.com\"]
            :from \"noreply@company.com\"
            :subject \"Password Reset\"
            :text-body \"Click here to reset your password: ...\"})"
  [email-data]
  {:effect/type :email
   :email-data email-data})

(defn email!
  "Executes an email effect by sending the specified email.
  
  This is the effect handler function that performs the actual email sending.
  It's automatically called by the Nurt broker when processing :email effects.
  The context must contain SMTP configuration.
  
  Args:
    effect - Email effect map containing :email-data
    context - Execution context containing SMTP configuration:
      :smtp-host - SMTP server hostname (required)
      :smtp-port - SMTP server port (optional, defaults to 587)
      :username - SMTP username for authentication (optional)
      :password - SMTP password for authentication (optional)
      :ssl - Whether to use SSL (optional, default false)
      :tls - Whether to use TLS (optional, default true)
  
  Returns:
    Message ID of the sent email
  
  Note:
    This function is typically not called directly. It's intended to be
    registered as an effect handler in the broker and called automatically
    during command processing."
  [{:keys [email-data]} context]
  (io-email/send! context email-data))
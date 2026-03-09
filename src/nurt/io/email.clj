(ns nurt.io.email
  "Email IO operations for sending HTML emails using Apache Commons Email.

  This namespace provides low-level email operations that can be used independently
  or by effect handlers. Emails are sent using Apache Commons Email 2 with support
  for HTML content, attachments, and multiple recipients."
  (:import [org.apache.commons.mail2.jakarta HtmlEmail]
           [org.apache.commons.mail2.jakarta EmailAttachment]))

(defn- create-attachment
  "Creates an EmailAttachment from attachment specification."
  [{:keys [file-path name description]}]
  (let [attachment (EmailAttachment.)]
    (.setPath attachment file-path)
    (when name (.setName attachment name))
    (when description (.setDescription attachment description))
    attachment))

(defn send!
  "Sends an HTML email using Apache Commons Email.

  Supports HTML and text content, multiple recipients, attachments, and
  configurable SMTP settings.

  Args:
    context - Execution context containing SMTP configuration:
      :smtp-host - SMTP server hostname (required)
      :smtp-port - SMTP server port (optional, defaults to 587)
      :username - SMTP username for authentication (optional)
      :password - SMTP password for authentication (optional)
      :ssl - Whether to use SSL (optional, default false)
      :tls - Whether to use TLS (optional, default true)
    email-data - Map containing:
      :to - Collection of recipient email addresses (required)
      :from - Sender email address (required)
      :subject - Email subject line (required)
      :html-body - HTML content of the email (optional)
      :text-body - Plain text content of the email (optional)
      :cc - Collection of CC email addresses (optional)
      :bcc - Collection of BCC email addresses (optional)
      :attachments - Collection of attachment maps (optional)
        Each attachment map can contain:
          :file-path - Path to the file (required)
          :name - Display name for attachment (optional)
          :description - Description of attachment (optional)

  Returns:
    Message ID of the sent email

  Examples:
    ;; Simple HTML email
    (send! {:smtp-host \"smtp.gmail.com\"
            :smtp-port 587
            :username \"user@gmail.com\"
            :password \"password\"
            :tls true}
           {:to [\"recipient@example.com\"]
            :from \"sender@example.com\"
            :subject \"Welcome!\"
            :html-body \"<h1>Welcome to our service!</h1>\"
            :text-body \"Welcome to our service!\"})

    ;; Email with attachments
    (send! smtp-config
           {:to [\"user@example.com\"]
            :from \"noreply@company.com\"
            :subject \"Your Report\"
            :html-body \"<p>Please find your report attached.</p>\"
            :attachments [{:file-path \"/tmp/report.pdf\"
                           :name \"monthly-report.pdf\"
                           :description \"Monthly Sales Report\"}]})"
  [context {:keys [to from subject html-body text-body cc bcc attachments] :as smtp}]
  (let [{:keys [smtp-host smtp-port username password ssl tls]
         :or {smtp-port 587 ssl false tls true} :as x} (merge context smtp)
        email (HtmlEmail.)]
    ;; Configure SMTP settings
    (.setHostName email smtp-host)
    (.setSmtpPort email smtp-port)
    (when username (.setAuthentication email username password))
    (.setSSLOnConnect email ssl)
    (.setStartTLSEnabled email tls)

    ;; Set sender and recipients
    (.setFrom email from)
    (doseq [recipient to]
      (.addTo email recipient))
    (doseq [recipient (or cc [])]
      (.addCc email recipient))
    (doseq [recipient (or bcc [])]
      (.addBcc email recipient))

    ;; Set subject and content
    (.setSubject email subject)
    (when html-body (.setHtmlMsg email html-body))
    (when text-body (.setTextMsg email text-body))

    ;; Add attachments
    (doseq [attachment-spec (or attachments [])]
      (let [attachment (create-attachment attachment-spec)]
        (.attach email attachment)))

    ;; Send the email
    (.send email)))

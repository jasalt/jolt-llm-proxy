(ns codex.cli
  "Interactive login, logout, usage, and info commands. Ports Go `auth.go`
  (interactiveLogin/browserLogin/deviceLogin), `usage.go` (codexUsage), and
  `info.go` (codexInfo)."
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [ring-chez.adapter :as adapter]
            [jolt.http-client :as http]
            [codex.auth :as auth]
            [llm-proxy.id :as id]
            [llm-proxy.time :as time]))

(def redirect-uri "http://localhost:1455/auth/callback")
(def device-redirect-uri "https://auth.openai.com/deviceauth/callback")
(def device-verification-uri "https://auth.openai.com/codex/device")
(def usage-url "https://chatgpt.com/backend-api/wham/usage")

;; ---------------------------------------------------------------------------
;; Encoding helpers
;; ---------------------------------------------------------------------------

(defn b64url-encode
  "Base64url-encode bytes without padding."
  [b]
  (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) b))

(defn random-bytes [n]
  (id/random-bytes n))

(defn sha256-bytes
  [s]
  (let [d (java.security.MessageDigest/getInstance "SHA-256")]
    (.digest d (.getBytes s "UTF-8"))))

(defn parse-query
  "Parse an URL query string into a map of decoded string keys/values."
  [qs]
  (if (or (nil? qs) (= qs ""))
    {}
    (into {}
          (map (fn [pair]
                 (let [idx (.indexOf pair "=")]
                   (if (neg? idx)
                     [pair ""]
                     [(subs pair 0 idx)
                      (java.net.URLDecoder/decode (subs pair (inc idx)) "UTF-8")]))))
          (str/split qs #"&"))))

(defn open-browser [url]
  (try
    (.. (ProcessBuilder. ["xdg-open" url]) start)
    true
    (catch Throwable _ false)))

(defn read-console-line
  "Read one console line, accepting either LF or CR as its terminator.

  `read-line` only recognizes LF.  Some terminal/PTY combinations used with
  Jolt leave stdin in a mode where Enter arrives as CR, so an interactive
  login selection would otherwise remain unread."
  [reader]
  (loop [line ""]
    (let [ch (.read reader)]
      (cond
        (= ch -1) line
        (or (= ch 10) (= ch 13)) line
        :else (recur (str line (char ch)))))))

(defn- restore-enter-translation!
  "Restore the POSIX tty translation from Enter's CR byte to LF.

  A sandbox can start Bash with canonical input but `icrnl` disabled. In that
  state the kernel echoes Enter as `^M` but does not release the canonical line
  to this process, so no application-level reader can consume it. Inherit stdio
  so `stty` operates on fd 0; opening /dev/tty can be denied in a jail."
  []
  (try
    (let [process (.. (ProcessBuilder. ["stty" "icrnl"])
                      inheritIO
                      start)]
      (zero? (.waitFor process)))
    (catch Throwable _ false)))

(defn prompt-choice
  "Print the prompt, then read a trimmed choice from the console."
  [prompt]
  ;; Complete tty repair before displaying the prompt, otherwise a fast input
  ;; relay can deliver CR in the small window before `stty` takes effect.
  (restore-enter-translation!)
  (println prompt)
  (print "> ") (flush)
  (let [reader (java.io.BufferedReader.
                 (java.io.InputStreamReader. System/in))]
    (str/trim (read-console-line reader))))

;; ---------------------------------------------------------------------------
;; Browser login (PKCE + localhost callback server)
;; ---------------------------------------------------------------------------

(defn- callback-handler
  "Ring handler for the OAuth callback on port 1455. Delivers the exchanged
  credential (or error) to `result` (a promise)."
  [state verifier result]
  (fn [req]
    (let [q (parse-query (get req :query-string))
          respond (fn [status html]
                    {:status status
                     :headers {"Content-Type" "text/html"}
                     :body html})
          deliver! (fn [v]
                     (deliver result v)
                     nil)]
      (cond
        (or (not= (:request-method req) :get)
            (not= (:uri req) "/auth/callback"))
        (respond 404 "Not found")

        (not= (get q "state") state)
        ;; A browser probe or unrelated localhost request must not consume the
        ;; one valid callback opportunity.
        (respond 400 "OAuth state mismatch")

        (not (empty? (get q "error")))
        (let [msg (str "OAuth failed: " (get q "error_description"))]
          (deliver! {:error msg})
          (respond 200 "<h1>Login failed</h1><p>Return to the terminal.</p>"))

        (empty? (get q "code"))
        (respond 400 "Missing authorization code")

        :else
        (try
          (let [cred (auth/exchange-token!
                       {"grant_type" "authorization_code"
                        "client_id" auth/client-id
                        "code" (get q "code")
                        "code_verifier" verifier
                        "redirect_uri" redirect-uri}
                       nil)]
            (deliver! {:cred cred})
            (respond 200 "<h1>Login successful</h1><p>You can close this window.</p>"))
          (catch Throwable t
            (deliver! {:error (.getMessage t)})
            (respond 200 "<h1>Login failed</h1><p>Return to the terminal.</p>")))))))

(defn browser-login
  "PKCE browser login: start a local callback server on 127.0.0.1:1455, print
  the authorize URL, wait (up to 5 min) for the credential exchange."
  []
  (let [verifier (b64url-encode (random-bytes 32))
        challenge (b64url-encode (sha256-bytes verifier))
        state (apply str (map #(format "%02x" %) (random-bytes 16)))
        result (promise)
        server (adapter/run-server (callback-handler state verifier result)
                                   {:port 1455 :strategy :threads :worker-threads 1})
        auth-url (str auth/auth-base-url "/oauth/authorize"
                      "?response_type=code"
                      "&client_id=" auth/client-id
                      "&redirect_uri=" (java.net.URLEncoder/encode redirect-uri "UTF-8")
                      "&scope=" (java.net.URLEncoder/encode "openid profile email offline_access" "UTF-8")
                      "&code_challenge=" challenge
                      "&code_challenge_method=S256"
                      "&state=" state
                      "&id_token_add_organizations=true"
                      "&codex_cli_simplified_flow=true"
                      "&originator=pi")
        deadline (+ (time/now-ms) (* 5 60 1000))
        outcome (try
                  ;; Make progress visible even when there is no graphical
                  ;; session. Previously `auth-url` was constructed but never
                  ;; opened or printed, which made a successful choice of 1
                  ;; look like the prompt was still waiting for Enter.
                  (println "Opening your browser for ChatGPT login...")
                  (println (str "If it does not open, visit:\n" auth-url))
                  (open-browser auth-url)
                  (loop []
                    (cond
                      (realized? result) @result
                      (>= (time/now-ms) deadline) {:error "OAuth login timed out"}
                      :else (do (Thread/sleep 500) (recur))))
                  (finally
                    (adapter/stop-server server)))]
    (if-let [err (:error outcome)]
      (throw (ex-info err {}))
      (:cred outcome))))

;; ---------------------------------------------------------------------------
;; Device login (headless)
;; ---------------------------------------------------------------------------

(defn- device-interval
  "The poll interval from the deviceauth response: number (seconds), numeric
  string, or default 5s. Never below 1s."
  [v]
  (let [n (cond (number? v) (double v)
                (string? v) (try (Double/parseDouble v) (catch Throwable _ nil))
                :else nil)]
    (max 1000.0 (* 1000.0 (or n 5.0)))))

(defn device-login
  "Device-code login: request a user code, poll until authorized, then
  exchange the resulting authorization code."
  []
  (let [resp (http/post (str auth/auth-base-url "/api/accounts/deviceauth/usercode")
              {:body (json/write-str {:client_id auth/client-id})
               :content-type "application/json"
               :throw-exceptions false})]
    (when-not (= 200 (:status resp))
      (throw (ex-info (str "request device code: HTTP " (:status resp) ": " (:body resp)) {})))
    (let [body (json/read-str (:body resp) :key-fn keyword)
          device-auth-id (:device_auth_id body)
          user-code (:user_code body)]
      (when (or (empty? device-auth-id) (empty? user-code))
        (throw (ex-info "invalid device code response" {})))
      (println (str "Open " device-verification-uri " and enter code: " user-code))
      (println "Waiting for authorization...")
      (open-browser device-verification-uri)
      (loop [interval (device-interval (:interval body))
             deadline (+ (time/now-ms) (* 15 60 1000))]
        (when (>= (time/now-ms) deadline)
          (throw (ex-info "device login timed out" {})))
        (Thread/sleep (long interval))
        (let [poll (http/post (str auth/auth-base-url "/api/accounts/deviceauth/token")
                     {:body (json/write-str {:device_auth_id device-auth-id
                                             :user_code user-code})
                      :content-type "application/json"
                      :throw-exceptions false})
              status (:status poll)
              data (str (:body poll))]
          (cond
            (and (>= status 200) (< status 300))
            (let [dt (json/read-str data :key-fn keyword)]
              (when (or (empty? (:authorization_code dt)) (empty? (:code_verifier dt)))
                (throw (ex-info "invalid device authorization response" {})))
              (auth/exchange-token!
                {"grant_type" "authorization_code"
                 "client_id" auth/client-id
                 "code" (:authorization_code dt)
                 "code_verifier" (:code_verifier dt)
                 "redirect_uri" device-redirect-uri}
                nil))

            (str/includes? data "slow_down")
            (recur (min 15000 (+ interval 5000)) deadline)

            (or (= status 403) (= status 404) (str/includes? data "authorization_pending"))
            (recur interval deadline)

            :else
            (throw (ex-info (str "device authorization failed (HTTP " status "): "
                                 (str/trim data)) {}))))))))

(defn login!
  "Interactive login: browser (default) or device code. Saves the credential
  to `path` (default codex.auth/default-auth-path)."
  ([] (login! auth/default-auth-path))
  ([path]
   (println "Select OpenAI Codex login method:")
   (println "  1. Browser login (default)")
   (println "  2. Device code login (headless)")
   (let [choice (prompt-choice "")
         cred (if (= choice "2")
                (device-login)
                (browser-login))]
     (auth/save-cred! path cred)
     (println (str "Login successful. Credentials saved to " path))
     cred)))

;; ---------------------------------------------------------------------------
;; logout
;; ---------------------------------------------------------------------------

(defn logout!
  "Delete the saved credential file."
  ([] (logout! auth/default-auth-path))
  ([path]
   (auth/logout! (auth/start! path))
   (println "Logged out.")))

;; ---------------------------------------------------------------------------
;; usage
;; ---------------------------------------------------------------------------

(defn- format-percent [v]
  (let [clamped (max 0.0 (min 100.0 (double v)))]
    (if (= clamped (double (long clamped)))
      (str (long clamped) "%")
      (format "%.1f%%" clamped))))

(defn- weekly-window
  "Find the ~weekly rate-limit window in the usage payload."
  [payload]
  (let [rate-limit (get payload :rate_limit)]
    (when (map? rate-limit)
      (some (fn [k]
              (let [w (get rate-limit k)]
                (when (map? w)
                  (let [used (:used_percent w)
                        duration (:limit_window_seconds w)
                        reset-at (:reset_at w)]
                    (when (and (number? used) (number? duration) (number? reset-at)
                               (<= (Math/abs (- duration (* 7 24 60 60))) 60480))
                      {:used used :reset-at reset-at})))))
            [:primary_window :secondary_window]))))

(defn usage!
  "Fetch and print the weekly Codex usage for the current login."
  []
  (let [store (auth/start! auth/default-auth-path)]
    (when-not (auth/authenticated? store)
      (throw (ex-info "no saved login found; run `login`" {})))
    (let [[token account-id] (auth/token store)
          resp (http/get usage-url
                  {:headers {"Authorization" (str "Bearer " token)
                             "ChatGPT-Account-Id" account-id
                             "Accept" "application/json"
                             "originator" "pi"}
                   :throw-exceptions false})]
      (when-not (= 200 (:status resp))
        (throw (ex-info (str "ChatGPT usage request failed (HTTP " (:status resp) "): "
                             (str/trim (str (:body resp)))) {})))
      (let [payload (json/read-str (:body resp) :key-fn keyword)
            window (weekly-window payload)]
        (when (nil? window)
          (throw (ex-info "ChatGPT did not return a weekly Codex usage window" {})))
        (let [used (:used window)]
          (println (str "ChatGPT Codex weekly usage: " (format-percent used)
                        " used · " (format-percent (- 100 used)) " remaining"))
          (let [fmt (-> (java.time.format.DateTimeFormatter/ofPattern "EEE, dd MMM yyyy HH:mm zzz")
                        (.withZone (java.time.ZoneId/systemDefault)))]
            (println (str "Resets " (.format fmt (java.time.Instant/ofEpochSecond (long (:reset-at window))))))))))))

;; ---------------------------------------------------------------------------
;; info
;; ---------------------------------------------------------------------------

(defn- stringify [v]
  (cond
    (nil? v) ""
    (string? v) v
    (boolean? v) (str v)
    (number? v) (if (= v (double (long v))) (str (long v)) (str v))
    :else (json/write-str v)))

(defn- claim-str [payload k]
  (let [v (get payload k)]
    (cond (string? v) v
          (sequential? v) (str/join ", " (map stringify v))
          (nil? v) ""
          :else (stringify v))))

(defn- rfc1123
  [epoch-seconds]
  (.. (java.time.Instant/ofEpochSecond (long epoch-seconds))
      (atZone (java.time.ZoneId/systemDefault))
      (format (java.time.format.DateTimeFormatter/ofPattern "EEE, dd MMM yyyy HH:mm zzz"))))

(def known-top-level
  #{"email" "email_verified" "name" "sub" "iss" "aud" "iat" "exp" "scope"
    "organizations" "organization_id" "https://api.openai.com/auth"})

(def known-auth
  #{"chatgpt_account_id" "chatgpt_plan_type" "account_plan_type"})

(defn info!
  "Print everything known about the current login: disk credentials and all
  access-token JWT claims."
  []
  (let [store (auth/start! auth/default-auth-path)]
    (when-not (auth/authenticated? store)
      (throw (ex-info "no saved login found; run `login`" {})))
    (let [[token account-id] (auth/token store)
          cred @store
          path (:path cred)
          payload (auth/decode-jwt-payload token)
          auth-claim (get payload (keyword "https://api.openai.com/auth"))
          line (fn [k v] (println (format "%-18s%s" (str (if (keyword? k) (name k) k) ":") v)))]
      (println "ChatGPT / OpenAI Codex session")
      (println (apply str (repeat 39 "=")))
      (line "Auth file" path)
      (line "Account ID" account-id)
      (doseq [k [:email]]
        (when-let [v (claim-str payload k)] (when-not (= v "") (line k v))))
      (when (contains? payload :email_verified) (line "Email verified" (str (:email_verified payload))))
      (doseq [k [:name :sub :iss]]
        (let [v (claim-str payload k)]
          (when-not (= v "") (line k v))))
      (let [aud (claim-str payload :aud)]
        (when-not (= aud "") (line "Audience" aud)))
      (let [orgs (claim-str payload :organizations)]
        (when-not (= orgs "") (line "Organizations" orgs)))
      (let [org-id (claim-str payload :organization_id)]
        (when-not (= org-id "") (line "Organization ID" org-id)))
      (when (map? auth-claim)
        (let [plan (get auth-claim :chatgpt_plan_type)]
          (when (and plan (not= plan "")) (line "ChatGPT plan" plan)))
        (let [aplan (get auth-claim :account_plan_type)]
          (when (and aplan (not= aplan "")) (line "Account plan" aplan))))
      (let [scope (claim-str payload :scope)]
        (when-not (= scope "") (line "Scopes" scope)))
      (when (number? (:iat payload)) (line "Issued at" (rfc1123 (:iat payload))))
      (when (number? (:exp payload)) (line "JWT expires" (rfc1123 (:exp payload))))
      (when (pos? (or (:expires_at cred) 0))
        (let [expired (> (time/now-ms) (:expires_at cred))]
          (line "Token expires at" (str (rfc1123 (/ (:expires_at cred) 1000))
                                        " (" (if expired "expired" "valid") ")"))))
      (line "Refresh token" (if (empty? (:refresh_token cred)) "missing" "present"))
      (when (map? auth-claim)
        (let [extras (->> auth-claim
                          seq
                          (remove (fn [[k _]] (known-auth (subs (str k) 1))))
                          (sort-by (fn [[k _]] (str k))))]
          (when (seq extras)
            (println)
            (println "Auth claim extras (https://api.openai.com/auth):")
            (doseq [[k v] extras]
              (println (format "  %s: %s" (subs (str k) 1) (stringify v)))))))
      (let [extras (->> payload
                        seq
                        (remove (fn [[k _]] (known-top-level (subs (str k) 1))))
                        (sort-by (fn [[k _]] (str k))))]
        (when (seq extras)
          (println)
          (println "Other token claims:")
          (doseq [[k v] extras]
            (println (format "  %s: %s" (subs (str k) 1) (stringify v)))))))))


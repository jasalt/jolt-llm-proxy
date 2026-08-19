(ns codex.auth
  "Token store + OAuth refresh. Loads
  `~/.config/chatgpt-openai-api-adapter/auth.json`, refreshes the access token
  when expired, and exposes [token account-id] to the rest of the proxy.

  The store is an atom whose value is the credential PMap merged with :path and
  :lock (a stable Object for `locking`). Deref it to read the cred
  (`:access_token`, `:refresh_token`, `:expires_at`, `:account_id`)."
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [jolt.http-client :as http]
            [jolt.ffi :as ffi]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def client-id "app_EMoamEEZ73f0CkXaXp7hrann")
(def auth-base-url "https://auth.openai.com")
(def refresh-margin-ms (* 5 60 1000))   ; refresh if <5min left

(def default-auth-path
  (let [env (System/getenv "CHATGPT_ADAPTER_AUTH_FILE")]
    (if (and env (not= env ""))
      env
      (str (or (System/getenv "HOME") ".") "/.config/chatgpt-openai-api-adapter/auth.json"))))

;; ---------------------------------------------------------------------------
;; Credential file load/save
;; ---------------------------------------------------------------------------

;; Jolt's java.io.File shim has rename/delete but no permission methods. Bind
;; the small POSIX surface directly so OAuth refresh tokens never inherit a
;; permissive umask. Modes are octal: 0700 = 448, 0600 = 384.
(ffi/defcfn c-chmod "chmod" [:pointer :int] :int)

(def credential-keys
  [:access_token :refresh_token :expires_at :account_id])

(defn- chmod!
  [path mode]
  (let [p (ffi/string->ptr path)]
    (try
      (when-not (zero? (c-chmod p mode))
        (throw (ex-info "set credential permissions failed"
                        {:type :credential-file :operation :chmod :path path})))
      (finally (ffi/free p)))))

(defn load-cred
  "Load `auth.json` into a PMap, or nil only when the file is absent. Parse and
  permission failures are reported instead of being mistaken for logout."
  [path]
  (let [f (java.io.File. path)]
    (when (.exists f)
      (try
        (chmod! path 384)
        (json/read-str (slurp path) :key-fn keyword)
        (catch Throwable e
          (throw (ex-info (str "read credentials: " (ex-message e))
                          {:type :credential-file :operation :read :path path}
                          e)))))))

(defn save-cred!
  "Atomically save credential fields using an owner-only directory and file."
  [path cred]
  (let [target (java.io.File. path)
        parent (.getParentFile target)
        tmp-path (str path ".tmp")
        tmp (java.io.File. tmp-path)
        payload (select-keys cred credential-keys)]
    (try
      (when parent
        (when-not (or (.exists parent) (.mkdirs parent))
          (throw (ex-info "create credential directory failed"
                          {:type :credential-file :operation :mkdir :path (.getPath parent)})))
        (chmod! (.getPath parent) 448))
      (spit tmp-path (str (json/write-str payload) "\n"))
      (chmod! tmp-path 384)
      (when-not (.renameTo tmp target)
        (throw (ex-info "replace credential file failed"
                        {:type :credential-file :operation :rename :path path})))
      (chmod! path 384)
      payload
      (catch Throwable e
        (when (.exists tmp) (.delete tmp))
        (if (= :credential-file (:type (ex-data e)))
          (throw e)
          (throw (ex-info (str "write credentials: " (ex-message e))
                          {:type :credential-file :operation :write :path path}
                          e)))))))

;; ---------------------------------------------------------------------------
;; JWT helpers
;; ---------------------------------------------------------------------------

(defn b64url->b64std
  "Convert base64url (JWT payloads) to standard base64 for the shim's decoder."
  [s]
  (let [pad (mod (- 4 (mod (count s) 4)) 4)]
    (-> (str s (apply str (repeat pad "=")))
        (.replace "-" "+")
        (.replace "_" "/"))))

(defn decode-jwt-payload
  "Return the decoded JWT payload (keyword keys) for `token`, or nil."
  [token]
  (let [parts (str/split token #"\.")]
    (when (>= (count parts) 2)
      (let [part (nth parts 1)
            decoded (.decode (java.util.Base64/getDecoder)
                             (.getBytes (b64url->b64std part) "UTF-8"))]
        (json/read-str (String. decoded "UTF-8") :key-fn keyword)))))

(defn account-id-from-jwt
  "Extract `chatgpt_account_id` from the JWT (matches `auth.json`'s account_id)."
  [token]
  (get-in (decode-jwt-payload token)
          [(keyword "https://api.openai.com/auth") :chatgpt_account_id]))

(defn jwt-expiry-ms
  "Unix-ms expiry from the JWT `exp` claim (seconds), falling back to +1h."
  [token]
  (let [exp (get (decode-jwt-payload token) :exp)]
    (if (number? exp)
      (* (long exp) 1000)
      (+ (System/currentTimeMillis) (* 60 60 1000)))))

;; ---------------------------------------------------------------------------
;; Refresh
;; ---------------------------------------------------------------------------

(defn exchange-token!
  "POST an OAuth grant to /oauth/token and build a credential PMap from the
  response. `params` is a map of form parameters (grant_type, client_id, ...).
  `old-refresh` is reused when the response omits a refresh token."
  [params old-refresh]
  (let [resp (http/post (str auth-base-url "/oauth/token")
              {:form-params params
               :throw-exceptions false})]
    (when-not (= 200 (:status resp))
      (throw (ex-info "token exchange failed"
                      {:status (:status resp) :body (:body resp)})))
    (let [body (json/read-str (:body resp) :key-fn keyword)
          access (:access_token body)]
      (when (empty? access)
        (throw (ex-info "token response is missing access_token" {})))
      (let [refresh (or (:refresh_token body) old-refresh)
            expires-in (:expires_in body)
            expires-at (if (and expires-in (pos? expires-in))
                         (+ (System/currentTimeMillis) (* (long expires-in) 1000))
                         (jwt-expiry-ms access))
            account (account-id-from-jwt access)]
        {:access_token access
         :refresh_token refresh
         :expires_at expires-at
         :account_id account}))))

(defn refresh-token!
  "Exchange the refresh token for a fresh credential PMap."
  [cred]
  (exchange-token!
    {"grant_type" "refresh_token"
     "client_id" client-id
     "refresh_token" (:refresh_token cred)}
    (:refresh_token cred)))

;; ---------------------------------------------------------------------------
;; Stateful token store
;; ---------------------------------------------------------------------------

(defn start!
  "Load cred from `path` into an atom store and return it. The atom's value is
  the cred PMap merged with `:path` and `:lock`."
  ([path]
   (let [cred (load-cred path)]
     (atom (assoc cred :path path :lock (Object.)))))
  ([] (start! default-auth-path)))

(defn authenticated?
  "True when the store has both an access token and an account id."
  [store]
  (let [s @store]
    (and (some? (:access_token s)) (some? (:account_id s)))))

(defn token
  "Return `[access-token account-id]`, refreshing (and saving) when the token
  is within `refresh-margin-ms` of expiry or `:force` is set."
  ([store] (token store {}))
  ([store & {:keys [force]}]
   (let [lk (:lock @store)]
     (locking lk
       (let [cred @store]
         (when (nil? (:access_token cred))
           (throw (ex-info "not logged in; run login" {})))
         (if (or force (>= (+ (System/currentTimeMillis) refresh-margin-ms)
                           (:expires_at cred)))
           (if (nil? (:refresh_token cred))
             (throw (ex-info "access token expired and no refresh token available; login again" {}))
             (let [newcred (refresh-token!
                            (select-keys cred [:access_token :refresh_token]))
                   merged (assoc newcred :path (:path cred) :lock (:lock cred))]
               (save-cred! (:path cred) newcred)
               (reset! store merged)
               [(:access_token newcred) (:account_id newcred)]))
           [(:access_token cred) (:account_id cred)]))))))

(defn logout!
  "Clear the store and delete the credential file."
  [store]
  (let [path (:path @store)]
    (reset! store {:path path :lock (:lock @store)})
    (let [f (java.io.File. path)]
      (when (and (.exists f) (not (.delete f)))
        (throw (ex-info "delete credential file failed"
                        {:type :credential-file :operation :delete :path path}))))))

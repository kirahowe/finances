# Security Review - Finance Aggregator
**Date:** 2025-12-29
**Scope:** Plaid OAuth integration and application security posture

## Executive Summary

This security review identifies critical and high-priority security gaps in the finance aggregator application, with particular focus on the Plaid OAuth flow implementation. While the application demonstrates strong practices in credential encryption and secrets management, there are **critical CORS misconfigurations** and **missing authentication/authorization** that must be addressed before production deployment.

**Risk Level:** HIGH

### Critical Findings
1. Wildcard CORS configuration exposing all endpoints
2. No authentication or session management
3. Missing CSRF protection
4. Hardcoded test user credentials accessible to all clients

---

## 1. CORS Configuration ⚠️ CRITICAL

### Issues Identified

**File:** `backend/src/finance_aggregator/http/middleware.clj:17-48`

```clojure
"Access-Control-Allow-Origin" "*"
"Access-Control-Allow-Headers" "*"
```

**Risk:** CRITICAL
- **Wildcard origin (`*`)** allows any website to make requests to your API
- Credentials from one user's browser could be accessed by malicious sites
- No protection against Cross-Site Request Forgery (CSRF)
- Access tokens and financial data exposed to any domain

### Attack Scenario
1. User logs into your finance aggregator at `app.example.com`
2. User visits malicious site `evil.com`
3. `evil.com` JavaScript makes requests to `app.example.com/api/plaid/accounts`
4. Browser sends cookies/credentials automatically
5. Attacker receives user's financial data

### Recommendations

#### Short-term (Pre-Production)
```clojure
(def allowed-origins
  #{"http://localhost:5173"
    "http://localhost:8080"
    "https://your-production-domain.com"})

(defn wrap-cors [handler]
  (fn [request]
    (let [origin (get-in request [:headers "origin"])
          allowed? (contains? allowed-origins origin)]
      (if (= :options (:request-method request))
        {:status 200
         :headers (when allowed?
                   {"Access-Control-Allow-Origin" origin
                    "Access-Control-Allow-Methods" "GET, POST, PUT, DELETE, OPTIONS"
                    "Access-Control-Allow-Headers" "Content-Type, Authorization"
                    "Access-Control-Allow-Credentials" "true"
                    "Access-Control-Max-Age" "3600"})}
        (let [response (handler request)]
          (update response :headers
                  (fn [headers]
                    (if allowed?
                      (merge {"Access-Control-Allow-Origin" origin
                              "Access-Control-Allow-Credentials" "true"}
                             headers)
                      headers))))))))
```

#### Long-term (Production)
- Use environment-based configuration for allowed origins
- Implement same-origin policy where possible
- Use `Access-Control-Allow-Credentials: true` with specific origins
- Remove wildcard CORS entirely

---

## 2. Plaid OAuth Flow Security ✅ GOOD / ⚠️ GAPS

### What's Working Well

**Token Exchange Flow** (`backend/src/finance_aggregator/http/handlers/plaid.clj:34-60`)
- ✅ Public token is exchanged server-side (not exposed to frontend beyond single use)
- ✅ Access token never sent to frontend
- ✅ Encrypted storage of access tokens (AES-256-GCM)
- ✅ Uses official Plaid Java SDK
- ✅ Proper error handling with ex-info

**Frontend Implementation** (`frontend/app/routes/plaid-test.tsx`)
- ✅ Plaid Link SDK loaded from official CDN
- ✅ Link token created server-side
- ✅ Public token sent to backend immediately after OAuth callback
- ✅ No sensitive tokens persisted in localStorage or sessionStorage

### Security Gaps

#### 2.1 Missing State Parameter
**File:** `backend/src/finance_aggregator/plaid/client.clj:34-58`

**Issue:** No OAuth state parameter to prevent CSRF attacks on the OAuth flow itself.

**Risk:** MEDIUM
- Attacker could initiate OAuth flow and trick user into authorizing attacker's link token
- Session fixation attacks possible

**Recommendation:**
```clojure
(defn create-link-token [plaid-config user-id]
  (let [state (generate-secure-random-state)  ;; Add CSRF state
        api-client (create-api-client plaid-config)
        ;; Store state in session/db associated with user
        request (-> (LinkTokenCreateRequest.)
                    (.user user)
                    (.clientName "Finance Aggregator")
                    (.products [Products/TRANSACTIONS])
                    (.countryCodes [CountryCode/US])
                    (.language "en")
                    ;; Note: Plaid Link handles state differently via redirect_uri
                    ;; For Plaid, use webhook verification instead
                    )]
    ;; ...
```

**Note:** Plaid Link's hosted UI mitigates traditional OAuth state attacks, but implementing webhook signature verification is critical (see §2.3).

#### 2.2 No Webhook Signature Verification
**File:** Not implemented

**Issue:** When Plaid sends webhooks (account updates, transaction updates), there's no verification that webhooks are from Plaid.

**Risk:** HIGH
- Attacker could send fake webhooks to trigger data updates
- Data integrity compromised

**Recommendation:**
```clojure
(ns finance-aggregator.http.handlers.plaid-webhook
  (:require [buddy.core.mac :as mac]
            [buddy.core.codecs :as codecs]))

(defn verify-plaid-signature [request plaid-webhook-secret]
  (let [signature (get-in request [:headers "plaid-verification"])
        body (:body request)
        expected (-> (mac/hash body {:key plaid-webhook-secret :alg :hmac+sha256})
                     (codecs/bytes->hex))]
    (= signature expected)))

(defn webhook-handler [{:keys [plaid-webhook-secret]} request]
  (when-not (verify-plaid-signature request plaid-webhook-secret)
    (throw (ex-info "Invalid webhook signature" {:type :forbidden})))
  ;; Process webhook...
  )
```

#### 2.3 No Replay Attack Protection
**File:** `backend/src/finance_aggregator/http/handlers/plaid.clj:34-60`

**Issue:** Public token can potentially be replayed if intercepted before use.

**Risk:** LOW (Plaid tokens are single-use, but no explicit checking)

**Recommendation:**
- Add nonce/timestamp validation
- Log all token exchanges with timestamps
- Implement idempotency keys

---

## 3. Authentication & Authorization ⚠️ CRITICAL

### Issue: No Authentication System

**Files:**
- `backend/src/finance_aggregator/http/middleware.clj` (no auth middleware)
- `backend/src/finance_aggregator/http/handlers/plaid.clj:14-16` (hardcoded user)

**Current State:**
```clojure
(def ^:private hardcoded-user-id
  "Hardcoded user ID for Phase 2/3 testing. Will be removed in Phase 7."
  "test-user")
```

**Risk:** CRITICAL
- **Any client can access any endpoint**
- **No user identity verification**
- **All clients share same "test-user" credentials**
- **Financial data accessible without authentication**

### Attack Scenarios

#### Scenario 1: Data Theft
```bash
# Anyone can fetch financial data
curl http://localhost:8080/api/plaid/accounts
# Returns all accounts for "test-user"
```

#### Scenario 2: Credential Poisoning
```bash
# Attacker links their own bank account to your app
curl -X POST http://localhost:8080/api/plaid/exchange-token \
  -H "Content-Type: application/json" \
  -d '{"publicToken": "attacker-token"}'
# Now all users see attacker's financial data
```

### Recommendations

#### Phase 1: Session-Based Authentication (Quickest)

**Add to middleware:**
```clojure
(ns finance-aggregator.http.middleware.auth
  (:require [buddy.auth :refer [authenticated?]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]))

(def auth-backend (session-backend))

(defn wrap-auth [handler]
  (-> handler
      (wrap-authentication auth-backend)
      (wrap-authorization auth-backend)))

(defn require-auth [handler]
  (fn [request]
    (if (authenticated? request)
      (handler request)
      {:status 401
       :headers {"Content-Type" "application/json"}
       :body "{\"error\":\"Authentication required\"}"})))
```

**Apply to Plaid routes:**
```clojure
["/plaid"
 {:middleware [require-auth]}  ;; Add auth requirement
 ["/create-link-token" {:post {:handler (handlers/create-link-token-handler deps)}}]
 ;; ...
]
```

#### Phase 2: JWT Tokens (Production)

**Benefits:**
- Stateless authentication
- Mobile app support
- Microservices-friendly

**Implementation:**
```clojure
(ns finance-aggregator.http.middleware.jwt
  (:require [buddy.sign.jwt :as jwt]))

(defn wrap-jwt-auth [handler secret]
  (fn [request]
    (let [token (get-in request [:headers "authorization"])
          token (when token (str/replace token "Bearer " ""))
          claims (when token
                   (try (jwt/unsign token secret)
                        (catch Exception _ nil)))]
      (if claims
        (handler (assoc request :user claims))
        {:status 401
         :body "{\"error\":\"Invalid or missing token\"}"}))))
```

---

## 4. Input Validation ⚠️ GAPS

### 4.1 Missing Input Validation

**File:** `backend/src/finance_aggregator/http/handlers/plaid.clj:83-113`

**Current State:**
```clojure
(let [start-date (:startDate params)
      end-date (:endDate params)]
  (when-not (and start-date end-date)
    (throw (ex-info "startDate and endDate are required" ...)))
  ;; No validation of date format or ranges!
  (plaid/fetch-transactions plaid-config access-token start-date end-date))
```

**Risks:**
- SQL injection via date strings (LOW - using Java LocalDate parser, but still risky)
- Date range abuse (request 10 years of transactions → DoS)
- Invalid date formats cause 500 errors

**Recommendation:**
```clojure
(ns finance-aggregator.http.validation
  (:require [clojure.spec.alpha :as s]
            [java.time LocalDate]))

(s/def ::date-string
  (s/and string?
         #(re-matches #"\d{4}-\d{2}-\d{2}" %)
         #(try (LocalDate/parse %) true
               (catch Exception _ false))))

(s/def ::date-range
  (s/and (s/keys :req-un [::startDate ::endDate])
         (fn [{:keys [startDate endDate]}]
           (let [start (LocalDate/parse startDate)
                 end (LocalDate/parse endDate)
                 diff (.until start end java.time.temporal.ChronoUnit/DAYS)]
             (&lt;= 0 diff 365)))))  ;; Max 1 year range

(defn validate-date-range! [params]
  (when-not (s/valid? ::date-range params)
    (throw (ex-info "Invalid date range"
                    {:type :bad-request
                     :errors (s/explain-data ::date-range params)}))))
```

### 4.2 SQL Injection via Query Endpoint

**File:** `backend/src/finance_aggregator/server.clj:24-34`

**Critical Issue:**
```clojure
(defn- query-handler [query-str]
  (try
    (let [query (read-string query-str)  ;; DANGEROUS!
          results (d/q query (d/db db/conn))]
      (json-response {:success true :data results}))
```

**Risk:** CRITICAL
- Arbitrary code execution via `read-string`
- Full database access
- Server compromise possible

**Attack Example:**
```bash
curl -X POST http://localhost:8080/api/query \
  -d '{"query": "#=(java.lang.Runtime/getRuntime) (.exec \"rm -rf /\")"}'
```

**Recommendation:**
```clojure
;; Option 1: Remove this endpoint entirely (RECOMMENDED)
;; Option 2: Use allowlist of predefined queries
(def allowed-queries
  {:list-transactions '[:find [(pull ?e [*]) ...]
                        :where [?e :transaction/external-id _]]
   :stats '[:find (count ?e) :where [?e :transaction/external-id _]]})

(defn query-handler [query-name]
  (when-let [query (get allowed-queries (keyword query-name))]
    (let [results (d/q query (d/db db/conn))]
      (json-response {:success true :data results}))))
```

---

## 5. Secrets Management ✅ EXCELLENT

### What's Working Well

**File:** `backend/src/finance_aggregator/lib/secrets.clj`

- ✅ Age encryption for secrets at rest
- ✅ Private key stored in `~/.config/finance-aggregator/key.txt` (outside repo)
- ✅ Encrypted secrets file in repo (`secrets.edn.age`)
- ✅ Strong validation and error messages
- ✅ No plaintext secrets in code or environment variables

**File:** `backend/src/finance_aggregator/lib/encryption.clj`

- ✅ AES-256-GCM for credential encryption
- ✅ Proper IV generation (12 bytes, SecureRandom)
- ✅ 128-bit authentication tag
- ✅ Base64 encoding for storage
- ✅ No hardcoded encryption keys

### Minor Recommendations

#### 5.1 Key Rotation Strategy
**Add to documentation:**
```markdown
## Key Rotation Procedure

### Encryption Key Rotation (Quarterly)
1. Generate new encryption key: `(encryption/generate-encryption-key)`
2. Add to secrets with new name: `:database-encryption-key-v2`
3. Re-encrypt all credentials with new key
4. Update application to use new key
5. Verify all credentials work
6. Remove old key after grace period
```

#### 5.2 Secrets Auditing
```clojure
(defn load-secrets-with-audit []
  (let [secrets (load-secrets)
        audit-entry {:timestamp (java.util.Date.)
                     :user (System/getProperty "user.name")
                     :action :secrets-loaded}]
    ;; Log to audit file
    (spit "logs/secrets-audit.log"
          (pr-str audit-entry)
          :append true)
    secrets))
```

---

## 6. Error Handling & Information Disclosure ⚠️ MODERATE

### Issues

**File:** `backend/src/finance_aggregator/http/errors.clj:51-73`

**Current behavior:**
```clojure
(defn exception->response [ex]
  (let [message (.getMessage ex)
        data (when (instance? clojure.lang.ExceptionInfo ex)
               (ex-data ex))]
    (error-response status message response-data)))  ;; Exposes ex-data!
```

**Risk:** MODERATE
- Stack traces and internal data exposed in error responses
- Attacker can learn about system internals
- Ex-data may contain sensitive information

**Example:**
```json
{
  "success": false,
  "error": "Failed to decrypt credential",
  "hint": "This may indicate data corruption or wrong encryption key",
  "identity-file": "/Users/kira/.config/finance-aggregator/key.txt",
  "encrypted-file": "resources/secrets.edn.age"
}
```

### Recommendation

```clojure
(defn sanitize-error-data [data]
  ;; Remove sensitive keys
  (dissoc data :identity-file :encrypted-file :access-token
          :secret :encryption-key))

(defn exception->response [ex]
  (let [message (.getMessage ex)
        data (when (instance? clojure.lang.ExceptionInfo ex)
               (sanitize-error-data (ex-data ex)))
        error-type (:type data)
        status (get type->status error-type 500)
        ;; In production, hide internal errors
        public-message (if (= status 500)
                         "Internal server error"
                         message)]
    (error-response status public-message
                    (when (not= status 500) data))))
```

---

## 7. CSRF Protection ⚠️ MISSING

### Issue

**File:** All POST/PUT/DELETE endpoints

**Current State:** No CSRF tokens or SameSite cookie protection

**Risk:** HIGH
- Attackers can make state-changing requests from malicious sites
- User unknowingly executes actions

### Attack Scenario
```html
<!-- evil.com -->
<form action="http://app.example.com/api/categories" method="POST">
  <input type="hidden" name="intent" value="delete-category">
  <input type="hidden" name="id" value="123">
</form>
<script>document.forms[0].submit();</script>
```

### Recommendations

#### Option 1: Double Submit Cookie Pattern
```clojure
(ns finance-aggregator.http.middleware.csrf
  (:require [buddy.core.codecs :as codecs]))

(defn generate-csrf-token []
  (-> (byte-array 32)
      (doto (.nextBytes (java.security.SecureRandom.)))
      (codecs/bytes->hex)))

(defn wrap-csrf [handler]
  (fn [request]
    (if (#{:post :put :delete} (:request-method request))
      (let [token-header (get-in request [:headers "x-csrf-token"])
            token-cookie (get-in request [:cookies "csrf-token" :value])]
        (if (and token-header token-cookie (= token-header token-cookie))
          (handler request)
          {:status 403
           :body "{\"error\":\"CSRF token validation failed\"}"}))
      (handler request))))
```

#### Option 2: SameSite Cookies (Simpler)
```clojure
;; When setting session cookies
{:status 200
 :cookies {"session" {:value token
                      :http-only true
                      :secure true  ;; HTTPS only
                      :same-site :strict}}
 :body "..."}
```

---

## 8. Transport Security ⚠️ DEV ONLY

### Current State
- HTTP only (no HTTPS)
- Development environment

### Pre-Production Requirements

#### 8.1 HTTPS Enforcement
```clojure
(defn wrap-https-redirect [handler]
  (fn [request]
    (if (and (not= "https" (:scheme request))
             (not (contains? #{"localhost" "127.0.0.1"}
                             (:server-name request))))
      {:status 301
       :headers {"Location" (str "https://" (:server-name request) (:uri request))}}
      (handler request))))
```

#### 8.2 Security Headers
```clojure
(defn wrap-security-headers [handler]
  (fn [request]
    (let [response (handler request)]
      (update response :headers merge
              {"Strict-Transport-Security" "max-age=31536000; includeSubDomains"
               "X-Frame-Options" "DENY"
               "X-Content-Type-Options" "nosniff"
               "X-XSS-Protection" "1; mode=block"
               "Content-Security-Policy" (str
                 "default-src 'self'; "
                 "script-src 'self' https://cdn.plaid.com; "
                 "connect-src 'self' https://*.plaid.com; "
                 "frame-src https://*.plaid.com; "
                 "img-src 'self' data: https:; "
                 "style-src 'self' 'unsafe-inline'")
               "Referrer-Policy" "strict-origin-when-cross-origin"}))))
```

---

## 9. Rate Limiting ⚠️ MISSING

### Issue
No rate limiting on any endpoint, including:
- `/api/plaid/create-link-token` (expensive Plaid API call)
- `/api/plaid/transactions` (could be used for DoS)

### Recommendation

```clojure
(ns finance-aggregator.http.middleware.rate-limit
  (:require [clj-time.core :as t]))

(def rate-limits (atom {}))

(defn rate-limit-key [request]
  (or (get-in request [:session :user-id])
      (get-in request [:headers "x-forwarded-for"])
      (:remote-addr request)))

(defn rate-limit [max-requests window-seconds]
  (fn [handler]
    (fn [request]
      (let [key (rate-limit-key request)
            now (t/now)
            window-start (t/minus now (t/seconds window-seconds))
            recent-requests (filter #(t/after? (:timestamp %) window-start)
                                    (get @rate-limits key []))]
        (if (&gt;= (count recent-requests) max-requests)
          {:status 429
           :headers {"Retry-After" (str window-seconds)}
           :body "{\"error\":\"Rate limit exceeded\"}"}
          (do
            (swap! rate-limits update key
                   #(conj (vec recent-requests) {:timestamp now}))
            (handler request)))))))

;; Apply to expensive endpoints
["/plaid"
 ["/create-link-token" {:post {:handler (handlers/create-link-token-handler deps)
                               :middleware [(rate-limit 10 3600)]}}]]  ;; 10/hour
```

---

## 10. Frontend Security

### Issues in Frontend

#### 10.1 XSS Prevention
**File:** `frontend/app/routes/plaid-test.tsx:220-222`

**Current:**
```tsx
<pre className="json-display">{linkToken}</pre>
```

**Risk:** LOW (linkToken is from your backend, but still risky)

**Recommendation:** React auto-escapes, but add explicit sanitization for user-controlled data.

#### 10.2 Dependency Security
**Missing:** No dependency vulnerability scanning

**Recommendation:**
```bash
# Add to package.json scripts
"audit": "pnpm audit --audit-level=moderate",
"audit:fix": "pnpm audit --fix"

# Run regularly
pnpm audit
```

#### 10.3 Content Security Policy
**File:** Frontend HTML template

**Add CSP meta tag:**
```html
<meta http-equiv="Content-Security-Policy"
      content="default-src 'self';
               script-src 'self' https://cdn.plaid.com;
               connect-src 'self' https://*.plaid.com http://localhost:8080;
               frame-src https://*.plaid.com;
               style-src 'self' 'unsafe-inline';">
```

---

## 11. Database Security ✅ GOOD

### What's Working

**File:** `backend/src/finance_aggregator/db/credentials.clj`

- ✅ Encrypted credentials at rest (AES-256-GCM)
- ✅ Separate IV per credential
- ✅ Last-used timestamp tracking
- ✅ Credential rotation support (can delete/recreate)

### Recommendations

#### 11.1 Audit Logging
```clojure
(defn get-credential [conn secrets-data institution]
  ;; ... existing code ...

  ;; Add audit log
  (d/transact! conn [{:audit/action :credential-accessed
                      :audit/institution institution
                      :audit/user [:user/id hardcoded-user-id]
                      :audit/timestamp (java.util.Date.)
                      :audit/ip-address (or *request-ip* "unknown")}])

  (encryption/decrypt-credential encrypted-data encryption-key))
```

---

## 12. Logging & Monitoring ⚠️ INSUFFICIENT

### Current State
**File:** `backend/src/finance_aggregator/server.clj:200`

```clojure
(println "Request:" method uri) ;; Debug logging - REMOVE IN PRODUCTION
```

### Recommendations

#### 12.1 Structured Logging
```clojure
(ns finance-aggregator.logging
  (:require [clojure.tools.logging :as log]
            [cheshire.core :as json]))

(defn log-request [request response]
  (log/info
    (json/generate-string
      {:event "http-request"
       :method (:request-method request)
       :uri (:uri request)
       :status (:status response)
       :duration-ms (- (:response-time response) (:request-time request))
       :user-id (get-in request [:session :user-id])
       :ip (or (get-in request [:headers "x-forwarded-for"])
               (:remote-addr request))})))
```

#### 12.2 Security Event Monitoring
```clojure
(defn log-security-event [event-type details]
  (log/warn
    (json/generate-string
      {:event "security-event"
       :type event-type
       :details details
       :timestamp (java.util.Date.)}))
  ;; Alert on critical events
  (when (contains? #{:credential-accessed :auth-failed :rate-limit-exceeded}
                   event-type)
    (send-alert! event-type details)))
```

---

## Priority Recommendations

### P0 - Critical (Fix Before Any Deployment)
1. **Replace wildcard CORS with specific origins** (`middleware.clj:17-48`)
2. **Add authentication system** (session or JWT)
3. **Remove or secure query endpoint** (`server.clj:24-34`)
4. **Add user isolation** (stop using hardcoded test-user)

### P1 - High (Fix Before Beta)
1. **Implement CSRF protection**
2. **Add rate limiting to Plaid endpoints**
3. **Validate all user inputs** (especially date ranges)
4. **Add HTTPS enforcement**
5. **Implement webhook signature verification**

### P2 - Medium (Fix Before Production)
1. **Add security headers** (HSTS, CSP, X-Frame-Options)
2. **Implement audit logging**
3. **Add monitoring and alerting**
4. **Sanitize error messages**
5. **Add dependency scanning**

### P3 - Low (Nice to Have)
1. **Add key rotation procedures**
2. **Implement replay attack protection**
3. **Add CSP to frontend**
4. **Implement proper structured logging**

---

## Testing Security

### Recommended Security Testing

#### 1. OWASP ZAP Scan
```bash
docker run -t owasp/zap2docker-stable zap-baseline.py \
  -t http://localhost:8080 \
  -r zap-report.html
```

#### 2. Dependency Vulnerability Scan
```bash
# Backend
clojure -Sdeps '{:deps {nvd-clojure/nvd-clojure {:mvn/version "4.0.0"}}}' \
  -M -m nvd.task.check

# Frontend
pnpm audit
```

#### 3. Manual Penetration Testing Checklist
- [ ] CSRF token bypasses
- [ ] SQL injection in query endpoint
- [ ] CORS misconfiguration exploitation
- [ ] Authentication bypass attempts
- [ ] Rate limit testing
- [ ] Input validation bypasses
- [ ] Error message information disclosure
- [ ] Webhook signature bypasses

---

## Compliance Considerations

### PCI DSS Relevance
This application handles financial data connections (Plaid credentials) which may fall under PCI DSS scope:

**Required Controls:**
- [ ] Encrypt sensitive data in transit (TLS 1.2+)
- [ ] Encrypt sensitive data at rest (✅ implemented)
- [ ] Implement strong access control (⚠️ missing)
- [ ] Log and monitor access (⚠️ partial)
- [ ] Regular security testing (⚠️ missing)
- [ ] Maintain vulnerability management program

### GDPR/Privacy
**Required:**
- [ ] User consent for data collection
- [ ] Right to access personal data
- [ ] Right to deletion (data portability)
- [ ] Breach notification procedures
- [ ] Privacy policy and terms of service

---

## Conclusion

The application demonstrates **strong cryptographic practices** for secrets and credential management, but has **critical gaps in access control and CORS configuration** that must be addressed before any production deployment.

**The Plaid OAuth integration itself is well-implemented**, following best practices for token exchange and storage. However, **the lack of user authentication** makes all security controls ineffective, as anyone can access any user's financial data.

**Immediate Action Required:**
1. Fix CORS configuration
2. Implement authentication
3. Remove/secure query endpoint
4. Add CSRF protection

With these changes, the application will have a solid security foundation suitable for beta testing. Production deployment will require additional hardening per the P1/P2 recommendations.

---

**Reviewed By:** Claude Sonnet 4.5
**Review Date:** 2025-12-29
**Next Review:** After implementing P0/P1 recommendations

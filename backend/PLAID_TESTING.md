# Plaid Integration Testing Guide

## Phase 1 Implementation Complete

Phase 1 of the Plaid integration has been implemented following TDD practices:

### Completed Components

1. **Plaid Java SDK Dependency** - Added to `deps.edn` (version 25.0.0)
2. **Plaid Client Functions** - Pure API functions in `src/finance_aggregator/plaid/client.clj`:
   - `create-link-token` - Generate link token for Plaid Link initialization
   - `exchange-public-token` - Exchange public token for access token
   - `fetch-accounts` - Retrieve account list
   - `fetch-transactions` - Retrieve transactions for date range
3. **Plaid Config Component** - Added to `system.clj` with lifecycle management
4. **System Configuration** - Added Plaid configuration to `base-system.edn`
5. **Unit Tests** - Test stubs created in `test/finance_aggregator/plaid/client_test.clj`

### Testing in REPL with Sandbox Credentials

To test the Plaid integration, you need Plaid Sandbox credentials:

#### Step 1: Get Plaid Sandbox Credentials

1. Sign up for a free Plaid account at https://dashboard.plaid.com/signup
2. Navigate to Team Settings → Keys
3. Copy your `client_id` and `sandbox` secret

#### Step 2: Add Credentials to Secrets File

This project uses age-encrypted secrets instead of environment variables:

```bash
# Edit your secrets file
bb secrets edit
```

Add or update the Plaid configuration:

```clojure
{:plaid {:client-id "your_client_id_here"
         :secret "your_sandbox_secret_here"
         :environment :sandbox}

 ;; ... other secrets
 }
```

Save and close the editor. Your secrets are automatically encrypted.

See [SECRETS.md](./SECRETS.md) for more details on secrets management.

#### Step 3: Start the REPL

```bash
cd backend
jabba use zulu@21.0.6
clojure -M:repl -m nrepl.cmdline
```

#### Step 4: Test Plaid Functions

```clojure
;; Load dev environment
(dev)

;; Start the system
(go)

;; Get the Plaid config component
(def plaid-config (:finance-aggregator.plaid/config integrant.repl.state/system))

;; Verify config loaded
plaid-config
;; => {:client-id "...", :secret "...", :environment :sandbox}

;; Test 1: Create a link token
(require '[finance-aggregator.plaid.client :as plaid])
(def link-token (plaid/create-link-token plaid-config "test-user-123"))
link-token
;; => "link-sandbox-..."

;; Test 2: Exchange public token (requires completing Plaid Link flow)
;; NOTE: You'll need to use Plaid Link UI to get a public token first
;; For Sandbox testing, use the public token from Plaid Link
(def result (plaid/exchange-public-token plaid-config "public-sandbox-xxx"))
result
;; => {:access_token "access-sandbox-...", :item_id "item-xxx"}

;; Test 3: Fetch accounts
(def accounts (plaid/fetch-accounts plaid-config (:access_token result)))
accounts
;; => [{:account_id "..." :name "Plaid Checking" ...}]

;; Test 4: Fetch transactions
(def transactions (plaid/fetch-transactions
                   plaid-config
                   (:access_token result)
                   "2025-01-01"
                   "2025-11-30"))
transactions
;; => [{:transaction_id "..." :amount 12.50 :name "Coffee Shop" ...}]
```

#### Step 5: Using Plaid Sandbox Test Tokens

Plaid provides special test tokens for Sandbox:

```clojure
;; Use Plaid's test public token (these change periodically)
;; Check https://plaid.com/docs/sandbox/test-credentials/

;; Example with a Sandbox institution
;; After creating link-token, you can use Plaid Link's test mode
;; to complete the flow and get a public_token
```

### Running Unit Tests

The unit tests require valid Plaid Sandbox credentials in your secrets file to pass:

```bash
cd backend
jabba use zulu@21.0.6

# Ensure your secrets file has Plaid credentials
# bb secrets edit

# Run tests
clojure -M:test -m kaocha.runner --focus finance-aggregator.plaid.client-test
```

**Note:** The current tests make real API calls to Plaid Sandbox. They will fail without valid credentials in your secrets file.

### Implementation Notes

#### Pure Functions Architecture

The `plaid/client.clj` module contains only pure functions:
- No side effects beyond API calls
- No component dependencies
- All configuration passed as parameters
- Easy to test and reason about

#### Error Handling

All functions use try-catch with `ex-info` for structured error reporting:

```clojure
(catch Exception e
  (throw (ex-info "Failed to create link token"
                  {:user-id user-id
                   :error (.getMessage e)}
                  e)))
```

#### Plaid SDK Usage

The implementation uses the Plaid Java SDK v25.0.0:

```clojure
;; Create API client
(let [api-keys (doto (HashMap.)
                 (.put "clientId" client-id)
                 (.put "secret" secret))
      api-client (ApiClient. api-keys)]
  (.setPlaidAdapter api-client plaid-adapter)
  api-client)

;; Make API calls
(let [response (.linkTokenCreate plaid-api request)
      result (.body (.execute response))]
  (.getLinkToken result))
```

### Next Steps (Phase 2)

Phase 2 will implement:

1. **Data Transformation** (`plaid/data.clj`)
   - `parse-institution` - Transform Plaid institution → Datalevin schema
   - `parse-account` - Transform Plaid account → Datalevin schema
   - `parse-transaction` - Transform Plaid transaction → Datalevin schema

2. **Service Orchestration** (`plaid/service.clj`)
   - `link-user-account!` - Exchange token + store credential
   - `sync-transactions!` - Fetch, transform, persist

3. **Credential Encryption** (`credentials.clj`)
   - `encrypt-credential` - AES-256-GCM encryption
   - `decrypt-credential` - Decryption
   - `store-credential!` - Save encrypted credential to DB

See ADR-004 for complete implementation plan.

### File Locations

- Implementation: `backend/src/finance_aggregator/plaid/client.clj`
- Tests: `backend/test/finance_aggregator/plaid/client_test.clj`
- System config: `backend/src/finance_aggregator/system.clj`
- Base config: `backend/resources/system/base-system.edn`
- Dependencies: `backend/deps.edn`
- Design doc: `doc/adr/adr-004-plaid-integration.md`

### Troubleshooting

**Issue:** Tests fail with "Cannot invoke Object.getClass() because target is null"
- **Cause:** Missing or invalid Plaid credentials in secrets file
- **Solution:** Run `bb secrets edit` and add Plaid credentials under `:plaid` key

**Issue:** API calls return 401 Unauthorized
- **Cause:** Invalid credentials or wrong environment
- **Solution:** Verify credentials in secrets file are for Sandbox environment (`:environment :sandbox`)

**Issue:** "Plaid credentials not found in secrets"
- **Cause:** Missing `:plaid` key in secrets file
- **Solution:** Run `bb secrets edit` and add the `:plaid` configuration (see Step 2 above)

**Issue:** SLF4J warnings
- **Cause:** No SLF4J implementation in classpath
- **Solution:** This is cosmetic and doesn't affect functionality

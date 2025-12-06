# Plaid Integration Testing Guide

## Implementation Status

### Phase 1: Foundation ✅ Complete
- Plaid Java SDK (v25.0.0) integrated
- Pure API client functions
- Plaid config component with secrets integration
- Unit tests with Sandbox

### Phase 2: Minimal Backend Endpoints ✅ Complete
- **Encryption** (`lib/encryption.clj`) - AES-256-GCM for credential storage
- **Credential Storage** (`db/credentials.clj`) - Encrypted Plaid access tokens in database
- **API Endpoints** (`server.clj`):
  - `POST /api/plaid/create-link-token` - Generate link_token for frontend
  - `POST /api/plaid/exchange-token` - Exchange public_token, store encrypted access_token
  - `GET /api/plaid/accounts` - Fetch accounts using stored credential
  - `POST /api/plaid/transactions` - Fetch transactions for date range
- **Tests**: 20 tests passing (encryption + credentials + client)
- **Hardcoded User**: Uses "test-user" for single-user testing

### Completed Components

1. **Plaid Java SDK Dependency** - Added to `deps.edn` (version 25.0.0)
2. **Plaid Client Functions** - Pure API functions in `src/finance_aggregator/plaid/client.clj`:
   - `create-link-token` - Generate link token for Plaid Link initialization
   - `exchange-public-token` - Exchange public token for access token
   - `fetch-accounts` - Retrieve account list
   - `fetch-transactions` - Retrieve transactions for date range
3. **Encryption Utilities** - AES-256-GCM encryption in `lib/encryption.clj`
4. **Credential Management** - Secure storage in `db/credentials.clj`
5. **API Endpoints** - HTTP handlers in `server.clj`
6. **Plaid Config Component** - Integrated with secrets system
7. **System Configuration** - Plaid configuration in `base-system.edn`
8. **Comprehensive Tests** - Unit tests for all components

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

### Testing API Endpoints

You can test the new Phase 2 endpoints using curl:

```bash
# 1. Create link token
curl -X POST http://localhost:8080/api/plaid/create-link-token
# Returns: {"success": true, "data": {"linkToken": "link-sandbox-..."}}

# 2. Exchange public token (after completing Plaid Link flow)
curl -X POST http://localhost:8080/api/plaid/exchange-token \
  -H "Content-Type: application/json" \
  -d '{"publicToken": "public-sandbox-..."}'
# Returns: {"success": true, "data": {"access_token": "...", "item_id": "..."}}
# Also stores encrypted credential in database

# 3. Fetch accounts (uses stored credential)
curl http://localhost:8080/api/plaid/accounts
# Returns: {"success": true, "data": [{account data}]}

# 4. Fetch transactions (uses stored credential)
curl -X POST http://localhost:8080/api/plaid/transactions \
  -H "Content-Type: application/json" \
  -d '{"startDate": "2025-01-01", "endDate": "2025-11-30"}'
# Returns: {"success": true, "data": [{transaction data}]}
```

### Next Steps (Phase 3+)

Upcoming phases:

1. **Phase 3: Frontend Plaid Link** - Minimal UI for manual testing at `/plaid-test`
2. **Phase 4: Data Transformation** - Transform Plaid JSON to Datalevin schema
3. **Phase 5: Service Orchestration** - Persist data to database
4. **Phase 6: Dashboard Integration** - Add to main UI
5. **Phase 7: Production Hardening** - Multi-user, validation, error handling

See ADR-004 and the implementation plan for details.

### File Locations

**Phase 1 - Plaid Client:**
- `backend/src/finance_aggregator/plaid/client.clj` - Pure API functions
- `backend/test/finance_aggregator/plaid/client_test.clj` - Client tests

**Phase 2 - Encryption & Endpoints:**
- `backend/src/finance_aggregator/lib/encryption.clj` - AES-256-GCM encryption
- `backend/src/finance_aggregator/db/credentials.clj` - Credential storage
- `backend/src/finance_aggregator/server.clj` - API endpoints (Plaid section)
- `backend/test/finance_aggregator/lib/encryption_test.clj` - Encryption tests
- `backend/test/finance_aggregator/db/credentials_test.clj` - Credentials tests

**Configuration:**
- `backend/src/finance_aggregator/system.clj` - Plaid config component
- `backend/resources/system/base-system.edn` - System configuration
- `backend/deps.edn` - Plaid Java SDK dependency

**Documentation:**
- `doc/adr/adr-004-plaid-integration.md` - Architecture decision record
- Implementation plan: `/Users/kira/.claude/plans/tender-zooming-puffin.md`

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

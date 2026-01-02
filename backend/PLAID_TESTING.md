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

### Phase 3: Frontend Plaid Link Component ✅ Complete
- **Plaid Test Route** (`frontend/app/routes/plaid-test.tsx`) - Full testing UI at `/plaid-test`
- **API Client Functions** (`frontend/app/lib/api.ts`) - Type-safe Plaid API wrapper
- **Plaid Link SDK Integration** - Loaded from CDN, OAuth flow implemented
- **Features**:
  - Link token creation and display
  - Plaid Link modal initialization
  - OAuth success callback handling
  - Token exchange with backend
  - Account fetching with summary + raw JSON display
  - Transaction fetching (last 30 days) with summary + raw JSON display
  - Error handling and status updates
  - Test instructions included
- **Navigation**: Added "Plaid Test" button to main dashboard

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

Add or update the Plaid configuration and database encryption key:

```clojure
{:plaid {:client-id "your_client_id_here"
         :secret "your_sandbox_secret_here"
         :environment :sandbox}

 :database {:encryption-key "REPLACE_WITH_GENERATED_KEY"}

 ;; ... other secrets
 }
```

**Generate the database encryption key:**

In a REPL:
```clojure
(require '[finance-aggregator.lib.encryption :as encryption])
(encryption/generate-encryption-key)
;; Copy the output and paste it as the :encryption-key value above
```

Or via command line:
```bash
cd backend
clojure -M:repl -e "(require '[finance-aggregator.lib.encryption :as encryption]) (println (encryption/generate-encryption-key))"
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

## Testing the Full OAuth Flow (Phase 3)

### Prerequisites
1. Backend server running (see REPL instructions above)
2. Frontend development server running
3. Valid Plaid Sandbox credentials in secrets file

### Step-by-Step Testing Guide

#### 1. Start the Backend (if not already running)

```bash
cd backend
jabba use zulu@21.0.6
clojure -M:repl -m nrepl.cmdline
```

In the REPL:
```clojure
(dev)
(go)
```

The backend should now be running on port 8080.

#### 2. Start the Frontend Development Server

In a new terminal:
```bash
cd frontend
pnpm run dev
```

The frontend should now be running on port 5173 (or similar).

#### 3. Navigate to the Plaid Test Page

1. Open your browser to http://localhost:5173
2. Click the "Plaid Test" button in the main navigation
3. You should see the Plaid Test page with instructions

#### 4. Test the OAuth Flow

**Step 1: Create Link Token**
- The page automatically creates a link token on load
- You should see the link token displayed on the page
- If there's an error, check your Plaid credentials in the backend secrets file

**Step 2: Open Plaid Link**
- Click the "Open Plaid Link" button
- The Plaid Link modal should open
- Click "Continue" in the modal

**Step 3: Select Test Institution**
- Search for or select any test institution (e.g., "First Platypus Bank")
- Click on the institution

**Step 4: Enter Sandbox Credentials**
- Username: `user_good`
- Password: `pass_good`
- Click "Submit"

**Step 5: Select Accounts**
- Select one or more accounts
- Click "Continue"

**Step 6: Complete OAuth Flow**
- The modal should close automatically
- You should see "Successfully linked account!" status message
- The "Exchange Result" section should appear with access token info

**Step 7: Fetch Accounts**
- Click the "Fetch Accounts" button
- You should see a summary (e.g., "2 account(s) found")
- Click "View Raw Response" to see the full Plaid account data

**Step 8: Fetch Transactions**
- Click the "Fetch Transactions (Last 30 Days)" button
- You should see a summary (e.g., "15 transaction(s) found")
- Click "View Raw Response" to see the full Plaid transaction data

### Expected Results

After successful testing, you should see:
1. Link token displayed (starts with `link-sandbox-`)
2. Access token obtained and stored (backend logs will show credential saved)
3. Account data returned from Plaid API
4. Transaction data returned from Plaid API

### Troubleshooting

**"Failed to create link token"**
- Check backend is running on port 8080
- Verify Plaid credentials in `bb secrets edit`
- Check backend logs for errors

**"Plaid SDK not loaded yet"**
- Wait a few seconds for the CDN script to load
- Refresh the page if needed

**Modal doesn't open**
- Check browser console for errors
- Verify Plaid Link SDK loaded (check Network tab)
- Try refreshing the link token

**"Failed to exchange token"**
- Check backend logs for errors
- Verify the public token was received
- Check database connection

**No accounts/transactions returned**
- Verify you completed the OAuth flow successfully
- Check backend logs for API errors
- Try linking again with a different test institution

### Next Steps (Phase 4+)

Upcoming phases:

1. **Phase 4: Data Transformation** - Transform Plaid JSON to Datalevin schema
2. **Phase 5: Service Orchestration** - Persist data to database
3. **Phase 6: Dashboard Integration** - Add to main UI
4. **Phase 7: Production Hardening** - Multi-user, validation, error handling

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

**Phase 3 - Frontend UI:**
- `frontend/app/routes/plaid-test.tsx` - Plaid test route component
- `frontend/app/lib/api.ts` - Plaid API client functions (added to existing file)
- `frontend/app/styles/pages/plaid-test.css` - Plaid test page styles
- `frontend/app/routes.ts` - Route configuration (updated)
- `frontend/app/routes/home.tsx` - Main dashboard (added Plaid Test navigation)

**Configuration:**
- `backend/src/finance_aggregator/system.clj` - Plaid config component
- `backend/resources/system/base-system.edn` - System configuration
- `backend/deps.edn` - Plaid Java SDK dependency

**Documentation:**
- `doc/adr/adr-004-plaid-integration.md` - Architecture decision record
- `backend/PLAID_TESTING.md` - This file (comprehensive testing guide)

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

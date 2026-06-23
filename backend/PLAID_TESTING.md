# Plaid Backend Smoke-Testing Guide

This guide covers exercising the Plaid integration from the backend only — via
the REPL and `curl` against the running HTTP server. The browser entry point for
linking accounts is the server-rendered Datastar/SSR app (the `/setup` page);
this doc focuses on the backend client + endpoints rather than that UI.

For end-to-end account/transaction sync testing, see
[PLAID_SYNC_TESTING.md](./PLAID_SYNC_TESTING.md).

## Implementation Status

The Plaid integration is implemented end to end on the backend:

- **API client** (`plaid/client.clj`) — pure functions over the Plaid Java SDK
- **Encryption** (`lib/encryption.clj`) — AES-256-GCM for credential storage
- **Credential storage** (`db/credentials.clj`) — encrypted Plaid access tokens in the database
- **Data transformation** (`plaid/data.clj`, `plaid/provider.clj`) — Plaid responses normalized to the canonical schema
- **Sync orchestration & persistence** (`plaid/service.clj`) — account and cursor-based transaction sync into Datalevin
- **HTTP endpoints** — routes in `http/routes/plaid.clj`, handlers in `http/handlers/plaid.clj`
- **Plaid config component** — integrated with the secrets system
- **Hardcoded user** — uses `"test-user"` for single-user operation

### Public client functions (`plaid/client.clj`)

- `create-link-token` — generate a link token for Plaid Link initialization
- `exchange-public-token` — exchange a public token for an access token
- `fetch-accounts` — retrieve the account list
- `fetch-transactions` — retrieve transactions for a date range
- `fetch-item` — retrieve Item metadata
- `fetch-institution` — retrieve institution details
- `sync-transactions` — cursor-based `/transactions/sync` paging

### HTTP endpoints (`http/routes/plaid.clj`, under `/api`)

```
POST   /api/plaid/create-link-token            Create Plaid Link token
POST   /api/plaid/exchange-token               Exchange public token & store credential
GET    /api/plaid/items                         List linked Plaid Items
DELETE /api/plaid/items/:item-id               Remove a linked Item
GET    /api/plaid/items/:item-id/sync-status   Per-item sync status (polling)
POST   /api/plaid/items/:item-id/sync          Sync a single Item
POST   /api/plaid/items/:item-id/reset-sync    Reset a single Item's sync cursor
DELETE /api/plaid/credential                   Delete stored credential (legacy)
GET    /api/plaid/accounts                      Fetch accounts (uses stored credential)
POST   /api/plaid/transactions                 Fetch transactions (uses stored credential)
POST   /api/plaid/sync-accounts                Sync accounts to database
POST   /api/plaid/sync-transactions            Sync transactions to database
POST   /api/plaid/sync-month-transactions      Sync a specific month's transactions
```

## Testing in the REPL with Sandbox Credentials

To test the Plaid integration you need Plaid Sandbox credentials.

### Step 1: Get Plaid Sandbox Credentials

1. Sign up for a free Plaid account at https://dashboard.plaid.com/signup
2. Navigate to Team Settings → Keys
3. Copy your `client_id` and `sandbox` secret

### Step 2: Add Credentials to Secrets File

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

### Step 3: Start the REPL

```bash
cd backend
jabba use zulu@25.0.3
clojure -M:repl -m nrepl.cmdline
```

### Step 4: Test Plaid Functions

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

;; Test 2: Exchange public token (requires completing the Plaid Link flow)
;; NOTE: You'll need a public token from the Plaid Link UI first.
;; For Sandbox testing, use the public token from Plaid Link.
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

### Step 5: Using Plaid Sandbox Test Tokens

Plaid provides special test tokens for Sandbox:

```clojure
;; Use Plaid's test public token (these change periodically)
;; Check https://plaid.com/docs/sandbox/test-credentials/

;; Example with a Sandbox institution
;; After creating a link-token, you can use Plaid Link's test mode
;; to complete the flow and get a public_token.
```

## Running Unit Tests

The unit tests require valid Plaid Sandbox credentials in your secrets file to pass:

```bash
cd backend
jabba use zulu@25.0.3

# Ensure your secrets file has Plaid credentials
# bb secrets edit

# Run tests
clojure -M:test -m kaocha.runner --focus finance-aggregator.plaid.client-test
```

**Note:** The current tests make real API calls to Plaid Sandbox. They will fail
without valid credentials in your secrets file.

## Testing API Endpoints with curl

With the server running (`bb dev` from the project root, or the REPL `(go)`), you
can exercise the Plaid HTTP endpoints directly:

```bash
# 1. Create link token
curl -X POST http://localhost:8080/api/plaid/create-link-token
# Returns: {"success": true, "data": {"linkToken": "link-sandbox-..."}}

# 2. Exchange public token (after completing the Plaid Link flow)
curl -X POST http://localhost:8080/api/plaid/exchange-token \
  -H "Content-Type: application/json" \
  -d '{"publicToken": "public-sandbox-..."}'
# Returns: {"success": true, "data": {"access_token": "...", "item_id": "..."}}
# Also stores an encrypted credential in the database

# 3. Fetch accounts (uses stored credential)
curl http://localhost:8080/api/plaid/accounts
# Returns: {"success": true, "data": [{account data}]}

# 4. Fetch transactions (uses stored credential)
curl -X POST http://localhost:8080/api/plaid/transactions \
  -H "Content-Type: application/json" \
  -d '{"startDate": "2025-01-01", "endDate": "2025-11-30"}'
# Returns: {"success": true, "data": [{transaction data}]}

# 5. Persist a sync to the database
curl -X POST http://localhost:8080/api/plaid/sync-accounts
curl -X POST http://localhost:8080/api/plaid/sync-transactions
```

> The `public_token` in steps 1–2 comes from completing the Plaid Link flow.
> In the running app this happens in the browser via the Datastar/SSR `/setup`
> page; for backend-only testing, obtain a Sandbox public token from Plaid Link's
> test mode and feed it to `/api/plaid/exchange-token`.

## Implementation Notes

### Pure Functions Architecture

The `plaid/client.clj` module contains only pure functions:
- No side effects beyond API calls
- No component dependencies
- All configuration passed as parameters
- Easy to test and reason about

### Error Handling

All functions use try-catch with `ex-info` for structured error reporting:

```clojure
(catch Exception e
  (throw (ex-info "Failed to create link token"
                  {:user-id user-id
                   :error (.getMessage e)}
                  e)))
```

### Plaid SDK Usage

The implementation uses the Plaid Java SDK v35.0.0:

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

## File Locations

**Plaid client:**
- `backend/src/finance_aggregator/plaid/client.clj` - Pure API functions
- `backend/test/finance_aggregator/plaid/client_test.clj` - Client tests

**Encryption & credentials:**
- `backend/src/finance_aggregator/lib/encryption.clj` - AES-256-GCM encryption
- `backend/src/finance_aggregator/db/credentials.clj` - Credential storage
- `backend/test/finance_aggregator/lib/encryption_test.clj` - Encryption tests
- `backend/test/finance_aggregator/db/credentials_test.clj` - Credentials tests

**Data transformation & sync:**
- `backend/src/finance_aggregator/plaid/data.clj` - Plaid -> canonical transforms
- `backend/src/finance_aggregator/plaid/provider.clj` - `:plaid` provider seam
- `backend/src/finance_aggregator/plaid/service.clj` - Sync orchestration & persistence

**HTTP endpoints:**
- `backend/src/finance_aggregator/http/routes/plaid.clj` - Route definitions
- `backend/src/finance_aggregator/http/handlers/plaid.clj` - Request handlers

**Configuration:**
- `backend/src/finance_aggregator/system.clj` - Plaid config component
- `backend/resources/system/base-system.edn` - System configuration
- `backend/deps.edn` - Plaid Java SDK dependency

**Documentation:**
- `doc/adr/adr-004-plaid-integration.md` - Architecture decision record
- `backend/PLAID_TESTING.md` - This file (backend smoke-testing guide)
- `backend/PLAID_SYNC_TESTING.md` - Manual sync-testing guide

## Troubleshooting

**Issue:** Tests fail with "Cannot invoke Object.getClass() because target is null"
- **Cause:** Missing or invalid Plaid credentials in secrets file
- **Solution:** Run `bb secrets edit` and add Plaid credentials under `:plaid` key

**Issue:** API calls return 401 Unauthorized
- **Cause:** Invalid credentials or wrong environment
- **Solution:** Verify credentials in the secrets file are for the Sandbox environment (`:environment :sandbox`)

**Issue:** "Plaid credentials not found in secrets"
- **Cause:** Missing `:plaid` key in secrets file
- **Solution:** Run `bb secrets edit` and add the `:plaid` configuration (see Step 2 above)

**Issue:** SLF4J warnings
- **Cause:** No SLF4J implementation in classpath
- **Solution:** This is cosmetic and doesn't affect functionality

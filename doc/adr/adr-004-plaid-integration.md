# ADR-004: Plaid Integration for Transaction Data

**Date:** 2025-11-30
**Status:** Accepted
**Supersedes:** SimpleFIN integration (partial)

## Context

The finance aggregator currently uses SimpleFIN as the data source for bank accounts and transactions. While SimpleFIN provides a clean API and was useful for initial development, I've encountered data integrity issues that make it unsuitable for production use.

### Problems with SimpleFIN

1. **Data Integrity Issues** - Inconsistent and missing transaction data
2. **Limited Institution Support** - Fewer supported banks compared to alternatives
3. **Reliability Concerns** - Service availability and data freshness issues

### Requirements for New Integration

1. **Reliable Data** - Consistent, accurate transaction data
2. **Wide Institution Support** - Support for major banks and financial institutions
3. **Maintain Current Schema** - Don't break existing data model and frontend
4. **Multi-User Ready** - Support user-scoped credentials (already in schema)
5. **Secure Credential Storage** - Encrypted token storage (already implemented)

### Plaid Advantages

- **Industry Standard** - Used by major fintech companies (Venmo, Robinhood, etc.)
- **12,000+ Institutions** - Comprehensive bank coverage
- **Well-Documented API** - Extensive documentation and SDKs
- **Sandbox Environment** - Free testing environment
- **Active Development** - Regular updates and improvements
- **OAuth-Based Security** - Industry-standard authentication flow

## Decision

We will integrate Plaid as the primary data source for transaction aggregation, replacing SimpleFIN. The existing database schema requires no changes - only the integration layer needs to be replaced.

### Current Backend State (Pre-Plaid)

Based on ADR-003 Phase 1 completion:

**Infrastructure Layer (✅ Complete):**
- `sys.clj` - System lifecycle management
- `system.clj` - Integrant component definitions
- `db/core.clj` - Database connection management
- `http/server.clj` - HTTP server component

**Data Layer (✅ Exists, some refactoring planned per ADR-003):**
- `data/schema.clj` - Enhanced schema with user scoping, credential storage
- `db.clj` - Global connection and `insert!` function
- `db/transactions.clj` - Transaction category updates
- `db/categories.clj` - Category CRUD operations

**Application Layer (✅ Exists, will be enhanced):**
- `server.clj` - Monolithic Ring handler with all routes
  - Category endpoints
  - Transaction endpoints
  - Stats endpoint
  - CORS handling

**Integration Layer (✅ SimpleFIN only):**
- `simplefin/client.clj` - SimpleFIN API client
- `simplefin/data.clj` - SimpleFIN data transformations

**Missing (will create for Plaid):**
- ❌ `credentials.clj` - Encryption/decryption utilities
- ❌ `plaid/*` namespace - All Plaid integration code
- ❌ Plaid endpoints in `server.clj`

**Note:** ADR-003 Phase 2-7 plan to refactor into `db/queries.clj`, `api/handlers.clj`, etc., but this is independent of Plaid integration. We'll work with the current structure.

### Technology Stack

- **Plaid Java SDK** (`com.plaid/plaid-java`) - Official Java client library
- **Plaid Link** (React component) - Client-side account linking UI
- **Plaid API** - REST API for transaction and account data
- **Environment** - Start with Sandbox, migrate to Development/Production

### Integration Architecture

Plaid follows an OAuth-like flow that differs significantly from SimpleFIN:

```
┌─────────────────────────────────────────────────────────────┐
│                    PLAID INTEGRATION FLOW                   │
└─────────────────────────────────────────────────────────────┘

1. Backend (Server):
   POST /link/token/create → link_token
   ↓
2. Frontend (Client):
   Plaid Link UI with link_token → user authenticates → public_token
   ↓
3. Backend (Server):
   POST /item/public_token/exchange (public_token) → access_token
   Store encrypted access_token in DB
   ↓
4. Backend (Server):
   POST /transactions/get (access_token) → transactions
   Transform and store in DB
```

### Architecture Layers

Following ADR-003's layered architecture:

```
┌─────────────────────────────────────────────────────────────┐
│                    APPLICATION LAYER                        │
│  server.clj - Add Plaid endpoints to existing handler       │
│    - POST /api/plaid/create-link-token                      │
│    - POST /api/plaid/exchange-token                         │
│    - POST /api/plaid/sync                                   │
│  (Note: ADR-003 plans to refactor into api/handlers.clj)    │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                   INTEGRATION LAYER                         │
│  plaid/client.clj - Pure Plaid API calls                    │
│    - create-link-token                                      │
│    - exchange-public-token                                  │
│    - fetch-accounts                                         │
│    - fetch-transactions                                     │
│                                                             │
│  plaid/data.clj - Data transformation (pure)                │
│    - parse-account: Plaid JSON → :account/* entities        │
│    - parse-institution: Plaid JSON → :institution/* entities│
│    - parse-transaction: Plaid JSON → :transaction/* entities│
│                                                             │
│  plaid/service.clj - Orchestration                          │
│    - link-user-account! (exchange token, store credential)  │
│    - sync-transactions! (fetch, transform, persist)         │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                    DATA ACCESS LAYER                        │
│  db/core.clj - Connection management (existing)             │
│  db.clj - Insert operations (existing)                      │
│  db/transactions.clj - Transaction updates (existing)       │
│  db/categories.clj - Category operations (existing)         │
│  credentials.clj - Encryption/decryption (TO CREATE)        │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                   INFRASTRUCTURE LAYER                      │
│  system.clj - Add Plaid config component                    │
│    ::plaid/config - Client ID, secret, environment          │
└─────────────────────────────────────────────────────────────┘
```

### Module Organization

**New Modules:**

```
backend/src/finance_aggregator/
├── plaid/
│   ├── client.clj          # Pure Plaid API functions
│   ├── data.clj            # Plaid JSON → Datalevin schema transformations
│   └── service.clj         # Orchestration with component dependencies
```

**Updated Modules:**

```
backend/src/finance_aggregator/
├── server.clj              # Add Plaid endpoints to existing handler
├── system.clj              # Add ::plaid/config component
├── data/
│   └── schema.clj          # NO CHANGES - already supports both
└── credentials.clj         # CREATE NEW - Encryption/decryption
```

**Frontend (Minimal):**

```
frontend/app/routes/
└── plaid-link.tsx          # Minimal Plaid Link integration component
```

### Data Model Compatibility

The existing schema is **already compatible** with Plaid data. Only field mappings change:

**SimpleFIN Transaction:**
```clojure
{:id "sf-123"
 :posted 1638360000
 :amount "12.50"
 :payee "Coffee Shop"
 :description "Coffee Shop Purchase"}
```

**Plaid Transaction:**
```clojure
{:transaction_id "plaid-xyz"
 :date "2025-11-30"
 :amount 12.50
 :merchant_name "Starbucks"
 :name "Coffee Shop"}
```

**Both Transform To:**
```clojure
{:transaction/external-id "..." ; sf-123 or plaid-xyz
 :transaction/posted-date #inst "2025-11-30"
 :transaction/amount 12.50M
 :transaction/payee "Starbucks" or "Coffee Shop"
 :transaction/description "Coffee Shop" or "Coffee Shop Purchase"
 :transaction/account [:account/external-id "..."]
 :transaction/user [:user/id "..."]}
```

### Credential Storage Strategy

The existing `:credential/*` schema supports multiple integration sources:

```clojure
;; SimpleFIN credential (can coexist)
{:credential/id "cred-sf-1"
 :credential/user [:user/id "alice"]
 :credential/institution :simplefin
 :credential/encrypted-data "..."} ; Encrypted access-url

;; Plaid credential
{:credential/id "cred-plaid-1"
 :credential/user [:user/id "alice"]
 :credential/institution :plaid
 :credential/encrypted-data "..."} ; Encrypted access_token
```

This allows:
- **Coexistence**: Keep SimpleFIN during migration
- **Multiple Sources**: User can link accounts from both
- **Easy Migration**: Switch by changing which credential to use
- **Per-User Control**: Different users can use different sources

### Component Configuration

**Base Configuration** (`resources/system/base-system.edn`):
```clojure
{:finance-aggregator.plaid/config
 {:client-id #env "PLAID_CLIENT_ID"
  :secret #env "PLAID_SECRET"
  :environment :sandbox}} ; or :development, :production
```

**Environment Variables Required:**
```bash
PLAID_CLIENT_ID=your_client_id_here
PLAID_SECRET=your_secret_here
PLAID_ENVIRONMENT=sandbox  # Start here
```

### Implementation Phases

#### Phase 1: Foundation (Immediate)
1. Add Plaid dependency to `deps.edn`
2. Create Plaid configuration component
3. Create `plaid/client.clj` with core API functions:
   - `create-link-token`
   - `exchange-public-token`
   - `fetch-accounts`
   - `fetch-transactions`
4. Write unit tests for client functions (mock HTTP)

#### Phase 2: Data Transformation
1. Create `plaid/data.clj` with transformation functions:
   - `parse-institution`
   - `parse-account`
   - `parse-transaction`
2. Write comprehensive unit tests (pure functions)
3. Test with Plaid Sandbox sample data

#### Phase 3: Credential Management & Service Orchestration
1. Create `credentials.clj` for encryption/decryption:
   - `encrypt-credential` - AES-256-GCM encryption
   - `decrypt-credential` - Decryption
   - `store-credential!` - Save encrypted credential to DB
   - `get-credential` - Retrieve and decrypt credential
2. Create `plaid/service.clj` with orchestration:
   - `link-user-account!` - Handle token exchange and credential storage
   - `sync-transactions!` - Fetch, transform, persist
3. Write integration tests with Sandbox

#### Phase 4: API Endpoints
1. Add handlers to `server.clj` (existing Ring handler):
   - `POST /api/plaid/create-link-token`
   - `POST /api/plaid/exchange-token`
   - `POST /api/plaid/sync`
2. Add request validation (basic error handling initially)
3. Test full API flow in REPL
4. (Optional: Later refactor into api/handlers.clj per ADR-003 Phase 6)

#### Phase 5: Minimal Frontend Integration
1. Create `plaid-link.tsx` route component
2. Implement Plaid Link SDK initialization
3. Handle success callback (send public_token to backend)
4. Test full OAuth flow end-to-end

#### Phase 6: Production Hardening
1. Add error handling and retry logic
2. Add rate limiting awareness
3. Add transaction pagination support
4. Monitor and log integration health
5. Switch to Development environment for testing
6. Deploy to Production environment

### Key Differences from SimpleFIN

| Aspect | SimpleFIN | Plaid |
|--------|-----------|-------|
| **Authentication** | Setup token → access URL (one-time) | link_token → public_token → access_token (OAuth) |
| **UI Requirement** | None (pure API) | **Required** (Plaid Link component) |
| **Credential Format** | URL with embedded username:password | OAuth access token (JWT-like) |
| **Credential Lifecycle** | Permanent (until revoked) | Can expire, needs refresh |
| **Transaction Query** | By month ranges | By date ranges (max 24 months) |
| **Rate Limits** | Generous | Strict (1 req/sec per access_token) |
| **Data Freshness** | Variable | Near real-time with webhooks |
| **Institution Coverage** | Limited | 12,000+ institutions |
| **Sandbox** | Limited | Full-featured free sandbox |

### Critical Design Decisions

#### 1. Coexistence vs Replacement

**Decision:** Implement Plaid alongside SimpleFIN initially, then deprecate SimpleFIN.

**Rationale:**
- De-risks migration (can rollback if issues arise)
- Allows gradual user migration
- Tests new integration without breaking existing users
- Schema supports multiple credential types per user

#### 2. OAuth Flow Handling

**Decision:** Implement full OAuth flow with frontend Link component.

**Rationale:**
- Plaid requires it (no alternative)
- Industry-standard security practice
- Better UX than credential entry
- Required for production compliance

**Implementation:**
- Backend creates link_token with user context
- Frontend opens Plaid Link modal
- User authenticates with bank
- Frontend receives public_token
- Backend exchanges for access_token
- Backend stores encrypted access_token

#### 3. Token Refresh Strategy

**Decision:** Start without automatic refresh, add later if needed.

**Rationale:**
- Plaid access tokens are long-lived
- Can add refresh logic in Phase 6
- Simplifies initial implementation
- Monitor for token expiration errors

#### 4. Transaction Sync Approach

**Decision:** Manual sync initially (user-triggered), add scheduled sync later.

**Rationale:**
- Matches SimpleFIN pattern (user control)
- Avoids rate limit issues during development
- Can add scheduler component later (per ADR-003)
- Simpler testing and debugging

#### 5. Webhook Integration

**Decision:** Defer webhook integration to future phase.

**Rationale:**
- Webhooks require public endpoint (deployment complexity)
- Manual sync sufficient for MVP
- Can add later for real-time updates
- Focus on core flow first

### Testing Strategy

#### Unit Tests (Pure Functions)

**`plaid/client_test.clj`:**
- Mock HTTP calls with ring-mock
- Test token creation, exchange, data fetching
- Test error handling

**`plaid/data_test.clj`:**
- Test JSON transformations with sample Plaid data
- Verify schema compliance
- Test edge cases (missing fields, nulls)

#### Integration Tests (Sandbox)

**`plaid/service_test.clj`:**
- Use real Plaid Sandbox API
- Test full token exchange flow
- Test transaction sync with test institution
- Verify credential storage encryption
- Test user isolation

#### REPL Testing

```clojure
(go) ; Start system

;; Get Plaid config component
(def plaid-config (:finance-aggregator.plaid/config integrant.repl.state/system))

;; Test link token creation
(require '[finance-aggregator.plaid.client :as plaid])
(def link-token (plaid/create-link-token plaid-config "test-user"))

;; After frontend flow, test token exchange
(def result (plaid/exchange-public-token plaid-config "public-sandbox-xxx"))
;; => {:access_token "access-sandbox-xxx" :item_id "item-xxx"}

;; Test transaction fetch
(def txs (plaid/fetch-transactions plaid-config access-token "2025-01-01" "2025-11-30"))
```

### Security Considerations

#### Credential Storage

- **Existing encryption infrastructure** - Reuse AES-256-GCM encryption
- **Access tokens encrypted at rest** - Never store plaintext
- **User-scoped storage** - Credential isolation per user
- **Audit trail** - Track `:credential/last-used` timestamps

#### API Security

- **Environment variables** - Never commit secrets to git
- **Sandbox for development** - Use Sandbox credentials locally
- **Rate limiting awareness** - Respect Plaid rate limits
- **Token rotation** - Plan for token refresh (future phase)

#### Frontend Security

- **Link token lifetime** - Tokens expire quickly (30 minutes)
- **Public token lifetime** - Must exchange immediately
- **HTTPS only** - Enforce in production
- **No credential exposure** - Never expose access_token to frontend

### Performance Considerations

#### Rate Limits

Plaid has strict rate limits:
- **1 request/second per access_token** for `/transactions/get`
- **10 requests/second** for link token creation

**Mitigation:**
- Implement request throttling
- Cache transaction data
- Batch sync operations
- Monitor rate limit headers

#### Transaction History

Plaid provides up to **24 months** of transaction history on initial sync.

**Strategy:**
- Initial sync: Fetch full 24 months
- Incremental syncs: Last 30-60 days
- Store `:credential/last-synced` timestamp
- Optimize query ranges

#### Data Volume

**Considerations:**
- Large transaction sets (thousands per sync)
- Batch insert operations
- Database indexing (already optimized per ADR-003)
- Pagination support for large datasets

### Migration Path

#### For Existing SimpleFIN Users

**Option 1: Side-by-side**
1. User links Plaid account
2. Both credentials stored
3. System queries both sources
4. User chooses preferred source

**Option 2: Migration**
1. User links Plaid account
2. System syncs from Plaid
3. Archive SimpleFIN credential
4. Deactivate SimpleFIN sync

**Option 3: Clean slate**
1. Export SimpleFIN data
2. Remove SimpleFIN credential
3. Link Plaid account
4. Import transactions from Plaid

#### Implementation Strategy

Start with **Option 1** (side-by-side):
- Safest approach
- No data loss
- User control
- Easy rollback

### Open Questions

1. **Token Refresh Timing** - When to implement automatic token refresh?
   - **Answer:** Monitor for token expiration errors in Phase 6

2. **Webhook Strategy** - When to add real-time webhook support?
   - **Answer:** Defer to post-MVP, manual sync sufficient initially

3. **Multi-Institution Support** - How to handle users with multiple banks?
   - **Answer:** User can link multiple Plaid items, each gets own credential

4. **Transaction Deduplication** - How to handle duplicate transactions across sources?
   - **Answer:** Use `:transaction/external-id` uniqueness constraint

5. **Historical Data** - Keep SimpleFIN transactions or re-import from Plaid?
   - **Answer:** Keep historical data, new transactions from Plaid

6. **Error Reporting** - How to surface Plaid errors to users?
   - **Answer:** Store in DB, display in UI (Phase 6)

## Consequences

### Positive

1. **Better Data Quality** - Plaid provides more reliable, consistent data
2. **Wider Institution Support** - 12,000+ institutions vs SimpleFIN's limited set
3. **Industry Standard** - Well-documented, widely-used service
4. **Free Sandbox** - Comprehensive testing environment
5. **Future Features** - Webhooks, investments, liabilities support
6. **Schema Reuse** - No database changes required
7. **Coexistence** - Can run both integrations during transition
8. **Security** - OAuth-based flow more secure than credentials
9. **Scalability** - Production-ready infrastructure

### Negative

1. **UI Requirement** - Must implement Plaid Link frontend component
   - **Mitigation:** Minimal component, well-documented SDK
   - **Acceptable:** Essential for OAuth flow, one-time implementation

2. **More Complex Flow** - Multi-step OAuth vs direct API
   - **Mitigation:** Well-tested pattern, good documentation
   - **Acceptable:** Industry standard, better security

3. **Rate Limits** - Stricter than SimpleFIN
   - **Mitigation:** Implement throttling, caching
   - **Acceptable:** Forces good practices

4. **Cost** - Plaid has usage-based pricing (SimpleFIN is cheap)
   - **Mitigation:** Free Sandbox, Development tier reasonable
   - **Acceptable:** Quality data worth the cost

5. **Vendor Lock-in** - Harder to switch away from Plaid
   - **Mitigation:** Clean abstraction layer (integration layer)
   - **Acceptable:** Industry leader, low switching risk

### Migration Risks

1. **Data Gaps** - Plaid may not have all SimpleFIN historical data
   - **Mitigation:** Keep SimpleFIN data, new transactions from Plaid
   - **Acceptable:** Historical data preserved

2. **Token Expiration** - Access tokens can expire
   - **Mitigation:** Monitor, implement refresh
   - **Acceptable:** Standard OAuth practice

3. **Institution Coverage** - Some SimpleFIN banks may not be in Plaid
   - **Mitigation:** Check coverage before full migration
   - **Acceptable:** Plaid has better overall coverage

## Implementation Checklist

- [ ] Phase 1: Foundation
  - [ ] Add Plaid dependency to deps.edn
  - [ ] Get Plaid Sandbox credentials
  - [ ] Create Plaid config component
  - [ ] Create plaid/client.clj with core functions
  - [ ] Write unit tests for client

- [ ] Phase 2: Data Transformation
  - [ ] Create plaid/data.clj with transformations
  - [ ] Write comprehensive unit tests
  - [ ] Test with Plaid sample data

- [ ] Phase 3: Service Orchestration
  - [ ] Create plaid/service.clj
  - [ ] Integrate credential encryption
  - [ ] Write integration tests with Sandbox

- [ ] Phase 4: API Endpoints
  - [ ] Add /api/plaid/create-link-token
  - [ ] Add /api/plaid/exchange-token
  - [ ] Add /api/plaid/sync
  - [ ] Add request validation
  - [ ] Test in REPL

- [ ] Phase 5: Frontend Integration
  - [ ] Create plaid-link.tsx component
  - [ ] Implement Plaid Link SDK
  - [ ] Handle success callback
  - [ ] Test full OAuth flow

- [ ] Phase 6: Production Hardening
  - [ ] Add error handling
  - [ ] Add rate limiting
  - [ ] Add pagination support
  - [ ] Switch to Development environment
  - [ ] Deploy to Production

## References

- [Plaid API Documentation](https://plaid.com/docs/)
- [Plaid Link Documentation](https://plaid.com/docs/link/)
- [Plaid Java SDK](https://github.com/plaid/plaid-java)
- [ADR-003: Clojure Backend Architecture](./adr-003-clojure-backend-architecture.md)
- [Plaid Quickstart Guide](https://plaid.com/docs/quickstart/)
- [Plaid Sandbox Guide](https://plaid.com/docs/sandbox/)

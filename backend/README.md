# Finance Aggregator Backend

Clojure backend for the Finance Aggregator application, providing REST API for transaction management, Plaid integration, and data persistence.

## Architecture

Built using modern Clojure patterns:
- **Integrant**: Component lifecycle management
- **Datalevin**: Embedded Datalog database
- **HTTP-Kit**: High-performance HTTP server
- **Reitit**: Data-driven routing
- **age encryption**: Secure secrets management (no environment variables!)

See [ADR-003: Clojure Backend Architecture](../doc/adr/adr-003-clojure-backend-architecture.md) for full architecture details.

## Quick Start

### Prerequisites

```bash
# Install Jabba (Java version manager)
curl -sL https://github.com/shyiko/jabba/raw/master/install.sh | bash
jabba install zulu@25.0.3

# Install Babashka (for secrets management)
brew install borkdude/brew/babashka

# Install age encryption
brew install age
```

### First-Time Setup

```bash
cd backend

# Set Java version (run once per terminal session)
jabba use zulu@25.0.3

# Generate encryption key and create secrets
bb secrets keygen
bb secrets new
# Add your Plaid credentials in the editor that opens

# Run tests
clojure -M:test -m kaocha.runner

# Start REPL
clojure -M:repl -m nrepl.cmdline
```

### Running the Server

```bash
# Using the main entry point
clojure -M:dev -m finance-aggregator.main

# Or, from the project root, run the whole app (builds + watches the frontend
# assets and starts this server):
cd ..
bb dev
```

## Project Structure

```
backend/
├── src/finance_aggregator/
│   ├── main.clj                    # Application entry point
│   ├── system.clj                  # Integrant component definitions
│   ├── sys.clj                     # System lifecycle utilities
│   ├── auth.clj                    # Current-user resolution (single-user)
│   ├── categories.clj              # Category domain logic
│   ├── splits.clj                  # Split (sub-transaction) domain logic
│   ├── transfers.clj               # Transfer matching domain logic
│   ├── provider.clj                # Provider multimethod seam (dispatch)
│   ├── utils.clj                   # Utility functions
│   ├── db.clj                      # Legacy database operations
│   ├── db/
│   │   ├── core.clj                # Database connection management
│   │   ├── accounts.clj            # Account operations
│   │   ├── categories.clj          # Category CRUD operations
│   │   ├── transactions.clj        # Transaction operations
│   │   ├── transfers.clj           # Transfer link operations
│   │   ├── stats.clj               # Aggregate stats queries
│   │   └── credentials.clj         # Encrypted credential storage
│   ├── http/
│   │   ├── server.clj              # HTTP server component (lifecycle)
│   │   ├── router.clj              # Reitit router with middleware
│   │   ├── middleware.clj          # CORS, JSON, request processing
│   │   ├── errors.clj              # Exception handling middleware
│   │   ├── responses.clj           # Standard response formats
│   │   ├── handlers/               # JSON API request handlers by feature
│   │   │   ├── plaid.clj           # Plaid integration handlers
│   │   │   ├── providers.clj       # Generic provider sync handlers
│   │   │   ├── categories.clj      # Category CRUD handlers
│   │   │   ├── transactions.clj    # Transaction handlers
│   │   │   ├── transfers.clj       # Transfer matching handlers
│   │   │   ├── csv.clj             # CSV import handlers
│   │   │   ├── stats.clj           # Stats handlers
│   │   │   └── entities.clj        # Entity listing & query handlers
│   │   └── routes/                 # JSON API route definitions by feature
│   │       ├── api.clj             # API routes aggregator (/api)
│   │       ├── plaid.clj           # Plaid routes
│   │       ├── providers.clj       # Provider sync routes
│   │       ├── categories.clj      # Category routes
│   │       ├── transactions.clj    # Transaction routes
│   │       ├── transfers.clj       # Transfer routes
│   │       ├── csv.clj             # CSV import routes
│   │       ├── entities.clj        # Entity routes
│   │       ├── stats.clj           # Stats routes
│   │       └── static.clj          # Static file routes
│   ├── web/                        # Server-rendered hypermedia (hiccup2 + Datastar)
│   │   ├── routes.clj              # HTML/SSR route table
│   │   ├── pages/                  # Full-page views (transactions, setup)
│   │   ├── shell.clj               # HTML document shell
│   │   ├── layout.clj              # Page layout / chrome
│   │   ├── render.clj              # Datastar SSE / fragment rendering
│   │   ├── view.clj                # View helpers
│   │   ├── view_state.clj          # URL-encoded view state
│   │   ├── commands.clj            # Command (undo/redo) handling
│   │   ├── accounts.clj            # Account view fragments
│   │   ├── month.clj               # Month navigation helpers
│   │   └── format.clj              # Display formatting
│   ├── ws/
│   │   ├── handler.clj             # WebSocket endpoint (/ws)
│   │   └── state.clj               # WebSocket connection state
│   ├── provider/
│   │   └── sync.clj                # Shared provider sync orchestration
│   ├── plaid/
│   │   ├── client.clj              # Plaid API client (pure functions)
│   │   ├── data.clj                # Plaid -> canonical data transforms
│   │   ├── provider.clj            # :plaid provider seam implementation
│   │   ├── service.clj             # Sync orchestration & persistence
│   │   └── types.clj               # Plaid type/enum helpers
│   ├── simplefin/
│   │   ├── client.clj              # SimpleFIN API client (legacy)
│   │   └── data.clj                # SimpleFIN data transformations
│   ├── lunchflow/
│   │   ├── client.clj              # Lunchflow API client
│   │   ├── data.clj                # Lunchflow data transformations
│   │   └── provider.clj            # :lunchflow provider seam
│   ├── csv/
│   │   ├── service.clj             # CSV preview/import orchestration
│   │   └── data.clj                # CSV parsing & mapping
│   ├── manual/
│   │   ├── service.clj             # Manual entry orchestration
│   │   └── data.clj                # Manual entry data transforms
│   ├── lib/
│   │   ├── secrets.clj             # Secrets management library
│   │   ├── encryption.clj          # AES-256-GCM encryption
│   │   └── log.clj                 # Logging helpers
│   └── data/
│       ├── schema.clj              # Datalevin schema with user scoping
│       └── cleaning.clj            # Data normalization
├── env/dev/src/
│   ├── user.clj                    # REPL entry point
│   └── dev.clj                     # Dev tools and helpers
├── resources/
│   ├── system/
│   │   └── base-system.edn         # System configuration
│   ├── secrets.edn.age             # Encrypted secrets (committed)
│   └── secrets.edn.example         # Secrets template
├── test/                           # Test suite
├── SECRETS.md                      # Secrets management guide
├── PLAID_TESTING.md                # Plaid backend smoke-testing guide
└── PLAID_SYNC_TESTING.md           # Plaid sync manual-testing guide
```

## Development Workflow

### REPL-Driven Development

```bash
clojure -M:repl -m nrepl.cmdline
```

Then in your REPL:

```clojure
;; Load dev environment
(dev)

;; Start the system
(go)

;; Check status
(status)

;; Work with database
(all-entities :user/id)
(query '[:find ?name :where [_ :institution/name ?name]])

;; After code changes
(reset)

;; Stop the system
(halt)
```

See [REPL Quick Reference](../doc/implementation/adr-003-backend/repl-quick-reference.md) for more commands.

### Running Tests

```bash
# All tests
clojure -M:test -m kaocha.runner

# Watch mode
clojure -M:test -m kaocha.runner --watch

# Specific namespace
clojure -M:test -m kaocha.runner --focus finance-aggregator.lib.secrets-test
```

### Secrets Management

This project uses age-encrypted secrets instead of environment variables:

```bash
# Create/edit secrets
bb secrets edit

# View your public key (for team collaboration)
bb secrets show-key

# Generate new key
bb secrets keygen
```

See [SECRETS.md](./SECRETS.md) for comprehensive documentation.

## Key Features

### Plaid Integration

Connect to 12,000+ financial institutions via Plaid API:
- ✅ **API client** — pure API functions (`plaid/client.clj`)
- ✅ **Encryption & credentials** — AES-256-GCM token storage (`lib/encryption.clj`, `db/credentials.clj`)
- ✅ **Data transformation** — Plaid responses normalized to the canonical schema (`plaid/data.clj`, `plaid/provider.clj`)
- ✅ **Sync orchestration & persistence** — account and cursor-based transaction sync into Datalevin (`plaid/service.clj`), exposed via `http/routes/plaid.clj`
- ✅ **Multi-item support** — multiple linked Plaid Items synced per user, with per-item sync status and reset

Plaid links and synced accounts are surfaced through the server-rendered
`/setup` page (hiccup2 + Datastar) rather than a separate single-page app.

See:
- [ADR-004: Plaid Integration](../doc/adr/adr-004-plaid-integration.md) - Full integration plan
- [PLAID_TESTING.md](./PLAID_TESTING.md) - REPL/curl backend smoke-testing guide
- [PLAID_SYNC_TESTING.md](./PLAID_SYNC_TESTING.md) - Manual sync-testing guide

### Secure Secrets Management

No environment variables required! All secrets are encrypted with age:
- Convention-based defaults (`~/.config/finance-aggregator/key.txt`)
- Integrant-managed secrets component
- Safe to commit encrypted files to git
- Team collaboration via multi-recipient encryption

### Embedded Database

Datalevin provides a Datalog database with:
- ACID transactions
- Time-travel queries
- Schema validation
- Automatic indexing

## API Endpoints

The router (`http/router.clj`) mounts three families of routes plus a WebSocket
endpoint:

- **JSON API** under `/api` (`http/routes/`)
- **Server-rendered hypermedia pages** (`web/routes.clj`) for the Datastar UI
- **Static assets** (`http/routes/static.clj`)
- **WebSocket** at `/ws` (`ws/handler.clj`) for live push

**JSON API** (`/api`, via Integrant/reitit):
```
GET    /api/stats                              Database statistics
GET    /api/categories                         List categories
POST   /api/categories                         Create category
POST   /api/categories/bulk                    Bulk-create categories
POST   /api/categories/batch-sort              Batch update sort orders
PUT    /api/categories/:id                     Update category
DELETE /api/categories/:id                     Delete category
PUT    /api/transactions/:id/category          Update transaction category
PUT    /api/transactions/:id/description       Set transaction description
PUT    /api/transactions/:id/splits            Set transaction splits
PUT    /api/transactions/:id/splits/:splitId/memo      Set split memo
PUT    /api/transactions/:id/reviewed          Set transaction reviewed flag
PUT    /api/transactions/:id/splits/:splitId/reviewed  Set split reviewed flag
GET    /api/transfers/suggestions              Suggested transfer matches
GET    /api/transfers/candidates               Candidate transfer legs
POST   /api/transfers/reject                   Reject a transfer suggestion
DELETE /api/transfers/:id                      Unmatch a transfer
GET    /api/institutions                       List institutions
GET    /api/accounts                           List accounts
GET    /api/accounts/:id                        Get account
PUT    /api/accounts/:id/settings              Update account settings
GET    /api/transactions                       List transactions
POST   /api/query                              Execute custom Datalog query
POST   /api/providers/:provider/sync           Sync a provider (generic seam)
GET    /api/providers/:provider/available-accounts   List a provider's accounts
GET    /api/csv/mapping/:account-id            Get saved CSV column mapping
POST   /api/csv/preview/:account-id            Preview a CSV import
POST   /api/csv/import/:account-id             Import a CSV

# Plaid integration (http/routes/plaid.clj)
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

See `src/finance_aggregator/http/routes/`, `src/finance_aggregator/http/handlers/`,
and `src/finance_aggregator/web/routes.clj` for full implementation.

## Configuration

Configuration uses Integrant, loaded from `resources/system/base-system.edn`:

```clojure
{:finance-aggregator.system/db-path "./data/finance.db"
 :finance-aggregator.system/http-port 8080
 :finance-aggregator.system/secrets-key-file "~/.config/finance-aggregator/key.txt"
 :finance-aggregator.system/secrets-file "resources/secrets.edn.age"}
```

## Dependencies

Core libraries:
- `integrant` - Component lifecycle
- `datalevin` - Embedded database
- `http-kit` - HTTP server
- `reitit` - Routing
- `malli` - Schema validation
- `tablecloth` - Data processing
- `plaid-java` (v35.0.0) - Plaid API client

Development:
- `kaocha` - Test runner
- `integrant/repl` - REPL workflow
- `scope-capture` - Debugging

## Documentation

- [Clojure Backend Architecture](../doc/adr/adr-003-clojure-backend-architecture.md) - Full architecture ADR
- [REPL Quick Reference](../doc/implementation/adr-003-backend/repl-quick-reference.md) - Common REPL commands
- [Phase 1 Implementation](../doc/implementation/adr-003-backend/phase1-implementation-complete-2025-11-25.md) - Infrastructure layer details
- [Secrets Management](./SECRETS.md) - Complete secrets guide
- [Plaid Integration](../doc/adr/adr-004-plaid-integration.md) - Plaid implementation details

## License

Private project

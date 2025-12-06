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
jabba install zulu@21.0.6

# Install Babashka (for secrets management)
brew install borkdude/brew/babashka

# Install age encryption
brew install age
```

### First-Time Setup

```bash
cd backend

# Set Java version (run once per terminal session)
jabba use zulu@21.0.6

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

# Or from the project root with Overmind
cd ..
overmind start
```

## Project Structure

```
backend/
├── src/finance_aggregator/
│   ├── main.clj                    # Application entry point
│   ├── system.clj                  # Integrant component definitions
│   ├── sys.clj                     # System lifecycle utilities
│   ├── db/
│   │   ├── core.clj                # Database connection management
│   │   ├── categories.clj          # Category CRUD operations
│   │   ├── transactions.clj        # Transaction operations
│   │   └── credentials.clj         # Encrypted credential storage (Phase 2)
│   ├── db.clj                      # Legacy database operations
│   ├── server.clj                  # Full API handler (routes & logic + Plaid endpoints)
│   ├── http/
│   │   └── server.clj              # HTTP server component (lifecycle)
│   ├── plaid/
│   │   └── client.clj              # Plaid API client (Phase 1 complete)
│   ├── simplefin/
│   │   ├── client.clj              # SimpleFIN API client (legacy)
│   │   └── data.clj                # SimpleFIN data transformations
│   ├── lib/
│   │   ├── secrets.clj             # Secrets management library
│   │   └── encryption.clj          # AES-256-GCM encryption (Phase 2)
│   ├── data/
│   │   ├── schema.clj              # Datalevin schema with user scoping
│   │   └── cleaning.clj            # Data normalization
│   └── utils.clj                   # Utility functions
├── env/dev/src/
│   ├── user.clj                    # REPL entry point
│   └── dev.clj                     # Dev tools and helpers
├── resources/
│   ├── system/
│   │   ├── base-system.edn         # Base configuration
│   │   └── dev.edn                 # Dev overrides
│   ├── secrets.edn.age             # Encrypted secrets (committed)
│   └── secrets.edn.example         # Secrets template
├── test/                           # Test suite
├── SECRETS.md                      # Secrets management guide
└── PLAID_TESTING.md                # Plaid integration testing guide
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

### Plaid Integration (Phases 1-2 Complete)

Connect to 12,000+ financial institutions via Plaid API:
- ✅ **Phase 1**: Core API client functions (`plaid/client.clj`)
- ✅ **Phase 2**: Encryption & credentials (`lib/encryption.clj`, `db/credentials.clj`)
- ✅ **Phase 2**: API endpoints (4 endpoints in `server.clj`)
- ✅ **Phase 2**: Comprehensive tests (20 tests passing)
- 🚧 **Phase 3**: Frontend Plaid Link component (in progress)
- 🚧 **Phase 4**: Data transformation layer
- 🚧 **Phase 5**: Service orchestration & persistence

**Current Status**: Phase 2 complete with working API endpoints. Ready for Phase 3 (frontend UI).

See:
- [ADR-004: Plaid Integration](../doc/adr/adr-004-plaid-integration.md) - Full integration plan
- [PLAID_TESTING.md](./PLAID_TESTING.md) - Testing guide with Sandbox setup

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

**Currently Available** (via `server.clj`):
```
GET    /health                                 Health check
GET    /api/stats                              Database statistics
GET    /api/transactions                       List all transactions
GET    /api/accounts                           List all accounts
GET    /api/institutions                       List all institutions
GET    /api/categories                         List all categories
POST   /api/categories                         Create category
PUT    /api/categories/:id                     Update category
DELETE /api/categories/:id                     Delete category
POST   /api/categories/batch-sort              Batch update sort orders
PUT    /api/transactions/:id/category          Update transaction category
POST   /api/query                              Execute custom Datalog query

# Plaid Integration (Phase 2 - Complete)
POST   /api/plaid/create-link-token            Create Plaid Link token
POST   /api/plaid/exchange-token               Exchange public token & store credential
GET    /api/plaid/accounts                     Fetch accounts (uses stored credential)
POST   /api/plaid/transactions                 Fetch transactions (uses stored credential)
```

**Planned** (Plaid integration - Phase 5):
```
POST   /api/plaid/sync-accounts                Sync accounts to database
POST   /api/plaid/sync-transactions            Sync transactions to database
```

See `src/finance_aggregator/server.clj` for full implementation.

## Configuration

Configuration uses Integrant with environment-specific overrides:

**Base** (`resources/system/base-system.edn`):
```clojure
{:finance-aggregator.system/db-path "./data/finance.db"
 :finance-aggregator.system/http-port 8080
 :finance-aggregator.system/secrets-key-file "~/.config/finance-aggregator/key.txt"
 :finance-aggregator.system/secrets-file "resources/secrets.edn.age"}
```

**Dev overrides** (`resources/system/dev.edn`):
```clojure
{:finance-aggregator.system/db-path "./data/dev.db"}
```

## Dependencies

Core libraries:
- `integrant` - Component lifecycle
- `datalevin` - Embedded database
- `http-kit` - HTTP server
- `reitit` - Routing
- `malli` - Schema validation
- `tablecloth` - Data processing
- `plaid-java` - Plaid API client

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

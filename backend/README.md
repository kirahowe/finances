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
│   │   ├── core.clj                # Database operations
│   │   ├── categories.clj          # Category queries
│   │   └── transactions.clj        # Transaction queries
│   ├── http/
│   │   └── server.clj              # HTTP server component
│   ├── plaid/
│   │   └── client.clj              # Plaid API integration
│   ├── lib/
│   │   └── secrets.clj             # Secrets management library
│   └── data/
│       ├── schema.clj              # Datalevin schema
│       └── cleaning.clj            # Data normalization
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
└── SECRETS.md                      # Secrets management guide
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
- Account connection and authentication
- Transaction sync
- Balance tracking
- Multi-user support

See [ADR-004: Plaid Integration](../doc/adr/adr-004-plaid-integration.md)

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

```
GET    /health              Health check
GET    /api/transactions    List transactions
POST   /api/plaid/link      Create Plaid Link token
POST   /api/plaid/exchange  Exchange public token
```

See `src/finance_aggregator/http/server.clj` for full API documentation.

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

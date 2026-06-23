# Finance Aggregator

A personal finance management application for aggregating transactions from multiple financial institutions, with automated categorization and spending analysis.

## Features

- 🏦 **Multi-Institution Support** - Connect accounts via Plaid (12,000+ institutions), plus CSV import, manual entry, and lunchflow
- 📊 **Transaction Management** - View, filter, sort, split, and categorize transactions
- 🔁 **Transfer Tracking** - Match transfers between accounts and hide them from spending
- 🏷️ **Smart Categorization** - Manual and automated transaction categorization
- 📈 **Spending Analysis** - Track spending by category and time period
- 🔐 **Secure** - Encrypted secrets, user-scoped data isolation

## Quick Start

See [DEVELOPMENT.md](./DEVELOPMENT.md) for complete setup instructions.

### Prerequisites

```bash
# Install required tools
brew install borkdude/brew/babashka   # Task runner + secrets management
brew install age                       # Encryption
brew install node                      # Frontend deps (npm: islands + e2e)
brew install borkdude/brew/clj-kondo   # Clojure linting (bb lint)

# Install Jabba (Java version manager)
curl -sL https://github.com/shyiko/jabba/raw/master/install.sh | bash
jabba install zulu@25.0.3
jabba use zulu@25.0.3
```

### First-Time Setup

```bash
# 1. Install frontend dependencies (islands/ + e2e/)
bb install

# 2. Configure secrets (interactive)
bb secrets keygen
bb secrets new
# Add your Plaid credentials in the editor

# 3. Start the app (server-authoritative: single backend, no separate web server)
bb dev
```

Access the application at http://localhost:8080.

## Architecture

### Backend (Clojure)

Modern functional architecture using:
- **Integrant** - Component lifecycle management
- **Datalevin** - Embedded Datalog database
- **Plaid API** - Financial data aggregation
- **HTTP-Kit** - High-performance HTTP server

See [Backend README](./backend/README.md) for details.

### Frontend (server-authoritative)

The backend renders the UI directly — there is no separate frontend app:
- **hiccup2 SSR** - HTML rendered on the JVM
- **Datastar** - reactivity + SSE-patched fragments over the wire
- **TS islands** - small esbuild-bundled widgets (Zag) for pointer/latency-heavy
  interactions, served from the backend
- **Playwright** - real-Chromium browser checks in `e2e/`

See [islands/README.md](./islands/README.md) and [e2e/README.md](./e2e/README.md).

## Project Structure

```
finance-aggregator/
├── backend/                  # Clojure backend (SSR + Datastar, serves frontend assets)
│   ├── src/                  # Source code
│   ├── test/                 # Tests
│   ├── env/                  # Per-environment config + dev/e2e source
│   ├── resources/            # Config, secrets, public assets (CSS + built JS)
│   └── README.md             # Backend documentation
├── islands/                  # Vanilla TS islands (Zag widgets), esbuild → backend assets
├── e2e/                      # Playwright browser checks (TypeScript)
├── doc/                      # Architecture documentation
│   ├── adr/                  # Architecture Decision Records
│   └── plans/                # Design notes and feature backlog
├── scripts/                  # Development scripts (bb secrets)
├── bb.edn                    # Babashka dev tasks (dev/build/test/lint/e2e/secrets)
├── DEVELOPMENT.md            # Development setup guide
└── README.md                 # This file
```

## Documentation

### Getting Started
- [Development Setup](./DEVELOPMENT.md) - Complete development environment setup
- [Backend README](./backend/README.md) - Backend architecture and API
- [Islands](./islands/README.md) / [Browser checks](./e2e/README.md) - Frontend assets and e2e

### Architecture Decision Records
- [ADR-001: Category Management Prototype](./doc/adr/adr-001-category-management-scittle-prototype.md) _(superseded)_
- [ADR-002: Modern React Frontend](./doc/adr/adr-002-modern-react-frontend-architecture.md) _(superseded — React removed 2026-06)_
- [ADR-003: Clojure Backend Architecture](./doc/adr/adr-003-clojure-backend-architecture.md)
- [ADR-004: Plaid Integration](./doc/adr/adr-004-plaid-integration.md)

### Implementation Guides
- [Backend REPL Guide](./doc/implementation/adr-003-backend/repl-quick-reference.md)
- [Secrets Management](./backend/SECRETS.md)
- [Plaid Testing Guide](./backend/PLAID_TESTING.md)
- [Datastar migration handoff](./doc/plans/datastar-handoff.md)

## Development Workflow

### Running the Application

```bash
# Start the app (builds + watches islands, runs the backend dev server)
bb dev

# Or drive the backend from a REPL:
bb build                      # build frontend assets first
cd backend
jabba use zulu@25.0.3
clojure -M:repl -m nrepl.cmdline
# Then: (go)
```

### Running Tests

```bash
bb test            # backend (kaocha) + islands (vitest)
bb lint            # clj-kondo + tsc typechecks
bb e2e             # Playwright browser checks against a seeded server
```

Run `bb tasks` to list all available dev tasks.

### REPL Development (Backend)

```bash
cd backend
jabba use zulu@25.0.3
clojure -M:repl -m nrepl.cmdline
```

Then in your REPL:
```clojure
(dev)      ; Load dev environment
(go)       ; Start the system
(reset)    ; Reload and restart after code changes
(halt)     ; Stop the system
```

See [REPL Quick Reference](./doc/implementation/adr-003-backend/repl-quick-reference.md) for more commands.

## Current Status

### ✅ Completed
- Server-authoritative UI (hiccup2 SSR + Datastar + TS islands) — replaced the
  former React/Remix frontend (removed 2026-06)
- Backend infrastructure with Integrant
- Category management system, including a parent/child hierarchy
- Transaction table — filtering, sorting, pagination, transaction splits, and resizable/hideable columns (view state persisted in the URL)
- Transfer tracking — auto-matching, manual match, and a hide-transfers toggle
- Secrets management with age encryption
- REPL-driven development workflow
- **Plaid integration** - client, encrypted credentials, data transformation, and
  sync orchestration & persistence (multi-item)
- Additional providers via a provider seam (CSV import, manual entry, lunchflow)

### 📋 Planned
- Reconciliation + account sync hardening (production push)
- User authentication and multi-user support
- Automated transaction categorization
- Spending analytics dashboard
- Budget tracking

## Technology Stack

**Backend:**
- Clojure 1.12
- Integrant (system management)
- Datalevin (database)
- HTTP-Kit (web server)
- Reitit (routing)
- Plaid Java SDK (financial data)

**Frontend (server-authoritative):**
- hiccup2 (JVM-side HTML rendering)
- Datastar (reactivity + SSE)
- TypeScript + esbuild + Zag (islands)
- Playwright (e2e browser checks)

**Infrastructure:**
- Babashka (dev tasks + scripting)
- age (encryption)
- Jabba (Java version management)
- npm (frontend deps: islands + e2e)

## Contributing

This is a personal project, but the architecture and patterns may be useful for reference.

## License

Private project

# Finance Aggregator Backend

Clojure backend for the Finance Aggregator application: a server-rendered hypermedia app (hiccup2 + Datastar) for transaction management, multi-provider account sync (Plaid, Lunchflow, CSV, manual), and data persistence. (The legacy `/api` JSON layer and the React frontend were removed; the app is now server-rendered.)

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
│   │   ├── accounts.clj            # Account queries (incl. inverted-account-ids)
│   │   ├── categories.clj          # Category CRUD operations
│   │   ├── transactions.clj        # Transaction operations
│   │   ├── transfers.clj           # Transfer link operations
│   │   ├── stats.clj               # Aggregate stats queries
│   │   ├── connections.clj         # Provider-agnostic sync-state (:connection/*)
│   │   ├── snapshots.clj           # Reported-balance history + reconciliation reads (:snapshot/*)
│   │   ├── reconciliations.clj     # Monthly close events (:reconciliation/*)
│   │   └── credentials.clj         # Encrypted token storage (:credential/*)
│   ├── http/
│   │   ├── server.clj              # HTTP server component (lifecycle)
│   │   ├── router.clj              # Reitit router: SSR pages + static
│   │   ├── middleware.clj          # Params / request processing
│   │   ├── errors.clj              # Exception handling middleware
│   │   └── routes/
│   │       └── static.clj          # Static asset routes
│   ├── web/                        # Server-rendered hypermedia (hiccup2 + Datastar)
│   │   ├── routes.clj              # HTML/SSR route table
│   │   ├── pages/                  # Handler + dumb-view pairs (transactions/-view, setup/-view)
│   │   ├── shell.clj               # HTML document shell
│   │   ├── layout.clj              # Page layout / chrome
│   │   ├── render.clj              # Datastar SSE / fragment rendering
│   │   ├── view.clj                # Pure view engine: filter/sort/paginate, rollup, month-close
│   │   ├── view_state.clj          # URL-encoded view state
│   │   ├── commands.clj            # Command (undo/redo) handling
│   │   ├── accounts.clj            # Account view fragments
│   │   ├── month.clj               # Month navigation helpers
│   │   └── format.clj              # Display formatting
│   ├── provider/
│   │   ├── sync.clj                # Shared sync orchestration + persist-transactions! ingest point
│   │   ├── normalize.clj           # Canonical amount normalization (invert-amount, once at import)
│   │   ├── contract.clj            # Overlay-safety guard (sync never writes user edits)
│   │   └── retry.clj               # Capped-exponential backoff + transient/terminal classification
│   ├── plaid/
│   │   ├── client.clj              # Plaid API client (execute! chokepoint carries error_code)
│   │   ├── data.clj                # Plaid -> canonical data transforms
│   │   ├── provider.clj            # :plaid provider seam implementation
│   │   ├── errors.clj              # Plaid error vocabulary + classify -> retry/reconnect/fail
│   │   └── types.clj               # Plaid type/enum helpers
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
│   ├── resync.clj                  # Trigger-decoupled resync engine (drives due connections)
│   ├── resync/main.clj             # Headless resync entry (`clojure -M:resync` / `bb resync` / cron)
│   └── data/
│       ├── schema.clj              # Datalevin schema with user scoping
│       ├── ledger.clj              # Pure period-delta math for the monthly close (reconciliation)
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
- ✅ **Cursor-based sync** — `/transactions/sync` (added/modified/removed) through the generic provider seam (`provider/sync.clj`, `plaid/provider.clj`), with account balances + reported-balance snapshots persisted
- ✅ **Resumable resync engine** — trigger-decoupled `resync.clj` drives every due connection; per-page cursor persistence, capped-exponential backoff + error classification (`provider/retry.clj`, `plaid/errors.clj`); `bb resync` / `clojure -M:resync` / `Sync now`
- ✅ **Embedded Plaid Link** — the `/setup` page links banks via a Plaid Link.js island; re-auth via Link update mode (backend)
- ✅ **Multi-item support** — multiple linked Plaid Items per user

The provider seam (`provider.clj` + `provider/*`) is provider-agnostic: Plaid,
Lunchflow, CSV, and manual entry all produce the same canonical data, normalized
and persisted through one ingest point (`provider.sync/persist-transactions!`).
Sync-state (cursor, status, freshness, error/backoff) lives on a generic
`:connection/*` entity (`db/connections.clj`).

> **In flight (see [`sync-reconciliation-handoff.md`](../doc/plans/sync-reconciliation-handoff.md)):**
> the remaining sync chunk is Phase 3 — dedup/merge across providers + surfacing drift when a bank
> `modified` overwrites a user edit. The reconciliation surface shipped separately as the monthly close
> (below).

See:
- [ADR-004: Plaid Integration](../doc/adr/adr-004-plaid-integration.md) - Full integration plan
- [PLAID_TESTING.md](./PLAID_TESTING.md) - REPL/curl backend smoke-testing guide
- [PLAID_SYNC_TESTING.md](./PLAID_SYNC_TESTING.md) - Manual sync-testing guide

### Monthly close (reconciliation)

Verify a month's transactions match the bank, lock it in, and roll the totals up:
- ✅ **Period-delta check** — per account, the bank-reported balance change over the month
  (`db/snapshots.clj`) vs the sum of tracked transactions (`data/ledger.clj`); no opening-balance
  anchor needed. Shows "matches / off by $X / no statement".
- ✅ **Manual statement entry** — enter a bank statement balance when the sync has no month-boundary
  snapshot (`snapshots/record-manual-balance!`, a `:manual` snapshot).
- ✅ **Close / reopen** — a month-level `:reconciliation/*` event (`db/reconciliations.clj`) that freezes
  the month's totals; gated on everything reviewed + categorized + every account reconciled. Reopening
  retracts the event. A transaction later imported into a closed month surfaces as drift.

The panel lives on `/` beside the category rollup (`web/pages/transactions_view.clj` `close-panel`);
the model is pure (`web/view.clj` `month-close`). See
[`monthly-close-handoff.md`](../doc/plans/monthly-close-handoff.md).

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

## Routes

The router (`http/router.clj`) mounts the server-rendered hypermedia app plus
static assets. There is **no `/api` JSON layer** — it was removed when the app
moved to server-side rendering. Live updates (transaction edits, setup sync
progress) are Datastar SSE on the page routes, not a separate socket.

- **Server-rendered hypermedia pages** (`web/routes.clj`) — the Datastar UI: `/`
  (transactions workspace) and `/setup`, plus the fragment/SSE routes the workspace
  morphs (transaction edits, splits, transfer match/review, undo/redo; monthly close —
  `/transactions/reconcile/:account/statement`, `/transactions/close`,
  `/transactions/reopen`) and the setup sync actions (`/setup/sync`, `/setup/resync`)
  that live-patch the connections list. See the workspace handoff
  [`datastar-handoff.md`](../doc/plans/datastar-handoff.md) for the full route list.
- **Static assets** (`http/routes/static.clj`) — built islands + Datastar runtime.

See `src/finance_aggregator/web/routes.clj` for the page/fragment routes.

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

- [Architecture & Conventions](../doc/architecture-and-conventions.md) - the layered architecture (Data→Transformation→View→Handler) + code-style bars; **read before any refactor or new feature**
- [Clojure Backend Architecture](../doc/adr/adr-003-clojure-backend-architecture.md) - Full architecture ADR
- [REPL Quick Reference](../doc/implementation/adr-003-backend/repl-quick-reference.md) - Common REPL commands
- [Resilient sync, balances & reconciliation](../doc/plans/sync-reconciliation.md) - provider-sync design; [sync handoff](../doc/plans/sync-reconciliation-handoff.md) and [monthly-close handoff](../doc/plans/monthly-close-handoff.md) are the resume-here docs
- [Secrets Management](./SECRETS.md) - Complete secrets guide
- [Plaid Integration](../doc/adr/adr-004-plaid-integration.md) - Plaid implementation details

## License

Private project

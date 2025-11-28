# ADR-003 REPL Quick Reference

> **Document Type:** Living reference (updated as features are added)
> **Architecture:** [ADR-003: Clojure Backend Architecture](../../adr/adr-003-clojure-backend-architecture.md)
> **Last Updated:** 2025-11-25

Quick reference for common tasks when working with the Clojure backend.

---

## Setup

```bash
# Set Java version (once per terminal session)
jabba use zulu@21.0.6

# Start REPL (with dev environment)
cd backend
clojure -M:repl
```

## First-Time REPL Experience

When you start the REPL, you'll see a friendly welcome message with all available commands.

```clojure
;; Type this to load the dev environment:
(dev)

;; This will:
;; 1. Load all development tools
;; 2. Show the help message again
;; 3. Switch you to the 'dev' namespace
```

## REPL Commands

After calling `(dev)`, you'll have access to:

```clojure
;; Start the system
(go)     ; or (start)

;; Stop the system
(halt)

;; Reload code and restart
(reset)

;; Access components
(db)     ; Database component
(server) ; HTTP server component
(conn)   ; Database connection (convenience)
(system) ; Full system map

;; Quick status check
(status)

;; Database helpers
(query '[:find ?name :where [_ :institution/name ?name]])
(all-entities :user/id)
(pull-entity [:user/id "test-user"])

;; Development helpers
(show-schema)
(show-config)
(db-info)
(server-info)

;; Test helpers
(create-test-user!)
(create-test-institution!)
```

## Development Tools

The dev environment includes several powerful tools:

### Scope Capture (Debugging)

```clojure
;; Add to any expression to capture its value
(let [x 10
      y (sc.api/spy (* x 2))  ; Captures 20
      z (+ y 5)]
  z)

;; View recent captures
(sc.api/ep-info)

;; Learn more: https://github.com/vvvvalvalval/scope-capture
```

### Code Reloading

```clojure
;; Reload changed namespaces
(refresh)

;; Reload all namespaces
(refresh-all)

;; Full system reset
(reset)
```

### Introspection

```clojure
;; View function source
(source query)

;; View documentation
(doc query)

;; List namespace contents
(dir dev)
```

## Running Tests

```bash
# All tests (existing tests, should pass)
clojure -M:test -m kaocha.runner --skip finance-aggregator.system-test

# Specific test namespace
clojure -M:test -m kaocha.runner --focus finance-aggregator.db.core-test

# Multiple namespaces
clojure -M:test -m kaocha.runner --focus finance-aggregator.system-test --focus finance-aggregator.http.server-test
```

## Project Structure

```
backend/
├── env/
│   └── dev/
│       └── src/
│           ├── user.clj          # Initial REPL namespace (welcome message)
│           └── dev.clj           # Development tools and helpers
├── src/finance_aggregator/
│   ├── sys.clj                   # System lifecycle
│   ├── system.clj                # Integrant components
│   ├── db/
│   │   └── core.clj              # Database connection mgmt
│   ├── data/
│   │   └── schema.clj            # Datalevin schema (enhanced with user scoping)
│   ├── http/
│   │   └── server.clj            # HTTP server component
│   └── ...
├── test/finance_aggregator/
│   ├── sys_test.clj              # System lifecycle tests
│   ├── system_test.clj           # Integration tests
│   ├── db/
│   │   └── core_test.clj         # Database tests
│   ├── data/
│   │   └── schema_test.clj       # Schema tests
│   └── http/
│       └── server_test.clj       # HTTP server tests
└── resources/system/
    ├── base-system.edn           # Base configuration
    └── dev.edn                   # Dev overrides
```

### Dev Environment Structure

The `env/dev/src` directory contains development-only code:

- **user.clj**: Loaded automatically when REPL starts
  - Shows welcome message
  - Provides `(dev)` function to load dev tools
  - Provides `(help)` function to show commands

- **dev.clj**: Development namespace with tools
  - System management (`go`, `halt`, `reset`)
  - Component access (`db`, `server`, `conn`)
  - Database helpers (`query`, `all-entities`, `transact!`)
  - Development utilities (`status`, `show-schema`, `clear-db!`)
  - Test runners
  - Sample data creation

## Configuration

### Base System (`resources/system/base-system.edn`)
```clojure
{:finance-aggregator.system/db-path "./data/finance.db"
 :finance-aggregator.system/http-port 8080
 :finance-aggregator.db/connection {:db-path #ig/ref :finance-aggregator.system/db-path}
 :finance-aggregator.http/server {:port #ig/ref :finance-aggregator.system/http-port
                                   :db #ig/ref :finance-aggregator.db/connection}}
```

### Dev Overrides (`resources/system/dev.edn`)
```clojure
{:finance-aggregator.system/db-path "./data/dev.db"
 :finance-aggregator.system/http-port 8080}
```

## Schema Overview

### User Entities
```clojure
:user/id              ; Unique user identifier
:user/email           ; User email
:user/created-at      ; Creation timestamp
```

### User-Scoped Entities
```clojure
;; All major entities have user references
:account/user         ; Account ownership
:transaction/user     ; Transaction ownership (denormalized)
:category/user        ; Category ownership (nil = system category)
:credential/user      ; Credential ownership
```

### Credentials (Encrypted Storage)
```clojure
:credential/id                ; Unique ID
:credential/user              ; Owner
:credential/institution       ; e.g., :simplefin
:credential/encrypted-data    ; AES-256-GCM encrypted
:credential/created-at        ; Creation timestamp
:credential/last-used         ; Last access timestamp
```

## Common Queries

### User Isolation
```clojure
;; Find user's transactions
(d/q '[:find [(pull ?tx [*]) ...]
       :in $ ?user-id
       :where
       [?user :user/id ?user-id]
       [?tx :transaction/user ?user]]
     @conn
     "user-123")
```

### System + User Categories
```clojure
;; Find categories available to user (system + user's own)
(d/q '[:find [(pull ?cat [*]) ...]
       :in $ ?user-id
       :where
       [?user :user/id ?user-id]
       (or [?cat :category/user ?user]
           [(missing? $ ?cat :category/user)])]
     @conn
     "user-123")
```

## HTTP Endpoints

Currently implemented:
- `GET /health` → `{:status "ok", :service "finance-aggregator"}`

Test with:
```bash
curl http://localhost:8080/health
```

## Useful Commands

```bash
# Find all Clojure files
find . -name "*.clj" | grep -v target

# Count lines of code (src only)
find src -name "*.clj" -exec wc -l {} + | tail -1

# Run specific test and watch output
clojure -M:test -m kaocha.runner --focus finance-aggregator.db.core-test --watch

# Clean build artifacts
rm -rf .cpcache target
```

## Troubleshooting

### Native Library Crashes During Tests
**Symptom:** SIGSEGV errors when running all tests

**Solution:** Run test namespaces individually:
```bash
clojure -M:test -m kaocha.runner --focus namespace1 --focus namespace2
```

### REPL Won't Start
**Check:** Java version
```bash
jabba current  # Should show zulu@21.0.6
jabba use zulu@21.0.6
```

### Port Already in Use
**Solution:** Find and kill process:
```bash
lsof -ti:8080 | xargs kill -9
```

Or change port in `resources/system/dev.edn`

### Database Locked
**Symptom:** "Database is locked" errors

**Solution:** Make sure to stop the system before starting again:
```clojure
(halt)  ; Stop system
(go)    ; Start again
```

### Config Not Found
**Symptom:** "Config file not found" error

**Check:** Config files must be in `resources/` directory and on classpath
```bash
ls resources/system/
# Should show: base-system.edn  dev.edn
```

## Dependencies

Key libraries:
- **integrant** - Component lifecycle management
- **integrant/repl** - REPL workflow helpers (dev only)
- **datalevin** - Database
- **http-kit** - HTTP server
- **ring** - Web application library
- **malli** - Schema validation (to be used in Phase 2)
- **reitit** - Routing (to be used in Phase 2)

**Development tools** (`:dev` alias):
- **vvvvalvalval/scope-capture** - Powerful debugging with value capture
- **org.clojure/tools.namespace** - Code reloading and namespace management
- **nrepl** - REPL server for editor integration
- **cider-nrepl** - Enhanced REPL for Emacs/VS Code

## Next Development Tasks

See [Phase 1 Complete Documentation](./phase1-implementation-complete-2025-11-25.md) for detailed next steps.

Quick summary:
1. **db/queries.clj** - User-scoped read operations
2. **db/transactions.clj** - User-scoped write operations
3. **domain/** - Pure business logic functions
4. **credentials.clj** - Encryption/decryption
5. **api/routes.clj** - Reitit routing
6. **api/handlers.clj** - Request/response handling
7. **validation/schemas.clj** - Malli schemas

## Useful REPL Explorations

```clojure
;; Explore the schema
(require '[finance-aggregator.data.schema :as schema])
(keys schema/schema)

;; Check what's in the database
(d/q '[:find ?attr
       :where [_ ?attr]]
     @conn)

;; Create a test user
(d/transact! conn [{:user/id "test-user"
                   :user/email "test@example.com"
                   :user/created-at (java.util.Date.)}])

;; Find all users
(d/q '[:find [(pull ?u [*]) ...]
       :where [?u :user/id]]
     @conn)

;; Get system state
(require '[integrant.repl.state :as state])
state/system  ; Current running system

;; Check component keys
(keys state/system)
```

## Code Style Notes

- Use `defn` for public functions, `defn-` for private
- Docstrings for all public functions
- Prefer `let` bindings over nested function calls
- Use threading macros (`->`, `->>`) for data transformations
- Keep functions small and focused
- Write tests first (TDD)
- Separate pure functions from side effects

## Git Workflow

```bash
# Check status
git status

# Run tests before committing
clojure -M:test -m kaocha.runner --skip finance-aggregator.system-test

# Add files
git add backend/src/finance_aggregator/...

# Commit
git commit -m "Add database query layer with user scoping"
```

---

**Last Updated:** 2025-11-25
**Phase:** 1 (Foundation Complete)

# ADR-003 Dev Environment Setup

> **Document Type:** Point-in-time implementation snapshot
> **Date:** 2025-11-25
> **Status:** Complete
> **Architecture:** [ADR-003: Clojure Backend Architecture](../../adr/adr-003-clojure-backend-architecture.md)
> **Focus:** REPL development experience and tooling

---

## Overview

The REPL development experience has been significantly enhanced to match modern Clojure project standards, inspired by the excellent UX from Swirrl's datahost-prototypes project.

## What Changed

### 1. Reorganized Dev Environment Structure ✅

**Before:**
```
backend/
└── dev/
    └── user.clj    # All dev code in one file
```

**After:**
```
backend/
└── env/
    └── dev/
        └── src/
            ├── user.clj    # Welcome message & entry point
            └── dev.clj     # Development tools & helpers
```

### 2. Enhanced REPL Startup Experience ✅

**Before:**
- Silent REPL startup
- No guidance on available commands
- Manual requires for everything

**After:**
- **Friendly welcome banner** on REPL startup
- **Complete command reference** shown automatically
- **Guided workflow**: Type `(dev)` to load dev environment
- **Context switching**: Automatically switches to `dev` namespace

#### Welcome Message Example

The complete welcome message shows all available utilities organized into clear categories:

```
═════════════════════════════════════════════════════════════
  Welcome to Finance Aggregator Backend Development REPL
═════════════════════════════════════════════════════════════

Quick Start:
  (dev)                    ;; Load dev tools and switch to dev namespace

After running (dev), you'll have access to:

System Management:
  (go) or (start)          ;; Start the system
  (halt) or (stop)         ;; Stop the system
  (reset)                  ;; Reload code and restart system
  (reset-all)              ;; Full reset including config

Component Access:
  (system)                 ;; Get full running system
  (db)                     ;; Get database component
  (server)                 ;; Get HTTP server component
  (conn)                   ;; Get database connection

Database Helpers:
  (query '[...])           ;; Execute Datalog query
  (all-entities :attr)     ;; Get all entities with attribute
  (pull-entity eid)        ;; Pull entity by eid or lookup ref
  (transact! [...])        ;; Execute transaction

Status & Info:
  (status)                 ;; Show all components and their status
  (db-info)                ;; Database path, entity count, schema info
  (server-info)            ;; HTTP server port and health endpoint
  (show-schema)            ;; List all schema attributes
  (show-config)            ;; Display current system configuration

Development Utilities:
  (refresh)                ;; Reload changed namespaces
  (refresh-all)            ;; Reload all namespaces
  (clear-db!)              ;; Clear all data from database
  (test)                   ;; Run all tests
  (test 'namespace)        ;; Run tests for specific namespace

Test Data Creation:
  (create-test-user!)      ;; Create a test user
  (create-test-institution!) ;; Create a test institution

Debug & Introspection:
  sc.api/spy               ;; Capture values (via scope-capture)
  (source fn-name)         ;; View function source
  (doc fn-name)            ;; View function documentation
  (dir dev)                ;; List all functions in dev namespace

Server Info:
  Health:    http://localhost:8080/health
  Database:  ./data/dev.db

Documentation:
  doc/implementation/backend-quick-reference.md
  doc/implementation/adr-003-backend-phase1-complete.md
```

### 3. Powerful Development Tools ✅

#### Scope Capture Integration

```clojure
;; Automatically loaded on (dev)
(require 'sc.api)

;; Use in any expression to capture values
(let [x 10
      y (sc.api/spy (* x 2))    ; Captures the value 20
      z (+ y 5)]
  z)

;; View recent captures
(sc.api/ep-info)
```

#### Component Access Helpers

```clojure
;; Simple access to system components
(system)  ; Full system map
(db)      ; Database component
(server)  ; HTTP server component
(conn)    ; Database connection

;; Quick status check
(status)  ; Shows all running components
```

#### Database Helpers

```clojure
;; Easy database queries
(query '[:find ?name :where [_ :user/name ?name]])

;; Entity operations
(all-entities :user/id)
(pull-entity [:user/id "test-user"])
(transact! [{:user/id "new-user" ...}])

;; Introspection
(show-schema)
(db-info)
```

#### Development Utilities

```clojure
;; System information
(status)          ; Overall system status
(server-info)     ; HTTP server details
(db-info)         ; Database statistics
(show-config)     ; System configuration

;; Data management
(clear-db!)       ; Clear all data (with warning)

;; Test data creation
(create-test-user!)
(create-test-institution!)
```

#### Code Reloading

```clojure
;; Smart namespace reloading
(refresh)         ; Reload changed namespaces
(refresh-all)     ; Reload all namespaces

;; Full system reset
(reset)           ; Reload code and restart components
(reset-all)       ; Complete reset including config
```

### 4. Enhanced Dependencies ✅

Added to `:dev` and `:repl` aliases:

```clojure
{:dev
 {:extra-paths ["env/dev/src" "test"]
  :extra-deps {;; REPL and system management
               integrant/repl {:mvn/version "0.3.3"}
               org.clojure/tools.namespace {:mvn/version "1.5.0"}

               ;; Development and debugging
               vvvvalvalval/scope-capture {:mvn/version "0.3.3"}

               ;; Testing
               org.clojure/test.check {:mvn/version "1.1.1"}

               ;; REPL servers (for editor integration)
               nrepl/nrepl {:mvn/version "1.3.0"}
               cider/cider-nrepl {:mvn/version "0.50.2"}}}}
```

**Key additions:**
- **scope-capture**: Powerful debugging with value capture
- **tools.namespace**: Smart code reloading
- **integrant/repl**: Moved from main deps to dev-only

### 5. Smart Namespace Management ✅

```clojure
;; dev namespace configuration
(tns/disable-reload! (find-ns 'dev))
(tns/set-refresh-dirs "env/dev/src" "src" "test" "resources")
```

**Benefits:**
- Dev namespace doesn't reload (prevents loss of state)
- Only scans relevant directories
- Faster refresh times
- Prevents reload loops

## Comparison: Before vs After

### Starting the REPL

**Before:**
```bash
$ clojure -M:repl -m nrepl.cmdline
user=> (require 'dev)
user=> (in-ns 'dev)
dev=> (require 'integrant.repl)
dev=> (require '[integrant.repl :refer [go halt reset]])
dev=> (require '[finance-aggregator.sys :as sys])
dev=> (integrant.repl/set-prep! #(sys/prep-config ...))
dev=> (go)
```

**After:**
```bash
$ clojure -M:repl

[Friendly welcome banner appears automatically]

user=> (dev)

[Help message displayed, namespace switches to dev]

dev=> (go)
:initiated
```

### Workflow Comparison

| Task | Before | After |
|------|--------|-------|
| **Start REPL** | Blank prompt | Welcome message with commands |
| **Load dev tools** | Manual requires (5+ lines) | `(dev)` |
| **Start system** | `(integrant.repl/go)` | `(go)` or `(start)` |
| **Check status** | Manual queries | `(status)` |
| **Get DB connection** | `(get-conn (get system ...))` | `(conn)` |
| **Query database** | Full d/q with conn | `(query '[...])` |
| **View all users** | Write full query | `(all-entities :user/id)` |
| **Create test data** | Write full transact | `(create-test-user!)` |
| **Debug values** | `println` or log | `(sc.api/spy value)` |
| **Reload code** | Restart REPL | `(refresh)` or `(reset)` |

## Usage Examples

### Complete REPL Session

```clojure
;; 1. Start REPL
$ clojure -M:repl

;; 2. Load dev environment
user=> (dev)
;; [Help message shown]

;; 3. Start the system
dev=> (go)
:initiated

;; 4. Check status
dev=> (status)
System Status
=============
Components running:
  ✓ :finance-aggregator.system/db-path
  ✓ :finance-aggregator.system/http-port
  ✓ :finance-aggregator.db/connection
  ✓ :finance-aggregator.http/server

Database Connected:
  Path: ./data/dev.db
  Entities: 0
  Schema attributes: 35

HTTP Server Running:
  Port: 8080
  Health: http://localhost:8080/health

;; 5. Create some test data
dev=> (create-test-user!)
{:user/id "test-user", :user/email "test-user@example.com", ...}

;; 6. Query the data
dev=> (query '[:find ?email :where [_ :user/email ?email]])
[["test-user@example.com"]]

;; 7. After making code changes
dev=> (reset)
:reloading (...)
:resumed

;; 8. Stop the system
dev=> (halt)
:halted
```

### Debugging with Scope Capture

```clojure
;; Add sc.api/spy to capture intermediate values
(defn process-transaction [tx]
  (let [amount (sc.api/spy (:transaction/amount tx))
        category (sc.api/spy (determine-category tx))
        enriched (assoc tx :category category)]
    enriched))

;; Call the function
(process-transaction {:transaction/amount -50.00 ...})

;; View captured values
(sc.api/ep-info)
;; Shows all spy captures with file/line info
```

## Benefits

### Developer Experience
✅ **Faster onboarding** - New developers see available commands immediately
✅ **Less cognitive load** - No need to remember complex commands
✅ **Guided workflow** - Clear path from REPL start to working system
✅ **Professional appearance** - Polished, welcoming interface

### Productivity
✅ **Shorter feedback loops** - Quick status checks, easy queries
✅ **Better debugging** - Scope capture for value inspection
✅ **Efficient code reloading** - Smart refresh instead of REPL restart
✅ **Convenient helpers** - Common operations wrapped in simple functions

### Code Quality
✅ **Testable** - Easy to create test data and run tests
✅ **Maintainable** - Dev code organized and documented
✅ **Discoverable** - All helpers listed with `(dir dev)`
✅ **Self-documenting** - Docstrings on all helper functions

## Files Created/Modified

### New Files
- `env/dev/src/user.clj` - Initial namespace with welcome message
- `env/dev/src/dev.clj` - Development tools and helpers
- `demo-repl.sh` - Demo script for REPL experience

### Modified Files
- `deps.edn` - Added dev dependencies and reorganized aliases
- `doc/implementation/backend-quick-reference.md` - Updated with new workflow
- Old `dev/user.clj` - Removed (replaced by env/dev/src structure)

## Technical Details

### Auto-loading Mechanism

The `user.clj` namespace is automatically loaded by Clojure when the REPL starts (this is built into Clojure). Our implementation:

1. **user.clj** defines:
   - `(help)` - Shows command reference
   - `(dev)` - Loads dev namespace and switches context
   - Auto-calls `(help)` on load

2. **dev.clj** contains:
   - All development tools
   - Helper functions
   - Component accessors
   - Test utilities

### Namespace Isolation

```clojure
;; Prevent dev namespace from reloading
(tns/disable-reload! (find-ns 'dev))

;; Only scan relevant directories
(tns/set-refresh-dirs "env/dev/src" "src" "test" "resources")
```

This ensures:
- Dev namespace state persists across refreshes
- Faster reload times
- No circular dependency issues

### Scope Capture Integration

```clojure
;; Required as side effect in dev.clj
(require 'sc.api)
```

Makes `sc.api` immediately available for debugging without manual requires.

## Future Enhancements

Potential improvements for Phase 2:

1. **Portal integration** - Visual data browser
2. **REBL integration** - Graphical REPL browser
3. **Custom REPL prompt** - Show system status in prompt
4. **Hot-reload notifications** - Visual feedback on code changes
5. **Performance profiling** - Built-in profiling helpers
6. **Database fixtures** - Pre-loaded datasets for development

## References

- **Inspiration**: [Swirrl datahost-prototypes](https://github.com/Swirrl/datahost-prototypes/tree/dluhc-integration/datahost-ld-openapi)
- **Scope Capture**: [vvvvalvalval/scope-capture](https://github.com/vvvvalvalval/scope-capture)
- **Tools.namespace**: [clojure/tools.namespace](https://github.com/clojure/tools.namespace)
- **Integrant REPL**: [weavejester/integrant-repl](https://github.com/weavejester/integrant-repl)

## Conclusion

The dev environment now provides a **professional, welcoming, and productive** REPL experience that:
- Guides developers from first startup
- Reduces friction in common tasks
- Provides powerful debugging tools
- Follows Clojure community best practices

This is a solid foundation for efficient REPL-driven development! 🚀

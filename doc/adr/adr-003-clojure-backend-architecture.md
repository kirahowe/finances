# ADR-003: Clojure Backend Architecture

**Date:** 2025-11-24
**Status:** Proposed

> **Architectural Paradigm**: This is a **modular, layered backend architecture** using Integrant for component management and Datalevin as the source of truth. The backend follows Clojure principles: simple, decoupled, single-purpose modules with pure functions and explicit dependency injection.

## Context

The finance aggregator has an excellent foundation: working SimpleFin integration, solid Datalevin schema, and a functional HTTP API serving the React frontend. However, the current implementation lacks the structure needed to scale to production requirements.

### Current State (Strengths)

**Excellent Data Model** (`data/schema.clj`):
- Finance-domain specific (institutions, accounts, transactions, categories)
- Proper entity relationships using Datalevin refs
- Support for hierarchical categories
- Auto-categorization infrastructure (payee rules)
- Balance snapshot tracking for reconciliation
- Transaction tagging and transfer pairing

**Clean SimpleFin Integration**:
- Pure functions with dependency injection
- Testable transformation pipeline (SimpleFin JSON → Datalevin entities)
- Month-based transaction fetching
- Institution/account/transaction normalization

**Working HTTP API** (`server.clj`):
- http-kit server with Ring handler
- JSON request/response
- RESTful endpoints for categories, transactions, accounts
- CORS support for frontend development
- Batch operations

**Test-Driven Implementation**:
- Comprehensive test coverage (10 test files)
- Test utilities for setup/teardown
- Integration and unit tests

### Current State (Gaps)

**No System Management**:
- Global `defonce conn` instead of managed lifecycle
- Manual server start/stop
- No configuration management
- Hard to test with different configs
- No graceful shutdown

**No User Scoping**:
- Single-tenant architecture
- No access control
- Cannot support multiple users
- All data global

**No Authentication/Authorization**:
- All endpoints public
- No JWT verification
- No protected routes

**No Request/Response Validation**:
- Basic JSON parsing only
- No schema validation
- Inconsistent error messages

**No Credential Management**:
- No encrypted credential storage
- SimpleFin tokens not persisted
- No multi-user credential isolation

**No Ingestion Scheduler**:
- Manual SimpleFin fetching only
- No periodic sync
- No error tracking or retry logic

### Requirements

The backend must support:

1. **Multi-user support** - Eventually support multiple users with data isolation
2. **REPL-driven development** - Fast feedback loop with `(go)`, `(reset)`, `(halt)`
3. **Environment-based configuration** - Different settings for dev/prod
4. **Secure credential storage** - Encrypted SimpleFin access URLs
5. **Scheduled ingestion** - Periodic SimpleFin sync (optional, configurable)
6. **Request validation** - Strong API boundaries with Malli schemas
7. **Authentication** - Optional/pluggable auth for production
8. **Maintainability** - Clear separation of concerns, single-purpose modules
9. **Testability** - All components injectable, no global state

### Clojure Principles

The architecture must follow the Clojure way:
- **Simple**: Each module does one thing well
- **Decoupled**: Dependencies injected, not hardcoded
- **Pure functions**: Business logic free of side effects
- **Data-oriented**: Embrace data transformation pipelines
- **REPL-friendly**: Live coding workflow

## Decision

### Technology Stack

We will adopt the modular, layered architecture, adapted for the finance domain:

**Core Technologies**:
- **System Management**: Integrant (component lifecycle)
- **Configuration**: EDN files with environment overlays
- **Database**: Datalevin (existing, enhanced with user scoping)
- **Web Server**: http-kit (existing, wrapped in Integrant)
- **Routing**: Reitit with coercion middleware
- **Validation**: Malli schemas for all API boundaries
- **Authentication**: Hanko (optional/pluggable for dev vs. prod)
- **Scheduling**: Chime (optional, for periodic ingestion)
- **HTTP Client**: clj-http (existing SimpleFin client)

### Layered Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                  INFRASTRUCTURE LAYER                        │
│  - System management (Integrant components)                 │
│  - Configuration loading (EDN + environment overlays)       │
│  - Database connection lifecycle                            │
│  - HTTP server lifecycle                                    │
│  - Scheduler lifecycle (optional)                           │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                   DATA ACCESS LAYER                         │
│  - db/core.clj: Connection management only                  │
│  - db/schema.clj: Enhanced Datalevin schema (+ user refs)   │
│  - db/queries.clj: All read operations (user-scoped)        │
│  - db/transactions.clj: All write operations (user-scoped)  │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                    DOMAIN LAYER                             │
│  - domain/categories.clj: Business rules, validation        │
│  - domain/transactions.clj: Transaction logic               │
│  - domain/accounts.clj: Account calculations                │
│  - domain/rules.clj: Auto-categorization rules              │
│  - All pure functions (testable without DB)                 │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                  INTEGRATION LAYER                          │
│  - simplefin/client.clj: HTTP calls (pure, testable)        │
│  - simplefin/data.clj: Transformations (pure)               │
│  - simplefin/service.clj: Orchestration (uses components)   │
│  - credentials.clj: Encrypt/decrypt (AES-256-GCM)           │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                   APPLICATION LAYER                         │
│  - api/routes.clj: Reitit router configuration              │
│  - api/handlers.clj: Request→Domain→Response                │
│  - api/middleware.clj: Auth, CORS, logging                  │
│  - validation/schemas.clj: Malli request/response schemas   │
└─────────────────────────────────────────────────────────────┘
```

### Component Architecture (Integrant)

**Component Hierarchy**:
```clojure
;; resources/system/base-system.edn
{:finance-aggregator.system/db-path "./data/finance.db"
 :finance-aggregator.system/http-port 8080

 ;; Database component
 :finance-aggregator.db/connection
 {:db-path #ig/ref :finance-aggregator.system/db-path}

 ;; Optional auth component
 :finance-aggregator.auth/hanko nil  ; Disabled by default

 ;; API router
 :finance-aggregator.api/router
 {:db #ig/ref :finance-aggregator.db/connection
  :auth #ig/ref :finance-aggregator.auth/hanko}

 ;; HTTP server
 :finance-aggregator.http/server
 {:port #ig/ref :finance-aggregator.system/http-port
  :handler #ig/ref :finance-aggregator.api/router}

 ;; Optional scheduler
 :finance-aggregator.ingestion/scheduler nil}  ; Disabled by default

;; resources/system/dev.edn
{:finance-aggregator.system/db-path "./data/dev.db"
 :finance-aggregator.system/http-port 8080
 :finance-aggregator.auth/hanko nil           ; No auth in dev
 :finance-aggregator.ingestion/scheduler nil} ; Manual sync only

;; resources/system/prod.edn
{:finance-aggregator.system/db-path #env "DB_PATH"
 :finance-aggregator.system/http-port #env "PORT"

 :finance-aggregator.auth/hanko
 {:api-url #env "HANKO_API_URL"
  :jwks-url #env "HANKO_JWKS_URL"}

 :finance-aggregator.ingestion/scheduler
 {:schedule "0 2 * * *"  ; Daily at 2 AM
  :sources [:simplefin]}}
```

**REPL Workflow**:
```clojure
;; dev/user.clj
(ns user
  (:require [finance-aggregator.sys :as sys]
            [integrant.repl :refer [go halt reset]]
            [integrant.repl.state :as state]))

(integrant.repl/set-prep!
 #(sys/prep-config
   (sys/load-configs ["system/base-system.edn"
                      "system/dev.edn"])))

(comment
  (go)     ; Start system
  (reset)  ; Reload and restart
  (halt)   ; Stop system

  ;; Access components
  (def db (:finance-aggregator.db/connection state/system))

  ;; Test queries directly
  (require '[finance-aggregator.db.queries :as q])
  (q/find-transactions-by-user db "test-user" {:year 2024}))
```

### Module Organization

**Single-Purpose Principle**: Each namespace has ONE responsibility.

#### Infrastructure Layer

**`sys.clj`** - System lifecycle:
```clojure
(ns finance-aggregator.sys
  (:require [integrant.core :as ig]))

(defn load-configs [paths]
  "Load and merge EDN config files")

(defn prep-config [config]
  "Validate config with Malli, load namespaces")

(defn start-system! [config]
  (ig/init config))

(defn stop-system! [system]
  (ig/halt! system))
```

**`system.clj`** - Component definitions:
```clojure
(ns finance-aggregator.system
  (:require [integrant.core :as ig]
            [finance-aggregator.db :as db]))

;; Database component
(defmethod ig/init-key ::db/connection [_ config]
  (validate-config! db-connection-schema config)
  (db/start-db! (:db-path config)))

(defmethod ig/halt-key! ::db/connection [_ conn]
  (db/stop-db! conn))

;; HTTP server component
(defmethod ig/init-key ::http/server [_ config]
  (validate-config! http-server-schema config)
  (start-server! config))

(defmethod ig/halt-key! ::http/server [_ server]
  (stop-server! server))
```

#### Data Access Layer

**`db/core.clj`** - Connection lifecycle ONLY:
```clojure
(ns finance-aggregator.db.core)

(defn start-db! [db-path]
  "Open Datalevin connection with schema")

(defn stop-db! [conn]
  "Close Datalevin connection")

(defn get-conn [db-component]
  "Extract connection from component")
```

**`db/schema.clj`** - Enhanced schema with user scoping:
```clojure
;; Add user namespace
:user/id {:db/unique :db.unique/identity}
:user/email {}
:user/created-at {}

;; Add user references to existing entities
:account/user {:db/valueType :db.type/ref}
:category/user {:db/valueType :db.type/ref}  ; nil = system category
:transaction/user {:db/valueType :db.type/ref}  ; Denormalized for query speed

;; Add credential storage
:credential/id {:db/unique :db.unique/identity}
:credential/user {:db/valueType :db.type/ref}
:credential/institution {:db/valueType :db.type/keyword}  ; :simplefin
:credential/encrypted-data {}  ; AES-256-GCM encrypted
:credential/created-at {}
:credential/last-used {}
```

**`db/queries.clj`** - All read operations (user-scoped):
```clojure
(ns finance-aggregator.db.queries)

(defn find-transactions-by-user [db-component user-id filters]
  "Find transactions for user with optional filters"
  (let [conn (db/get-conn db-component)]
    (d/q '[:find [(pull ?tx [*]) ...]
           :in $ ?user-id
           :where
           [?user :user/id ?user-id]
           [?tx :transaction/user ?user]]
         @conn
         user-id)))

(defn find-categories-by-user [db-component user-id]
  "Find user's categories plus system categories"
  (let [conn (db/get-conn db-component)]
    (d/q '[:find [(pull ?cat [*]) ...]
           :in $ ?user-id
           :where
           [?user :user/id ?user-id]
           (or [?cat :category/user ?user]
               [(missing? $ ?cat :category/user)])]
         @conn
         user-id)))
```

**`db/transactions.clj`** - All write operations (user-scoped):
```clojure
(ns finance-aggregator.db.transactions)

(defn insert-entities! [db-component user-id entities]
  "Insert entities with user reference"
  (let [conn (db/get-conn db-component)]
    (d/transact! conn
      (map #(assoc % :user user-id) entities))))

(defn update-transaction-category! [db-component user-id tx-id category-id]
  "Update transaction category (user-scoped for security)"
  (let [conn (db/get-conn db-component)
        ;; Verify transaction belongs to user
        tx (d/pull @conn [:transaction/user] tx-id)]
    (when (= (:transaction/user tx) user-id)
      (d/transact! conn
        [{:db/id tx-id
          :transaction/category category-id}]))))
```

#### Domain Layer

**Pure functions only** - no DB dependencies:

```clojure
;; domain/transactions.clj
(ns finance-aggregator.domain.transactions)

(defn validate-transfer-pair [tx1 tx2]
  "Business rule: transfers must balance"
  (when-not (= (- (:amount tx1)) (:amount tx2))
    {:error "Transfer amounts must be equal and opposite"}))

(defn calculate-account-balance [transactions]
  "Pure calculation from transaction list"
  (reduce + 0M (map :transaction/amount transactions)))

(defn categorize-by-rules [transaction rules]
  "Apply payee rules to suggest category (pure)"
  (some #(when (matches-rule? transaction %) (:category %)) rules))
```

```clojure
;; domain/categories.clj
(ns finance-aggregator.domain.categories)

(defn validate-category-type [category]
  "Business rule: type must be valid"
  (when-not (#{:expense :income :transfer} (:category/type category))
    {:error "Category type must be :expense, :income, or :transfer"}))

(defn can-delete-category? [category transaction-count]
  "Business rule: can't delete if transactions assigned"
  (zero? transaction-count))
```

#### Integration Layer

**`simplefin/client.clj`** - Pure HTTP client:
```clojure
(ns finance-aggregator.simplefin.client
  (:require [clj-http.client :as http]))

(defn fetch-accounts
  "Fetch accounts from SimpleFin (pure function, testable)"
  [access-url]
  (-> (http/get (str access-url "/accounts")
                {:as :json})
      :body))

(defn fetch-transactions
  "Fetch transactions for date range (pure function)"
  [access-url start-date end-date]
  (-> (http/get (str access-url "/transactions")
                {:query-params {:start-date start-date
                                :end-date end-date}
                 :as :json})
      :body))
```

**`simplefin/data.clj`** - Pure transformations:
```clojure
(ns finance-aggregator.simplefin.data)

(defn simplefin-account->entity
  "Transform SimpleFin account JSON → Datalevin entity (pure)"
  [sf-account]
  {:account/external-id (:id sf-account)
   :account/name (:name sf-account)
   :account/balance (bigdec (:balance sf-account))
   ;; ... more transformations
   })

(defn simplefin-transaction->entity
  "Transform SimpleFin transaction JSON → Datalevin entity (pure)"
  [sf-transaction account-id]
  {:transaction/external-id (:id sf-transaction)
   :transaction/account account-id
   :transaction/amount (bigdec (:amount sf-transaction))
   ;; ... more transformations
   })
```

**`simplefin/service.clj`** - Orchestration with dependencies:
```clojure
(ns finance-aggregator.simplefin.service
  (:require [finance-aggregator.simplefin.client :as client]
            [finance-aggregator.simplefin.data :as data]
            [finance-aggregator.db.queries :as q]
            [finance-aggregator.db.transactions :as tx]))

(defn sync-user-data!
  "Orchestrate SimpleFin sync (uses DB component)"
  [db-component creds-component user-id year month]
  (let [;; Load encrypted credentials
        access-url (get-decrypted-credential creds-component user-id :simplefin)

        ;; Fetch from SimpleFin (pure)
        accounts (client/fetch-accounts access-url)
        transactions (client/fetch-transactions access-url
                       (format "%d-%02d-01" year month)
                       (format "%d-%02d-31" year month))

        ;; Transform (pure)
        account-entities (map data/simplefin-account->entity accounts)
        tx-entities (mapcat #(map (fn [t]
                                     (data/simplefin-transaction->entity
                                       t (:account/external-id %)))
                                   (:transactions %))
                            accounts)

        ;; Persist (side effect)
        _ (tx/insert-entities! db-component user-id account-entities)
        _ (tx/insert-entities! db-component user-id tx-entities)]

    {:accounts (count account-entities)
     :transactions (count tx-entities)}))
```

**`credentials.clj`** - Encryption/decryption:
```clojure
(ns finance-aggregator.credentials
  (:require [buddy.core.crypto :as crypto]))

(defn encrypt-credential
  "Encrypt credential data with AES-256-GCM"
  [encryption-key data]
  (crypto/encrypt data encryption-key))

(defn decrypt-credential
  "Decrypt credential data"
  [encryption-key encrypted-data]
  (crypto/decrypt encrypted-data encryption-key))

(defn store-credential!
  "Store encrypted credential for user"
  [db-component user-id institution data encryption-key]
  (let [encrypted (encrypt-credential encryption-key data)]
    (tx/insert-entities! db-component user-id
      [{:credential/user user-id
        :credential/institution institution
        :credential/encrypted-data encrypted
        :credential/created-at (java.util.Date.)}])))
```

#### Application Layer

**`api/routes.clj`** - Reitit router configuration:
```clojure
(ns finance-aggregator.api.routes
  (:require [reitit.ring :as ring]
            [finance-aggregator.api.handlers :as handlers]))

(defn create-router [db-component auth-component]
  (ring/router
    [["/api"
      ["/transactions"
       {:get {:handler (handlers/list-transactions-handler db-component)
              :middleware [(auth-middleware auth-component)]}
        :post {:handler (handlers/create-transaction-handler db-component)
               :middleware [(auth-middleware auth-component)]}}]

      ["/transactions/:id/category"
       {:put {:handler (handlers/update-category-handler db-component)
              :middleware [(auth-middleware auth-component)]}}]

      ["/categories"
       {:get {:handler (handlers/list-categories-handler db-component)
              :middleware [(auth-middleware auth-component)]}
        :post {:handler (handlers/create-category-handler db-component)
               :middleware [(auth-middleware auth-component)]}}]

      ["/sync"
       {:post {:handler (handlers/sync-simplefin-handler db-component)
               :middleware [(auth-middleware auth-component)]}}]]]

    {:data {:middleware [middleware/wrap-cors
                         middleware/wrap-json-body
                         middleware/wrap-json-response]}}))
```

**`api/handlers.clj`** - Thin request/response mapping:
```clojure
(ns finance-aggregator.api.handlers
  (:require [finance-aggregator.db.queries :as q]
            [finance-aggregator.validation.schemas :as schemas]))

(defn list-transactions-handler [db-component]
  (fn [request]
    (let [user-id (:user-id request)  ; Injected by auth middleware
          filters (parse-query-params request)
          _ (schemas/validate! schemas/TransactionFilters filters)
          transactions (q/find-transactions-by-user db-component user-id filters)]
      {:status 200
       :body {:transactions (map entity->response transactions)}})))

(defn update-category-handler [db-component]
  (fn [request]
    (let [user-id (:user-id request)
          tx-id (parse-long (get-in request [:path-params :id]))
          body (:body-params request)
          _ (schemas/validate! schemas/UpdateCategoryRequest body)
          result (tx/update-transaction-category! db-component
                   user-id tx-id (:category-id body))]
      {:status 200
       :body {:transaction (entity->response result)}})))
```

**`api/middleware.clj`** - Auth, CORS, logging:
```clojure
(ns finance-aggregator.api.middleware
  (:require [finance-aggregator.auth.hanko :as hanko]))

(defn wrap-authentication
  "Extract and verify JWT, inject user-id into request"
  [handler auth-component]
  (fn [request]
    (if-let [token (extract-bearer-token request)]
      (if-let [user-id (hanko/verify-jwt auth-component token)]
        (handler (assoc request :user-id user-id))
        {:status 401 :body {:error "Invalid token"}})
      {:status 401 :body {:error "Missing authorization header"}})))

(defn wrap-mock-auth
  "Mock authentication for development (no real auth)"
  [handler]
  (fn [request]
    (handler (assoc request :user-id "dev-user"))))
```

**`validation/schemas.clj`** - Malli schemas:
```clojure
(ns finance-aggregator.validation.schemas
  (:require [malli.core :as m]))

(def Transaction
  [:map
   [:transaction/external-id :string]
   [:transaction/amount :bigdec]
   [:transaction/payee :string]
   [:transaction/date inst?]])

(def CreateTransactionRequest
  [:map
   [:amount [:and :bigdec [:> 0]]]
   [:payee [:string {:min 1}]]
   [:date inst?]
   [:category-id {:optional true} :int]])

(def UpdateCategoryRequest
  [:map
   [:category-id [:maybe :int]]])

(defn validate! [schema data]
  (when-not (m/validate schema data)
    (throw (ex-info "Validation failed"
             {:errors (m/explain schema data)}))))
```

### Key Design Patterns

#### 1. Dependency Injection

All stateful dependencies are injected, never global:

```clojure
;; Good - dependencies injected
(defn sync-data! [db-component creds-component user-id]
  ...)

;; Bad - global state (don't do this)
(defn sync-data! [user-id]
  (let [conn @db/conn]  ; Global!
    ...))
```

#### 2. User-Scoped Everything

Every query and mutation includes user-id for data isolation:

```clojure
;; All queries user-scoped
(q/find-transactions-by-user db user-id filters)
(q/find-categories-by-user db user-id)

;; All mutations user-scoped
(tx/insert-entities! db user-id entities)
(tx/update-transaction-category! db user-id tx-id cat-id)
```

#### 3. Pure Domain Logic

Business logic is pure functions, testable without DB:

```clojure
;; Pure - easy to test
(domain/validate-transfer-pair tx1 tx2)
(domain/calculate-account-balance transactions)
(domain/categorize-by-rules transaction rules)

;; Side effects isolated in service layer
(service/sync-user-data! db creds user-id year month)
```

#### 4. Validate at Boundaries

Validate all inputs at API layer, trust internally:

```clojure
;; API handler validates
(defn create-transaction-handler [db]
  (fn [request]
    (schemas/validate! schemas/CreateTransactionRequest (:body request))
    (let [result (tx/create-transaction! db ...)]
      ...)))

;; Internal functions trust callers
(defn create-transaction! [db user-id tx-data]
  ;; No validation - already validated at boundary
  (insert-entities! db user-id [tx-data]))
```

#### 5. Optional Components

Make expensive/complex components optional via config:

```clojure
;; dev.edn - minimal components
{:finance-aggregator.auth/hanko nil           ; Disabled
 :finance-aggregator.ingestion/scheduler nil} ; Disabled

;; prod.edn - full stack
{:finance-aggregator.auth/hanko {...}         ; Enabled
 :finance-aggregator.ingestion/scheduler {...}} ; Enabled
```

### Data Flow Examples

#### Example 1: System Startup

```
1. Developer: (go) in REPL
2. sys.clj: load-configs ["system/base-system.edn" "system/dev.edn"]
3. sys.clj: prep-config (load namespaces, validate with Malli)
4. Integrant: init components in dependency order
   a. ::db/connection (opens Datalevin)
   b. ::api/router (builds Reitit router with DB injected)
   c. ::http/server (starts http-kit on port 8080)
5. System running, components stored in state/system atom
6. Developer edits code, runs (reset)
7. Integrant: halt! all components, reload code, init again
```

#### Example 2: User Syncs SimpleFin Data

```
1. Frontend: POST /api/sync {year: 2024, month: 11}
   Headers: Authorization: Bearer <JWT>
2. Middleware: Verify JWT with Hanko, inject user-id
3. Middleware: Validate request body with Malli
4. Handler: Delegates to simplefin/service
5. Service:
   - Load encrypted credentials from DB
   - Fetch from SimpleFin (pure client function)
   - Transform data (pure transformation)
   - Insert into DB with user reference
6. Handler: Return {:accounts 3, :transactions 45}
7. Frontend: Show "Synced 45 transactions from 3 accounts"
```

#### Example 3: User Updates Transaction Category

```
1. Frontend: PUT /api/transactions/123/category {category-id: 456}
   Headers: Authorization: Bearer <JWT>
2. Middleware: Verify JWT, inject user-id
3. Middleware: Validate request body
4. Handler: Calls tx/update-transaction-category!
5. DB Layer:
   - Verify transaction belongs to user (security)
   - Update transaction category
   - Return updated entity
6. Handler: Transform entity to response format
7. Frontend: Show updated transaction with new category
```

### File Structure

```
backend/
├── deps.edn
├── tests.edn
├── dev/
│   └── user.clj                    # REPL helpers
├── resources/
│   └── system/
│       ├── base-system.edn         # Base configuration
│       ├── dev.edn                 # Development overrides
│       └── prod.edn                # Production overrides
├── src/finance_aggregator/
│   ├── sys.clj                     # System lifecycle
│   ├── system.clj                  # Component definitions
│   │
│   ├── data/
│   │   └── schema.clj              # Enhanced Datalevin schema
│   │
│   ├── db/
│   │   ├── core.clj                # Connection lifecycle
│   │   ├── queries.clj             # Read operations (user-scoped)
│   │   └── transactions.clj        # Write operations (user-scoped)
│   │
│   ├── domain/
│   │   ├── categories.clj          # Category business logic (pure)
│   │   ├── transactions.clj        # Transaction business logic (pure)
│   │   ├── accounts.clj            # Account calculations (pure)
│   │   └── rules.clj               # Auto-categorization rules (pure)
│   │
│   ├── simplefin/
│   │   ├── client.clj              # HTTP client (pure)
│   │   ├── data.clj                # Transformations (pure)
│   │   └── service.clj             # Orchestration (uses components)
│   │
│   ├── credentials.clj             # Encryption/decryption
│   │
│   ├── auth/
│   │   ├── hanko.clj               # JWT verification
│   │   └── middleware.clj          # Auth middleware
│   │
│   ├── api/
│   │   ├── routes.clj              # Reitit router
│   │   ├── handlers.clj            # Request/response mapping
│   │   └── middleware.clj          # CORS, JSON, logging
│   │
│   ├── validation/
│   │   └── schemas.clj             # Malli schemas
│   │
│   └── ingestion/
│       ├── scheduler.clj           # Chime scheduler
│       └── core.clj                # Multi-source orchestration
│
└── test/finance_aggregator/
    ├── system_test.clj             # Integration tests
    ├── db/
    │   ├── queries_test.clj
    │   └── transactions_test.clj
    ├── domain/
    │   ├── categories_test.clj
    │   └── transactions_test.clj
    ├── simplefin/
    │   ├── client_test.clj
    │   ├── data_test.clj
    │   └── service_test.clj
    └── api/
        └── handlers_test.clj
```

### Migration Strategy

The existing code is solid. We'll enhance it incrementally:

**Phase 1: Infrastructure (Integrant)**
- Create `sys.clj` and `system.clj`
- Create `resources/system/*.edn` configs
- Wrap existing `db.clj` connection in Integrant component
- Wrap existing `server.clj` in Integrant component
- Set up `dev/user.clj` for REPL workflow
- Test: Verify `(go)`, `(reset)`, `(halt)` work

**Phase 2: User Scoping**
- Enhance `data/schema.clj` with `:user/*` namespace
- Add user references to accounts, categories, transactions
- Update all queries in `db/categories.clj` and new `db/queries.clj`
- Update all mutations to require user-id
- Migrate existing data with mock user
- Test: Verify data isolation between users

**Phase 3: Credential Management**
- Add `:credential/*` to schema
- Create `credentials.clj` with encryption
- Store SimpleFin access URLs encrypted in DB
- Update SimpleFin service to load credentials
- Test: Verify credential encryption/decryption

**Phase 4: Validation & Auth**
- Create `validation/schemas.clj` with Malli
- Add validation to all API handlers
- Create `auth/middleware.clj` with mock auth (dev) and Hanko (prod)
- Protect all routes with auth middleware
- Test: Verify validation catches bad requests

**Phase 5: Domain Refactoring**
- Extract pure logic from `db/categories.clj` → `domain/categories.clj`
- Create `domain/transactions.clj` for business rules
- Create `domain/accounts.clj` for calculations
- Update handlers to use domain layer
- Test: Pure functions with 100% coverage

**Phase 6: Modular API Layer**
- Refactor `server.clj` → `api/routes.clj` + `api/handlers.clj`
- Move middleware to `api/middleware.clj`
- Integrate Reitit with coercion
- Test: All API endpoints still work

**Phase 7: Optional Scheduler**
- Create `ingestion/scheduler.clj` with Chime
- Create `ingestion/core.clj` for orchestration
- Make scheduler optional in config
- Test: Scheduler syncs data periodically

Each phase is independently deployable and testable.

## Consequences

### Positive

1. **REPL-Driven Development**
   - Fast feedback loop with `(go)`, `(reset)`, `(halt)`
   - Interactive exploration of data and functions
   - No server restarts during development
   - Access to live system in REPL

2. **Multi-User Ready**
   - User-scoped data from day one
   - No data leakage between users
   - Easy to add household sharing later
   - Secure by default

3. **Testable Architecture**
   - Pure domain functions easy to test
   - Components injectable in tests
   - No global state to manage
   - Fast test execution

4. **Flexible Configuration**
   - Different settings for dev/prod
   - Optional components (auth, scheduler)
   - Environment variables for secrets
   - Easy to add new environments

5. **Clear Separation of Concerns**
   - Each module has one responsibility
   - Dependencies explicit and injected
   - Easy to understand code flow
   - Low coupling, high cohesion

6. **Graceful Degradation**
   - Auth optional (dev) or required (prod)
   - Scheduler optional (manual sync works)
   - Each component can be enabled/disabled
   - Start simple, add complexity as needed

7. **Maintains Current Strengths**
   - Keeps excellent data model
   - Keeps working SimpleFin integration
   - Keeps http-kit server
   - Wraps rather than rewrites

### Negative

1. **Learning Curve for Integrant**
   - Team needs to understand component lifecycle
   - Config-driven initialization is new pattern
   - **Mitigation**: Good documentation, clear examples, pair programming
   - **Acceptable**: One-time cost, long-term benefits

2. **More Boilerplate Initially**
   - Component definitions require boilerplate
   - Config files need maintenance
   - More files than current simple setup
   - **Mitigation**: Generators for new components, good templates
   - **Acceptable**: Clarity worth the extra structure

3. **User Scoping Adds Complexity**
   - Every query/mutation needs user-id
   - More careful about data isolation
   - Testing requires user context
   - **Mitigation**: Helper functions, test fixtures with users
   - **Acceptable**: Required for multi-user support

4. **Configuration Split Across Files**
   - Base config + environment overlays
   - Need to check multiple files for settings
   - **Mitigation**: Clear naming, documentation, validation errors cite file paths
   - **Acceptable**: Better than environment-specific duplication

## Open Questions

1. **Encryption Key Management**: How to securely store the encryption key for credentials?
   - Proposal: Environment variable in prod, hardcoded in dev
   - Future: Consider AWS KMS or similar key management service

2. **Rate Limiting**: Should we add rate limiting to SimpleFin API calls?
   - Proposal: Not in MVP, add if SimpleFin complains

3. **Audit Logging**: Do we need audit trail of all user actions?
   - Proposal: Not in MVP, Datalevin's temporal features could support this later

4. **Error Tracking**: Sentry, Rollbar, or custom solution?
   - Proposal: Start with simple logging, add Sentry if needed

5. **API Versioning**: Should we version the API from day one?
   - Proposal: Not yet, internal API only (we control frontend)

6. **Background Job Failures**: How to handle failed SimpleFin syncs?
   - Proposal: Store errors in DB, expose in UI, manual retry
   - Future: Exponential backoff, automatic retry

These will be addressed during implementation.

## References

- [Integrant Documentation](https://github.com/weavejester/integrant)
- [Datalevin Documentation](https://github.com/juji-io/datalevin)
- [Reitit Documentation](https://cljdoc.org/d/metosin/reitit)
- [Malli Documentation](https://github.com/metosin/malli)
- [Ring Specification](https://github.com/ring-clojure/ring/wiki/Concepts)
- [Chime Scheduler](https://github.com/jarohen/chime)
- [Clojure Style Guide](https://guide.clojure.style/)
- [Buddy Cryptography](https://funcool.github.io/buddy-core/latest/)
- ADR-002 (React frontend architecture)

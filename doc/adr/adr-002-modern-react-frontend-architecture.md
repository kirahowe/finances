# ADR-002: Modern React Frontend Architecture

**Date:** 2025-11-18
**Updated:** 2025-11-18
**Status:** Proposed
**Supersedes:** ADR-001 (Scittle/Reagent Prototype)

> **Architectural Paradigm**: This is a **server-first architecture** with offline capabilities, not strictly an offline-first architecture. The Datalevin db is the source of truth. The application works online by default, with the ability to queue mutations when offline and sync when reconnected.

## Context

After validating the backend API and data model with a Scittle prototype, we're building a production-grade React frontend. The requirements are:

### Core Requirements
1. **Offline-capable editing** - Works online by default, queues mutations when offline, syncs when network is available
2. **Multi-user support** - Eventually support concurrent editing (e.g., household finances)
3. **Server-side rendering** - Fast initial paint, graceful degradation
4. **Server as source of truth** - Datalevin owns all persistent state (Phoenix LiveView philosophy)
5. **Test-driven** - Pure functions, minimal state, comprehensive tests
6. **Semantic markup + CSS** - Complete separation of structure and presentation

### Use Cases
- **Primary**: Single user online (optimize for this)
- **Future**: Single user, multiple devices, with offline editing
- **Future**: Multiple users (e.g. households) editing concurrently
- **Future**: Real-time collaboration (not MVP)

### Clarification: Server-First vs Offline-First

**This architecture is server-first with offline capability:**

| Aspect | Server-First (This Architecture) | True Offline-First |
|--------|----------------------------------|-------------------|
| Source of Truth | Server (Datalevin) | Local (IndexedDB, Y.js) |
| Default Mode | Online, server round-trips | Always local, background sync |
| Offline Behavior | Queue mutations, sync later | Works identically offline |
| Complexity | Lower (proven patterns) | Higher (distributed systems) |
| Primary Use Case | Online with occasional offline | Offline-dominant usage |

**Why server-first?**
- Primary use case is "single user online"
- Simpler architecture, fewer edge cases
- Server rendering and progressive enhancement require server as source of truth
- Can add offline queue later without rewriting core architecture
- Avoids premature optimization for hypothetical offline-first requirements

### Constraints
- **Backend**: Clojure + Datalevin (source of truth)
- **External sync**: Batch ingestion (SimpleFIN, CSV uploads, etc.)
- **No Tailwind**: Semantic HTML + proper CSS organization
- **No BEM**: Use component library + clean CSS

## Decision

### Technology Stack

#### Core Framework: **Remix (React Router v7)**

**Why Remix over TanStack Router?**

1. **SSR + Progressive Enhancement (Required)**
   - SSR built-in (explicit requirement #3)
   - Forms work without JavaScript (requirement #4)
   - Graceful degradation out-of-the-box
   - TanStack Router would require DIY SSR or giving this up entirely

2. **Server-First Architecture (Core Design)**
   - Loaders/actions align perfectly with server as source of truth
   - Automatic revalidation after mutations
   - No impedance mismatch with Clojure backend
   - TanStack Router is client-first, would fight this design

3. **Simplicity for Primary Use Case**
   - Single user online = standard Remix patterns
   - Well-documented, proven approach
   - Lower complexity than client-first routing

4. **Works with Existing Backend**
   - Clean REST API integration
   - No backend changes needed
   - Clear separation of concerns

**When TanStack Router would be better:**
- Pure SPA with no SSR requirements
- Client-first architecture with local state as source of truth
- Need extreme TypeScript inference (marginal benefit here)
- Existing backend API you don't control (but we do control ours)

**Trade-offs accepted:**
- Less TypeScript inference than TanStack Router (acceptable - backend is typed with malli specs)
- Opinionated patterns vs flexibility (acceptable - opinions improve consistency)
- Larger bundle size than React Router v6 (acceptable - SSR mitigates this)

#### CRDT: **Y.js (Yjs)** - Future Enhancement

Y.js will be added later if/when offline editing or real-time collaboration becomes necessary.

**Why Y.js over Automerge (when we add it)?**
- More mature, battle-tested (Figma, Notion use it)
- Excellent performance with large documents
- Rich ecosystem (y-websocket, y-indexeddb, y-protocols)
- Better TypeScript support
- Network-agnostic (WebSocket, WebRTC, HTTP)

**MVP Approach**: No Y.js. Pure Remix with server as source of truth.

**Future Y.js Architecture** (when/if needed):
```typescript
import * as Y from 'yjs'
import { IndexeddbPersistence } from 'y-indexeddb'

// Use Y.js as offline mutation queue only
const offlineQueue = new Y.Doc()
const persistence = new IndexeddbPersistence('offline-mutations', offlineQueue)

// Queue mutations when offline
if (!navigator.onLine) {
  offlineQueue.getArray('pending-mutations').push([{
    type: 'update-category',
    transactionId: 123,
    categoryId: 456,
    timestamp: Date.now()
  }])
}

// Drain queue when back online
window.addEventListener('online', () => {
  drainOfflineQueue() // POST each mutation to Clojure API
})
```

**Key Point**: Y.js is an enhancement, not a replacement for Remix loaders/actions.

#### UI Components: **Radix UI** + **CSS Modules**

**Radix UI Primitives**
- Unstyled, accessible components
- Dialog, Dropdown, Select, Popover, etc.
- WAI-ARIA compliant
- Bring your own styles

**CSS Organization**
```
styles/
  base/
    reset.css           # Normalize
    typography.css      # Font scales, hierarchy
    colors.css          # CSS variables
  components/
    TransactionRow.module.css
    CategoryDropdown.module.css
  layouts/
    dashboard.module.css
```

**Example component**:
```tsx
// TransactionRow.tsx
import styles from './TransactionRow.module.css'

export function TransactionRow({ transaction }) {
  return (
    <article className={styles.row}>
      <time className={styles.date}>{transaction.date}</time>
      <span className={styles.payee}>{transaction.payee}</span>
      <data className={styles.amount} value={transaction.amount}>
        ${transaction.amount}
      </data>
    </article>
  )
}

// TransactionRow.module.css
.row {
  display: grid;
  grid-template-columns: 120px 1fr 120px;
  gap: var(--spacing-md);
  padding: var(--spacing-sm);
  border-bottom: 1px solid var(--color-border);
}

.amount {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.amount[data-positive] {
  color: var(--color-positive);
}
```

#### State Management: **Remix-Native, No Client Cache**

```
┌─────────────────────────────────────────────┐
│ Layer 1: URL State (Search Params)          │
│ - Page, filters, sort, selected transaction │
│ - Shareable via URL                         │
│ - Browser history works automatically       │
└─────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────┐
│ Layer 2: Server State (Remix + Datalevin)   │
│ - Loaders fetch on server (SSR)             │
│ - Actions mutate on server                  │
│ - Automatic revalidation after actions      │
│ - HTTP caching via Cache-Control headers    │
│ - Optimistic UI via useFetcher.formData     │
└─────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────┐
│ Layer 3: Ephemeral UI State (React)         │
│ - Dropdown open/closed                      │
│ - Form validation errors                    │
│ - Loading states (fetcher.state)            │
│ - Component-local state only                │
└─────────────────────────────────────────────┘
```

**Key Principles**:
- **Server is source of truth** - Datalevin owns all persistent state
- **URL is UI state** - Filters, sort, page number in search params
- **Remix handles caching** - HTTP caching via standard Cache-Control headers
- **Optimistic updates** - via useFetcher.formData (read form values during submission)
- **No client-side cache library** - Remix revalidation + browser caching is sufficient
- **Progressive enhancement** - Forms work without JavaScript

**Why no TanStack Query?**
- Remix's automatic revalidation eliminates need for manual cache invalidation
- useFetcher provides optimistic UI without external library
- HTTP caching (Cache-Control) handles performance
- Simpler mental model, fewer dependencies
- See "Alternatives Considered" section for details

#### Testing: **Vitest + Testing Library + Playwright**

**Test Pyramid**:
```
    ┌───────────┐
    │    E2E    │  Playwright (critical paths)
    └───────────┘
        ↑
    ┌───────────────┐
    │  Integration  │  Testing Library (user flows)
    └───────────────┘
        ↑
    ┌─────────────────────┐
    │   Unit (Pure Fns)   │  Vitest (business logic)
    └─────────────────────┘
```

### Architecture: Remix + Clojure

**Design Philosophy**: Simple, server-driven architecture following Remix patterns

```
┌──────────────────────────────────────────────────────────────┐
│                 Browser (Remix React App)                    │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  React Components                                      │  │
│  │  - Semantic HTML                                       │  │
│  │  - CSS Modules for styling                             │  │
│  │  - Minimal local state                                 │  │
│  └────────────────────┬───────────────────────────────────┘  │
│                       │                                      │
│  ┌────────────────────┴───────────────────────────────────┐  │
│  │  Remix Routing Layer                                   │  │
│  │  - File-based routes                                   │  │
│  │  - Loaders (server-side data fetching)                 │  │
│  │  - Actions (server-side mutations)                     │  │
│  │  - useFetcher for optimistic updates                   │  │
│  │  - HTTP caching via Cache-Control                      │  │
│  └────────────────────┬───────────────────────────────────┘  │
│                       │                                      │
└───────────────────────┼──────────────────────────────────────┘
                        │
                        │ HTTP (Remix loaders/actions)
                        ↓
┌──────────────────────────────────────────────────────────────┐
│                  Clojure Backend (Ring/HTTP Kit)             │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  REST API                                              │  │
│  │  - GET  /api/transactions?page=1&category=groceries    │  │
│  │  - GET  /api/categories                                │  │
│  │  - PUT  /api/transactions/:id/category                 │  │
│  │  - POST /api/categories                                │  │
│  │  - POST /api/import (SimpleFIN, CSV)                   │  │
│  └────────────────────┬───────────────────────────────────┘  │
│                       │                                      │
│  ┌────────────────────┴───────────────────────────────────┐  │
│  │  Business Logic                                        │  │
│  │  - Pure functions for validation                       │  │
│  │  - Transaction rules                                   │  │
│  │  - Category management                                 │  │
│  │  - Import processors (SimpleFIN, CSV)                  │  │
│  └────────────────────┬───────────────────────────────────┘  │
│                       │                                      │
│                       ↓                                      │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  Datalevin Interface                                   │  │
│  │  - Database queries (Datalog)                          │  │
│  │  - Transactions                                        │  │
│  │  - Schema enforcement                                  │  │
│  └────────────────────┬───────────────────────────────────┘  │
│                       │                                      │
└───────────────────────┼──────────────────────────────────────┘
                        │
                        ↓
┌──────────────────────────────────────────────────────────────┐
│                     Datalevin Database                       │
│                                                              │
│  - Source of truth for all data                              │
│  - Datalog queries for complex reads                         │
│  - Schema validation                                         │
│  - Temporal queries (as-of, history)                         │
│  - ACID transactions                                         │
└──────────────────────────────────────────────────────────────┘
```

### Data Flow Examples

#### 1. Initial Page Load (SSR)

```typescript
// app/routes/transactions._index.tsx

// Server-side loader (runs on server)
export async function loader({ request }: LoaderFunctionArgs) {
  const url = new URL(request.url)
  const page = url.searchParams.get('page') || '1'
  const category = url.searchParams.get('category') || ''

  // Fetch from Clojure API with HTTP caching
  const response = await fetch(
    `http://localhost:8080/api/transactions?page=${page}&category=${category}`,
    {
      headers: {
        'Cache-Control': 'max-age=60, stale-while-revalidate=300'
      }
    }
  )
  const data = await response.json()

  return json({ transactions: data.data, page, category }, {
    headers: {
      'Cache-Control': 'max-age=60, stale-while-revalidate=300'
    }
  })
}

// Client component
export default function TransactionsPage() {
  const { transactions, page, category } = useLoaderData<typeof loader>()

  // Server-rendered on first load, hydrated on client
  // No client-side data fetching needed
  return <TransactionList transactions={transactions} />
}
```

**Flow**:
1. User navigates to `/transactions?page=2&category=groceries`
2. Remix calls loader on server
3. Loader fetches from Clojure API
4. Server renders HTML with data
5. Browser receives pre-rendered page
6. React hydrates (makes interactive)

#### 2. User Edits Category (Online, with JS)

```typescript
// app/routes/transactions.$id.category.tsx

// Server-side action (mutation)
export async function action({ request, params }: ActionFunctionArgs) {
  const formData = await request.formData()
  const categoryId = formData.get('categoryId')

  // Call Clojure API
  await fetch(`http://localhost:8080/api/transactions/${params.id}/category`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ categoryId })
  })

  // Remix automatically revalidates loaders after actions
  return json({ success: true })
}

// Component with progressive enhancement and optimistic UI
export default function CategoryEditor({ transaction, categories }) {
  const fetcher = useFetcher()

  // Optimistic value - show form value during submission
  const displayCategory = fetcher.formData?.get('categoryId') ?? transaction.categoryId
  const isUpdating = fetcher.state === 'submitting' || fetcher.state === 'loading'

  return (
    <fetcher.Form method="post" action={`/transactions/${transaction.id}/category`}>
      <select
        name="categoryId"
        value={displayCategory}
        onChange={e => fetcher.submit(e.currentTarget.form)}
        disabled={isUpdating}
      >
        <option value="">Uncategorized</option>
        {categories.map(c => (
          <option key={c.id} value={c.id}>{c.name}</option>
        ))}
      </select>

      {/* Show loading state during submission */}
      {isUpdating && <span className="loading">Saving...</span>}
    </fetcher.Form>
  )
}
```

**Flow (with JavaScript)**:
1. User selects category from dropdown
2. `onChange` triggers `fetcher.submit()`
3. Fetcher intercepts form submission
4. Sends POST to action (no page reload)
5. Action calls Clojure API
6. Clojure updates Datalevin
7. Action completes
8. Remix revalidates all loaders on page
9. UI updates with fresh data

**Flow (without JavaScript)**:
1. User selects category, clicks Save button
2. Form POSTs to server
3. Action runs, updates Clojure
4. Redirect back to transaction list
5. Full page reload

#### 3. Clojure API Handles Request

```clojure
;; src/finance_aggregator/server.clj

(defn update-transaction-category-handler [request]
  (let [tx-id (parse-long (get-in request [:params :id]))
        body (slurp (:body request))
        {:keys [categoryId]} (json/read-json body :key-fn keyword)

        ;; Update in Datalevin
        result (transactions/update-category! db/conn tx-id categoryId)]

    (json-response {:success true :data result})))

;; PUT /api/transactions/:id/category
```

```clojure
;; src/finance_aggregator/db/transactions.clj

(defn update-category! [conn tx-id category-id]
  (let [tx-data (if category-id
                  [{:db/id tx-id
                    :transaction/category category-id}]
                  [{:db/id tx-id
                    :transaction/category nil}])]
    (d/transact! conn tx-data)
    (d/pull (d/db conn) '[* {:transaction/category [*]}] tx-id)))
```

### Progressive Enhancement in Practice

Remix enables true progressive enhancement where the same code works with and without JavaScript:

#### Without JavaScript: Forms POST to Server

```tsx
export default function TransactionRow({ transaction, categories }) {
  return (
    <tr>
      <td><time>{transaction.date}</time></td>
      <td>{transaction.payee}</td>
      <td><data value={transaction.amount}>${transaction.amount}</data></td>
      <td>
        {/* Plain HTML form - works without JS */}
        <form method="post" action={`/transactions/${transaction.id}/category`}>
          <select name="categoryId" defaultValue={transaction.categoryId}>
            <option value="">Uncategorized</option>
            {categories.map(c => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
          <button type="submit">Save</button>
        </form>
      </td>
    </tr>
  )
}
```

**Behavior**: Form submission causes full page reload. Category updates, user sees updated list.

#### With JavaScript: Enhanced Experience

```tsx
export default function TransactionRow({ transaction, categories }) {
  const fetcher = useFetcher()

  // Optimistic UI - read from form data during submission
  const displayCategory = fetcher.formData?.get('categoryId') ?? transaction.categoryId
  const isUpdating = fetcher.state === 'submitting' || fetcher.state === 'loading'

  return (
    <tr className={isUpdating ? 'updating' : ''}>
      <td><time>{transaction.date}</time></td>
      <td>{transaction.payee}</td>
      <td><data value={transaction.amount}>${transaction.amount}</data></td>
      <td>
        {/* Enhanced with useFetcher for optimistic UI */}
        <fetcher.Form method="post" action={`/transactions/${transaction.id}/category`}>
          <select
            name="categoryId"
            value={displayCategory} {/* Shows optimistic value */}
            onChange={e => fetcher.submit(e.currentTarget.form)}
            disabled={isUpdating}
          >
            <option value="">Uncategorized</option>
            {categories.map(c => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>

          {/* Show inline loading state */}
          {isUpdating && <span className="loading-indicator">Saving...</span>}
        </fetcher.Form>
      </td>
    </tr>
  )
}
```

**Behavior**: No page reload. Optimistic update shows new category immediately. Request sent in background. UI shows "Saving..." state. After action completes, Remix revalidates loaders automatically and UI reflects server state.

### File Structure

```
finance-aggregator/
├── frontend/                        # Remix app
│   ├── app/
│   │   ├── routes/
│   │   │   ├── _index.tsx          # Dashboard
│   │   │   ├── transactions._index.tsx
│   │   │   ├── transactions.$id.category.tsx  # Category action
│   │   │   └── categories._index.tsx
│   │   ├── components/
│   │   │   ├── TransactionRow.tsx
│   │   │   ├── TransactionRow.module.css
│   │   │   ├── CategoryDropdown.tsx
│   │   │   └── CategoryDropdown.module.css
│   │   ├── lib/
│   │   │   ├── api.ts              # Clojure API client
│   │   │   ├── currency.ts         # Pure functions
│   │   │   ├── currency.test.ts
│   │   │   └── date-format.ts
│   │   ├── styles/
│   │   │   ├── base/
│   │   │   │   ├── reset.css
│   │   │   │   ├── typography.css
│   │   │   │   └── variables.css   # CSS custom properties
│   │   │   ├── components/         # Component-specific styles
│   │   │   └── layouts/            # Layout styles
│   │   ├── entry.client.tsx
│   │   ├── entry.server.tsx
│   │   └── root.tsx
│   ├── tests/
│   │   ├── unit/
│   │   ├── integration/
│   │   └── e2e/
│   ├── vitest.config.ts
│   ├── playwright.config.ts
│   └── package.json
├── backend/                         # Clojure backend (existing)
│   ├── deps.edn
│   ├── README.md
│   ├── resources/
│   │   └── config.edn.example
│   ├── scripts/
│   │   └── start_server.clj
│   ├── src/
│   │   └── finance_aggregator/
│   │       ├── db.clj
│   │       ├── data/
│   │       │   └── schema.clj
│   │       ├── db/
│   │       │   ├── categories.clj
│   │       │   └── transactions.clj
│   │       └── simplefin/
│   │           ├── client.clj
│   │           └── data.clj
│   ├── test/
│   │   └── finance_aggregator/
│   │       ├── db_test.clj
│   │       ├── db/
│   │       │   ├── categories_test.clj
│   │       │   └── transactions_test.clj
│   │       └── simplefin/
│   │           ├── client_test.clj
│   │           ├── data_test.clj
│   │           └── data_persistence_test.clj
│   └── tests.edn
└── doc/
    ├── adr/
    └── implementation/
        └── adr-002-remix-frontend-tasks.md
```

## Implementation

Detailed implementation tasks are documented separately in [`doc/implementation/adr-002-remix-frontend-tasks.md`](../implementation/adr-002-remix-frontend-tasks.md).

**Key Tasks** (non-sequential):
- Project setup with Remix, Vitest, Playwright
- Pure business logic with TDD (currency, dates, validation)
- CSS architecture (semantic, component-scoped)
- Type-safe API client with Zod
- Transaction list with SSR
- Category editing with progressive enhancement
- URL-based state (filters, pagination)
- TanStack Table integration (sorting)

**Future Enhancements** (post-MVP):
- Offline mutation queue with Y.js
- Real-time collaboration
- Multi-device sync

See [adr-002-remix-frontend-tasks.md](../implementation/adr-002-remix-frontend-tasks.md) for details.

---

## Consequences

### Positive

1. **Simple, proven architecture**
   - Remix patterns are well-documented
   - Server-driven state reduces complexity
   - No distributed systems to debug (for MVP)

2. **Semantic HTML + CSS**
   - Complete separation of concerns
   - Easy to restyle with CSS alone
   - Accessible by default

3. **Test-driven throughout**
   - Pure functions with 100% coverage
   - High confidence in refactoring
   - Regression prevention

4. **Clojure backend maintained**
   - Datalevin remains source of truth
   - Existing API works as-is
   - No rewrite needed

5. **Progressive enhancement**
   - Works without JavaScript
   - Enhanced when available
   - Graceful degradation

6. **Clear path to advanced features**
   - Y.js for offline/realtime when needed
   - Architecture doesn't prevent future enhancements
   - Add complexity only when required

### Negative

1. **Server round-trips for mutations**
   - Every edit goes to server
   - Network latency affects perceived performance
   - **Mitigation**: useFetcher optimistic UI (read formData during submission)
   - **Mitigation**: HTTP caching via Cache-Control headers
   - **Acceptable**: Primary use case is online, optimistic updates make this feel fast

2. **No offline editing in MVP**
   - Requires network connection to make edits
   - Users will see errors if they try to edit while offline
   - **Mitigation**: Clear offline indicators, queue for future enhancement
   - **Acceptable**: Primary use case is "single user online", validate before building

3. **Potential for over-fetching**
   - SSR fetches all data for route upfront
   - May load data that user doesn't need immediately
   - **Mitigation**: Pagination, smart loader design, defer non-critical data
   - **Acceptable**: Fast initial paint is more important than minimal bytes

4. **Opinionated routing**
   - Remix patterns are opinionated (file-based routes, loader/action convention)
   - Less flexibility than TanStack Router
   - **Mitigation**: Document patterns, enforce consistency
   - **Acceptable**: Opinions lead to consistency, reduce bikeshedding

### Risks & Mitigations

**Risk**: Users expect offline capability from day one
- **Mitigation**: Architecture supports adding Y.js queue without full rewrite

**Risk**: Real-time collaboration becomes hard requirement early
- **Mitigation**: Y.js architecture designed, clear path to add later

**Risk**: Server latency feels too slow
- **Mitigation**: useFetcher optimistic UI (formData provides instant feedback)
- **Mitigation**: HTTP caching via Cache-Control headers
- **Mitigation**: Optimize Clojure backend if needed (we control it)
- **Mitigation**: Measure performance early with realistic data

**Risk**: Remix learning curve for team
- **Mitigation**: Excellent docs, strong community, many examples

**Risk**: SimpleFIN import conflicts with manual edits
- **Mitigation**: Transaction external-id for deduplication, import as atomic operation
- **Mitigation**: Show clear import progress, lock UI during import

## Future Enhancements

### Offline Mutation Queue with Y.js

**When to Build**: After MVP launch, if user feedback indicates offline editing is important

**Validation Criteria**:
- Users report being unable to work during commutes/flights
- Analytics show >5% of attempted edits fail due to offline
- User interviews validate offline as top priority

**Approach**:
- Y.js as offline mutation queue only (NOT source of truth)
- Persists to IndexedDB
- Drains queue when online
- Minimal changes to existing Remix architecture
- Server (Datalevin) remains source of truth

**Implementation**:
```typescript
// Wrap Remix action calls with offline queue
async function submitTransaction(data) {
  if (navigator.onLine) {
    // Normal Remix flow
    return await fetcher.submit(data)
  } else {
    // Queue in Y.js + IndexedDB
    offlineQueue.add({
      type: 'create-transaction',
      data,
      timestamp: Date.now()
    })
    // Show optimistic UI
    return { status: 'queued' }
  }
}

// Drain queue on reconnection
window.addEventListener('online', async () => {
  const pending = offlineQueue.getAll()
  for (const mutation of pending) {
    await fetch('/api/transactions', {
      method: 'POST',
      body: JSON.stringify(mutation.data)
    })
    offlineQueue.remove(mutation.id)
  }
})
```

**Architecture Changes Required**: Minimal
- Add Y.js + IndexedDB dependencies
- Wrap fetcher.submit calls with offline detection
- Add queue UI (show pending mutations)
- Handle conflicts on sync (last-write-wins for MVP)

### Real-Time Collaboration

**When to Build**: After households become common use case (multi-user editing validated)

**Validation Criteria**:
- >20% of users are households with multiple editors
- Users report conflicts from eventual consistency
- Feature requests for real-time awareness

**Approach**:
- Node.js Y.js WebSocket server
- Y.js → Datalevin bridge
- Presence awareness ("User X is editing transaction Y")
- Datalevin remains source of truth
- Y.js only for real-time sync layer

**Implementation**:
```typescript
// Add WebSocket connection for real-time updates
const wsProvider = new WebsocketProvider(
  'wss://finance.example.com/collab',
  'household-123',
  ydoc
)

// Subscribe to remote changes
ydoc.getArray('transactions').observe(event => {
  event.changes.added.forEach(item => {
    // Another user added transaction, update UI
    invalidateTransactionCache()
  })
})

// Show awareness
wsProvider.awareness.on('change', () => {
  const states = wsProvider.awareness.getStates()
  // Show "Alice is editing transaction #123"
})
```

**Architecture Changes Required**: Moderate
- Add WebSocket server (Node.js + Y.js)
- Y.js → Datalevin sync bridge
- Handle real-time updates in React UI
- Add presence indicators

### Multi-Device Sync

**When to Build**: If users report issues with using multiple devices

**Approach**:
- Reuse Phase 2 real-time infrastructure
- Per-user Y.Doc instead of shared household doc
- Background sync when user switches devices

**Note**: This may be unnecessary if offline queue (Phase 1) + real-time collab (Phase 2) already handle this use case.

## Open Questions

1. **Authentication**: How to handle user auth in Remix loaders?
   - Decision: Use Hanko for user management when authentication becomes necessary

2. **Deployment**: Vercel, Fly.io, or self-hosted?
   - Proposal: Start with Fly.io (simple, supports Clojure + Node if needed)

3. **Monorepo vs separate repos**: Frontend and backend together?
   - Proposal: Monorepo for easier development, separate deployment

4. **CSV export**: Client-side or server-side generation?
   - Proposal: Server-side to leverage Datalog queries

These will be refined during implementation.

## Alternatives Considered

### TanStack Router

**Why not chosen:**
- Requires DIY SSR or giving up SSR entirely (violates requirement #3)
- Client-first architecture conflicts with server-as-source-of-truth (requirement #4)
- No progressive enhancement support (violates requirement #3)
- Would add complexity for marginal TypeScript inference benefits
- Works better for pure SPAs or offline-first architectures (not our paradigm)

**When it would be better:**
- Pure client-side SPA with no SSR
- Offline-first architecture with local state as source of truth
- Complex client-side routing with extreme type safety needs

### Next.js

**Why not chosen:**
- More opinionated than needed (Vercel ecosystem lock-in)
- Server Components add complexity we don't need
- Remix loader/action patterns are simpler for our use case
- Want to keep frontend and Clojure backend clearly separated

**When it would be better:**
- Need image optimization and other Vercel features
- Want Server Components for partial hydration
- Team already expert in Next.js

### Pure Client-Side SPA (Vite + React Router)

**Why not chosen:**
- No SSR (violates requirement #3)
- No progressive enhancement
- Worse initial load performance
- Would need to DIY data loading patterns

**When it would be better:**
- Internal tools where SSR doesn't matter
- Extremely dynamic UIs that can't benefit from SSR
- Want absolute minimal server infrastructure

### TanStack Query (as addition to Remix)

**Why not chosen:**
- Remix already provides what TanStack Query does:
  - Automatic revalidation after actions (no manual cache invalidation)
  - Optimistic UI via useFetcher.formData
  - HTTP caching via Cache-Control headers
- Would create redundant caching layers (Remix revalidation + TanStack Query cache)
- Adds complexity, mental overhead, and ~50kb bundle size
- Goes against Remix philosophy of embracing the network
- Our use case (simple CRUD) doesn't need it

**When it would be better:**
- Extremely slow backend APIs where aggressive client caching is critical
- Complex polling/real-time requirements (but Y.js is better for this)
- Third-party APIs you don't control (we own the Clojure backend)
- SPA architecture without Remix

**Remix-native approach is simpler:**
```tsx
// Optimistic UI without TanStack Query
const fetcher = useFetcher()
const displayValue = fetcher.formData?.get('field') ?? serverValue

// Automatic revalidation after actions
// HTTP caching via Cache-Control headers
// No manual cache management needed
```

## References

- [Remix Documentation](https://remix.run/docs)
- [React Router v7 (Remix merge)](https://remix.run/blog/merging-remix-and-react-router)
- [Remix Optimistic UI](https://remix.run/docs/en/main/guides/optimistic-ui) (useFetcher patterns)
- [TanStack Table](https://tanstack.com/table/latest)
- [TanStack Router](https://tanstack.com/router/latest) (alternative considered, not chosen)
- [Progressive Enhancement](https://developer.mozilla.org/en-US/docs/Glossary/Progressive_Enhancement)
- [HTTP Caching](https://developer.mozilla.org/en-US/docs/Web/HTTP/Caching) (Cache-Control)
- [Y.js Documentation](https://docs.yjs.dev/) (future enhancement)
- [Radix UI](https://www.radix-ui.com/)
- [Vitest](https://vitest.dev/)
- [Playwright](https://playwright.dev/)
- [Local-First Software](https://www.inkandswitch.com/local-first/) (future architecture consideration)

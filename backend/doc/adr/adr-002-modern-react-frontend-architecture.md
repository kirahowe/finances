# ADR-002: Modern React Frontend Architecture

**Date:** 2025-11-18
**Status:** Proposed
**Supersedes:** ADR-001 (Scittle/Reagent Prototype)

## Context

After validating the backend API and data model with a Scittle prototype, we're building a production-grade React frontend. The requirements are:

### Core Requirements
1. **Offline-first editing** - Work without network, sync when available
2. **Multi-user support** - Eventually support concurrent editing (e.g., household finances)
3. **Server-side rendering** - Fast initial paint, graceful degradation
4. **Minimal client state** - Server as source of truth (Phoenix LiveView philosophy)
5. **Test-driven** - Pure functions, minimal state, comprehensive tests
6. **Semantic markup + CSS** - Complete separation of structure and presentation

### Use Cases
- **Primary**: Single user online
- **Future:** Single user, multiple devices, offline editing
- **Future**: Multiple users (e.g. households) editing concurrently
- **Future**: Real-time collaboration (not MVP)

### Constraints
- **Backend**: Clojure + Datalevin (source of truth)
- **External sync**: Batch ingestion (SimpleFIN, CSV uploads, etc.)
- **No Tailwind**: Semantic HTML + proper CSS organization
- **No BEM**: Use component library + clean CSS

## Decision

### Technology Stack

#### Core Framework: **Remix**

**Why Remix?**
- SSR by default, progressive enhancement built-in
- File-based routing with data loading (loaders/actions)
- Forms work without JavaScript
- Excellent TypeScript support
- Built on React Router 6.4+ (stable, proven)

#### CRDT: **Y.js (Yjs)**

**Why Y.js over Automerge?**
- More mature, battle-tested (Figma, Notion use it)
- Excellent performance with large documents
- Rich ecosystem (y-websocket, y-indexeddb, y-protocols)
- Better TypeScript support
- Network-agnostic (WebSocket, WebRTC, HTTP)

**Y.js Architecture**:
```typescript
import * as Y from 'yjs'
import { IndexeddbPersistence } from 'y-indexeddb'
import { WebsocketProvider } from 'y-websocket'

// Shared Y.Doc across all clients
const ydoc = new Y.Doc()

// Sync to IndexedDB (offline persistence)
const indexeddbProvider = new IndexeddbPersistence('finance-db', ydoc)

// Sync to server (when online)
const websocketProvider = new WebsocketProvider(
  'ws://localhost:1234',
  'finance-room',
  ydoc
)

// Our data structures
const transactions = ydoc.getArray('transactions')
const categories = ydoc.getMap('categories')
```

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

#### State Management: **Remix-First, Minimal Client State**

```
┌─────────────────────────────────────────────┐
│ Layer 1: URL State (React Router)           │
│ - Page, filters, sort, selected transaction │
│ - Search params for shareable UI state      │
│ - Browser history works automatically       │
└─────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────┐
│ Layer 2: Server State (Datalevin)           │
│ - Clojure backend owns all data             │
│ - Remix loaders fetch on server-side        │
│ - Remix actions mutate on server-side       │
│ - Automatic revalidation after mutations    │
└─────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────┐
│ Layer 3: Client Cache (TanStack Query)      │
│ - Caches server responses                   │
│ - Optimistic updates for UX                 │
│ - Background refetching                     │
│ - Stale-while-revalidate                    │
└─────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────┐
│ Layer 4: Ephemeral UI State (React)         │
│ - Dropdown open/closed                      │
│ - Form validation errors                    │
│ - Loading spinners                          │
│ - Local component state only                │
└─────────────────────────────────────────────┘
```

**Key Principles**:
- **Server is source of truth** - Datalevin owns all persistent state
- **URL is UI state** - Filters, sort, page number in search params
- **Optimistic updates** - via useFetcher and TanStack Query for responsive UX
- **No client-side replicas** - Don't duplicate server state on client
- **Progressive enhancement** - Forms work without JavaScript

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
│  └────────────────────┬───────────────────────────────────┘  │
│                       │                                      │
│  ┌────────────────────┴───────────────────────────────────┐  │
│  │  TanStack Query (optional enhancement)                 │  │
│  │  - Client-side cache                                   │  │
│  │  - Optimistic updates                                  │  │
│  │  - Background refetching                               │  │
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

  // Fetch from Clojure API
  const response = await fetch(
    `http://localhost:8080/api/transactions?page=${page}&category=${category}`
  )
  const data = await response.json()

  return json({ transactions: data.data, page, category })
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

// Component with progressive enhancement
export default function CategoryEditor({ transaction, categories }) {
  const fetcher = useFetcher()

  return (
    <fetcher.Form method="post" action={`/transactions/${transaction.id}/category`}>
      <select
        name="categoryId"
        defaultValue={transaction.categoryId}
        onChange={e => fetcher.submit(e.currentTarget.form)}
      >
        <option value="">Uncategorized</option>
        {categories.map(c => (
          <option key={c.id} value={c.id}>{c.name}</option>
        ))}
      </select>

      {/* Show loading state during submission */}
      {fetcher.state === 'submitting' && <Spinner />}
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

  return (
    <tr>
      <td><time>{transaction.date}</time></td>
      <td>{transaction.payee}</td>
      <td><data value={transaction.amount}>${transaction.amount}</data></td>
      <td>
        {/* Same HTML structure, enhanced with useFetcher */}
        <fetcher.Form method="post" action={`/transactions/${transaction.id}/category`}>
          <select
            name="categoryId"
            defaultValue={transaction.categoryId}
            onChange={e => fetcher.submit(e.currentTarget.form)} {/* Auto-submit on change */}
          >
            <option value="">Uncategorized</option>
            {categories.map(c => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>

          {/* Show inline loading state */}
          {fetcher.state === 'submitting' && <span>Saving...</span>}
        </fetcher.Form>
      </td>
    </tr>
  )
}
```

**Behavior**: No page reload. Request sent in background. UI shows "Saving..." then updates. Other loaders revalidate automatically.

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
├── src/                             # Clojure backend (existing)
│   └── finance_aggregator/
│       ├── server.clj
│       ├── db/
│       │   ├── categories.clj
│       │   └── transactions.clj
│       └── data/
│           └── schema.clj
├── test/                            # Clojure tests
├── resources/
│   └── config.edn
└── doc/
    └── adr/
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
   - Optimistic updates help but still have latency
   - **Mitigation**: TanStack Query caching, useFetcher optimistic UI

2. **No offline editing (MVP)**
   - Requires network connection
   - **Mitigation**: Plan for Y.js enhancement later, architecture supports it

3. **Potential for over-fetching**
   - SSR fetches all data for route
   - **Mitigation**: Pagination, smart loaders, TanStack Query

### Risks & Mitigations

**Risk**: Users expect offline capability from day one
- **Mitigation**: Validate with users, add offline as enhancement if needed

**Risk**: Real-time becomes hard requirement**
- **Mitigation**: Y.js architecture designed, can add when needed

**Risk**: Remix learning curve for team
- **Mitigation**: Excellent docs, similar to Next.js, strong community

**Risk**: SimpleFIN import conflicts with manual edits
- **Mitigation**: Transaction external-id for deduplication, import as separate operation

## Future Enhancements

### Offline Editing with Y.js

**When**: User feedback indicates offline editing is needed

**Approach**:
- Y.js as offline mutation queue
- Persists to IndexedDB
- Drains queue when online
- Minimal changes to existing architecture
- Remix remains primary path

**Implementation**:
- Add Y.js client-side only
- Queue mutations when offline
- `navigator.onLine` detection
- Sync via existing API endpoints

### Real-Time Collaboration

**When**: Household sharing becomes priority

**Approach**:
- Node.js Y.js WebSocket server
- Y.js → Datalevin bridge
- Awareness for "User X is editing"
- Datalevin remains source of truth

**Implementation**:
- Add `yjs-server` directory
- WebSocket connection
- Observe Y.js changes, translate to Datalevin transactions
- UI shows collaboration state

### Multi-Device Sync

**When**: Users report using multiple devices

**Approach**:
- Same Y.js infrastructure as collaboration
- Per-user Y.Doc instead of shared
- Background sync when devices online

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

## References

- [Remix Documentation](https://remix.run/docs)
- [TanStack Table](https://tanstack.com/table/latest)
- [TanStack Query](https://tanstack.com/query/latest) (optional enhancement)
- [Progressive Enhancement](https://developer.mozilla.org/en-US/docs/Glossary/Progressive_Enhancement)
- [Y.js Documentation](https://docs.yjs.dev/) (future)
- [Radix UI](https://www.radix-ui.com/)
- [Vitest](https://vitest.dev/)
- [Playwright](https://playwright.dev/)

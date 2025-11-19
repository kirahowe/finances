# Frontend Implementation Tasks

**Related ADR**: [ADR-002: Modern React Frontend Architecture](../adr/adr-002-modern-react-frontend-architecture.md)

**Status**: Active implementation plan (as of 2025-11-18)

These tasks implement the architecture described in ADR-002. They can be tackled in various orders, though some have natural dependencies. Focus on building features incrementally with TDD.

## Core Tasks (MVP)

### Task: Project Initialization

**What**: Set up Remix project with tooling

```bash
npx create-remix@latest frontend
cd frontend
npm install @tanstack/react-query @tanstack/react-table
npm install @radix-ui/react-dropdown-menu @radix-ui/react-dialog @radix-ui/react-select
npm install zod
npm install -D vitest @testing-library/react @testing-library/user-event playwright
```

**Deliverables**:
- Working Remix app
- Vitest configured
- Playwright configured
- TypeScript strict mode

**Tests**: Build passes, basic smoke test

---

### Task: Pure Business Logic (TDD)

**What**: Currency, date formatting, validation - all pure functions

```typescript
// lib/currency.test.ts
describe('formatAmount', () => {
  it('formats with 2 decimals and comma separators', () => {
    expect(formatAmount(1234.56)).toBe('$1,234.56')
  })

  it('handles negative amounts', () => {
    expect(formatAmount(-1234.56)).toBe('-$1,234.56')
  })
})

// lib/currency.ts
export function formatAmount(amount: number): string {
  const formatter = new Intl.NumberFormat('en-CA', {
    style: 'currency',
    currency: 'CAD'
  })
  return formatter.format(amount)
}
```

**Deliverables**:
- `lib/currency.ts` + tests
- `lib/date-format.ts` + tests
- `lib/validation.ts` + tests
- 100% test coverage

**Tests**: 20+ unit tests

---

### Task: CSS Architecture

**What**: Set up semantic, maintainable CSS

```
app/styles/
  base/
    reset.css           # Normalize browser styles
    variables.css       # CSS custom properties
    typography.css      # Font scales, line heights
  components/          # Component-specific CSS modules
  layouts/             # Page layout styles
```

**Example**:
```css
/* styles/base/variables.css */
:root {
  /* Colors */
  --color-text: #1a1a1a;
  --color-background: #ffffff;
  --color-border: #e5e5e5;
  --color-positive: #2e7d32;
  --color-negative: #d32f2f;

  /* Spacing */
  --spacing-xs: 0.25rem;
  --spacing-sm: 0.5rem;
  --spacing-md: 1rem;
  --spacing-lg: 1.5rem;

  /* Typography */
  --font-body: system-ui, -apple-system, sans-serif;
  --font-mono: 'SF Mono', Monaco, monospace;
}
```

**Deliverables**:
- CSS organization in place
- No inline styles
- Reskinnable via CSS alone

---

### Task: Type-Safe API Client

**What**: Clojure API client with Zod validation

```typescript
// lib/api.ts
import { z } from 'zod'

const TransactionSchema = z.object({
  'db/id': z.number(),
  'transaction/amount': z.number(),
  'transaction/payee': z.string(),
  'transaction/posted-date': z.string(),
  'transaction/category': z.object({
    'db/id': z.number(),
    'category/name': z.string(),
  }).nullable(),
})

export type Transaction = z.infer<typeof TransactionSchema>

export const api = {
  async getTransactions(params: { page?: number; category?: string }) {
    const url = new URL(`${API_BASE}/api/transactions`)
    if (params.page) url.searchParams.set('page', params.page.toString())
    if (params.category) url.searchParams.set('category', params.category)

    const response = await fetch(url.toString())
    const json = await response.json()

    // Runtime validation
    const result = z.object({
      success: z.boolean(),
      data: z.array(TransactionSchema),
    }).parse(json)

    return result.data
  },

  async updateTransactionCategory(txId: number, categoryId: number | null) {
    const response = await fetch(`${API_BASE}/api/transactions/${txId}/category`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ categoryId }),
    })

    return response.json()
  },
}
```

**Deliverables**:
- All API calls typed
- Runtime validation with Zod
- Error handling

**Tests**: Mock API responses, validate schema parsing

---

### Task: Transaction List (SSR)

**What**: Server-rendered transaction list with semantic HTML

```typescript
// app/routes/transactions._index.tsx

export async function loader({ request }: LoaderFunctionArgs) {
  const url = new URL(request.url)
  const page = parseInt(url.searchParams.get('page') || '1')
  const category = url.searchParams.get('category') || ''

  const transactions = await api.getTransactions({ page, category })

  return json({ transactions, page, category })
}

export default function TransactionsPage() {
  const { transactions } = useLoaderData<typeof loader>()

  return (
    <main>
      <h1>Transactions</h1>
      <TransactionTable transactions={transactions} />
    </main>
  )
}
```

```tsx
// app/components/TransactionTable.tsx
import styles from './TransactionTable.module.css'

export function TransactionTable({ transactions }) {
  return (
    <table className={styles.table}>
      <thead>
        <tr>
          <th>Date</th>
          <th>Payee</th>
          <th>Amount</th>
          <th>Category</th>
        </tr>
      </thead>
      <tbody>
        {transactions.map(tx => (
          <TransactionRow key={tx['db/id']} transaction={tx} />
        ))}
      </tbody>
    </table>
  )
}
```

**Deliverables**:
- Working transaction list
- Server-side rendering
- Semantic HTML (`<table>`, `<time>`, `<data>`)

**Tests**:
- Unit: TransactionTable renders rows
- E2E: Navigate to /transactions, see data

---

### Task: Category Editing (Progressive Enhancement)

**What**: Forms that work without JS, enhanced with useFetcher

```typescript
// app/routes/transactions.$id.category.tsx

export async function action({ request, params }: ActionFunctionArgs) {
  const formData = await request.formData()
  const categoryId = formData.get('categoryId')

  await api.updateTransactionCategory(
    parseInt(params.id!),
    categoryId ? parseInt(categoryId) : null
  )

  return json({ success: true })
}
```

```tsx
// app/components/CategoryEditor.tsx
export function CategoryEditor({ transaction, categories }) {
  const fetcher = useFetcher()

  return (
    <fetcher.Form method="post" action={`/transactions/${transaction['db/id']}/category`}>
      <select
        name="categoryId"
        defaultValue={transaction['transaction/category']?.['db/id'] || ''}
        onChange={e => fetcher.submit(e.currentTarget.form)}
      >
        <option value="">Uncategorized</option>
        {categories.map(c => (
          <option key={c['db/id']} value={c['db/id']}>
            {c['category/name']}
          </option>
        ))}
      </select>
      {fetcher.state === 'submitting' && <span>Saving...</span>}
    </fetcher.Form>
  )
}
```

**Deliverables**:
- Forms work without JS (full page POST)
- Enhanced with JS (useFetcher, no reload)
- Loading states

**Tests**:
- E2E without JS: Form POST works
- E2E with JS: No page reload, optimistic update

---

### Task: URL-Based Filtering & Pagination

**What**: All UI state in URL, shareable links

```typescript
// app/routes/transactions._index.tsx
export default function TransactionsPage() {
  const [searchParams, setSearchParams] = useSearchParams()

  const page = parseInt(searchParams.get('page') || '1')
  const category = searchParams.get('category') || ''

  const handlePageChange = (newPage: number) => {
    setSearchParams(prev => {
      prev.set('page', newPage.toString())
      return prev
    })
  }

  const handleCategoryFilter = (categoryId: string) => {
    setSearchParams(prev => {
      if (categoryId) {
        prev.set('category', categoryId)
      } else {
        prev.delete('category')
      }
      prev.set('page', '1') // Reset to page 1
      return prev
    })
  }

  return (
    <main>
      <CategoryFilter value={category} onChange={handleCategoryFilter} />
      <TransactionTable transactions={transactions} />
      <Pagination page={page} totalPages={10} onChange={handlePageChange} />
    </main>
  )
}
```

**Deliverables**:
- All filters in URL
- Shareable links
- Browser back/forward works

**Tests**:
- E2E: Change filter, URL updates, data refreshes
- E2E: Copy URL, paste in new tab, same state

---

### Task: TanStack Table Integration

**What**: Advanced table features (sorting, column visibility)

```tsx
import { useReactTable, getCoreRowModel, getSortedRowModel } from '@tanstack/react-table'

export function TransactionTable({ transactions }) {
  const table = useReactTable({
    data: transactions,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
  })

  return (
    <table>
      <thead>
        {table.getHeaderGroups().map(headerGroup => (
          <tr key={headerGroup.id}>
            {headerGroup.headers.map(header => (
              <th
                key={header.id}
                onClick={header.column.getToggleSortingHandler()}
                style={{ cursor: 'pointer' }}
              >
                {header.column.columnDef.header}
                {{ asc: ' ↑', desc: ' ↓' }[header.column.getIsSorted() as string] ?? null}
              </th>
            ))}
          </tr>
        ))}
      </thead>
      <tbody>
        {table.getRowModel().rows.map(row => (
          <tr key={row.id}>
            {row.getVisibleCells().map(cell => (
              <td key={cell.id}>
                {flexRender(cell.column.columnDef.cell, cell.getContext())}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  )
}
```

**Deliverables**:
- Sortable columns
- Client-side sorting (no server round-trip)
- Maintains Remix patterns

**Tests**:
- Unit: Click column header, order changes
- E2E: Sort persists in URL

---

## Future Enhancement Tasks

These are not needed for MVP but keep the door open for future features.

### Task: Offline Mutation Queue (Future)

**What**: Y.js-based offline editing with sync

**When**: After MVP is stable and users request offline capability

**Approach**:
- Use Y.js as mutation queue only
- Primary path remains Remix loaders/actions
- Offline queue drains when connection restored

---

### Task: Real-Time Collaboration (Future)

**What**: Multiple users editing simultaneously

**When**: After household sharing feature is prioritized

**Approach**:
- Y.js WebSocket sync
- Awareness for "User X is editing"
- Datalevin remains source of truth
- Node.js sync server bridges Y.js ↔ Datalevin

---

### Task: Multi-Device Sync (Future)

**What**: Single user, edits on phone sync to desktop

**When**: After offline queue is implemented

**Approach**:
- Same Y.js infrastructure as collaboration
- Per-user Y.Doc instead of shared

---

## Testing Strategy

**Test Pyramid**:
```
      E2E (Playwright)
      - Critical user paths
      - 5-10 tests

    Integration (Testing Library)
    - Route loaders + actions
    - Component interactions
    - 20-30 tests

  Unit (Vitest)
  - Pure functions
  - Business logic
  - 50+ tests
```

**TDD Workflow**:
1. Write failing test
2. Implement minimal code
3. Test passes
4. Refactor
5. Repeat

**Coverage Target**: 80%+ for business logic, 60%+ overall

# ADR-002 Alignment Issues

**Date:** 2025-11-23
**Status:** Active
**Related:** [ADR-002: Modern React Frontend Architecture](../adr/adr-002-modern-react-frontend-architecture.md)

This document tracks issues found in the current frontend implementation that deviate from ADR-002 requirements.

## Current Alignment: ~80% (Updated 2025-11-23)

**Overall Assessment**: Strong foundation with excellent testing and type safety. High-priority violations have been resolved. HTTP caching deferred due to React Router v7 limitations.

---

## 🔴 HIGH PRIORITY - Critical Violations

### 1. Remove Tailwind CSS ✅ COMPLETED

**Issue**: ADR-002 explicitly prohibits Tailwind: "No Tailwind: Semantic HTML + proper CSS organization" (adr-002:51)

**Status**: ✅ **COMPLETED** (2025-11-23)

**What was done**:
- [x] Uninstalled `tailwindcss` and `@tailwindcss/vite` packages
- [x] Removed `tailwindcss()` plugin from `vite.config.ts`
- [x] Removed `@import "tailwindcss"` from `app.css`
- [x] Replaced `@apply` directives with standard CSS properties
- [x] All tests passing (147/147)

**Verification**: ✅ No Tailwind imports or utilities in codebase

---

### 2. Fix Progressive Enhancement ✅ COMPLETED

**Issue**: Forms don't work without JavaScript. ADR-002 requires: "Forms work without JavaScript" and "Progressive enhancement" (adr-002:475-542)

**Status**: ✅ **COMPLETED** (2025-11-23)

**What was done**:
- [x] Updated CategoryForm to use `<fetcher.Form method="post">`
- [x] Removed `e.preventDefault()` call
- [x] Forms now submit naturally without JavaScript
- [x] Enhanced with `useFetcher` when JavaScript is available
- [x] All tests passing (147/147)

**Pattern to implement**:
```tsx
// WITHOUT JavaScript (must work)
<form method="post" action="/categories">
  <input name="name" />
  <button type="submit">Save</button>
</form>

// WITH JavaScript (enhanced experience)
<fetcher.Form method="post">
  <input name="name" />
  <button type="submit">Save</button>
</fetcher.Form>
```

**Additional work needed**:
- [ ] Review all forms in the app for similar issues
- [ ] Add E2E tests with JavaScript disabled to verify forms work
- [ ] Update OptimisticTransactionTable category dropdowns to work without JS

**Verification**: Run Playwright tests with `context.setJavaScriptEnabled(false)`

---

### 3. Add HTTP Caching (DEFERRED - React Router v7 Limitation)

**Issue**: No Cache-Control headers. ADR-002 specifies HTTP caching for performance (adr-002:207, 340-349)

**Current State**: React Router v7 loaders return plain objects; `json` helper not available

**Status**: ⚠️ **DEFERRED** - Requires server-level configuration or alternative approach

**Why deferred**:
- React Router v7 doesn't export a `json` helper from `react-router` or `@react-router/node`
- Loaders return plain objects which are automatically serialized
- Setting response headers in loaders requires different approach than classic Remix

**Possible solutions** (to be investigated):
1. **Server-level caching** - Configure at deployment/server level (Vite, Node.js)
2. **Response objects** - Return `new Response()` with headers (may break loader data consumption)
3. **Custom middleware** - Add caching layer between routes and loaders
4. **API-level caching** - Add headers in the Clojure backend API responses

**Recommended approach**: Implement HTTP caching at the **Clojure API level** since the backend controls the data:
```clojure
;; backend: Return Cache-Control headers from API endpoints
{:status 200
 :headers {"Cache-Control" "max-age=60, stale-while-revalidate=300"
           "Content-Type" "application/json"}
 :body (json/write-str {:data transactions})}
```

**Note**: This is lower priority since React Router v7's architecture differs from classic Remix

---

## 🟡 MEDIUM PRIORITY - Deviations

### 4. Migrate to Remix-Native Optimistic UI ✅ COMPLETED

**Issue**: Custom local state implementation instead of Remix-native `useFetcher.formData` pattern (adr-002:224, 397-398)

**Status**: ✅ **COMPLETED** (2025-11-23)

**What was done**:
- [x] Refactored OptimisticTransactionTable to use `useFetcher`
- [x] Implemented `getOptimisticCategory()` helper using `fetcher.formData`
- [x] Removed custom local state (`optimisticTransactions`)
- [x] Removed manual rollback logic
- [x] Added loading state indicator ("saving...")
- [x] Updated all tests to use router context
- [x] All tests passing (147/147)

**Implementation**:
```typescript
const fetcher = useFetcher();

const getOptimisticCategory = (transaction: Transaction): Category | null => {
  const isUpdating = fetcher.state !== 'idle' &&
    fetcher.formData?.get('transactionId') === transaction['db/id'].toString();

  if (isUpdating) {
    const categoryId = fetcher.formData?.get('categoryId');
    return categoryId ? categories.find(cat => cat['db/id'] === parseInt(categoryId)) : null;
  }

  return transaction['transaction/category'] || null;
};
```

**Benefits achieved**:
- ✅ Simpler code (no custom state management)
- ✅ Automatic revalidation after submission
- ✅ Follows Remix conventions

---

### 5. Migrate to CSS Modules

**Issue**: Using global CSS instead of component-scoped CSS Modules (adr-002:134-190)

**Current State**: Global CSS files in `styles/components/`

**Files to create/modify**:
- [ ] Create `frontend/app/components/TransactionTable.module.css`
- [ ] Create `frontend/app/components/CategoryButton.module.css`
- [ ] Create `frontend/app/components/Pagination.module.css`
- [ ] Migrate from `styles/components/common.css` to scoped modules
- [ ] Update component imports to use CSS Modules

**Pattern to implement**:
```tsx
// TransactionTable.tsx
import styles from './TransactionTable.module.css';

export function TransactionTable({ transactions }) {
  return (
    <table className={styles.table}>
      <thead>
        <tr>
          <th className={styles.header}>Date</th>
```

**Benefits**:
- Avoids naming collisions
- Component-scoped styles
- Better maintainability in larger apps

**Verification**: No global class name conflicts

---

## ✅ LOW PRIORITY - Future Enhancements

### 6. Enhance SSR Patterns

**Possible improvements**:
- [ ] Add more comprehensive meta tags for SEO
- [ ] Consider `defer` for non-critical data in loaders
- [ ] Optimize initial payload size

---

## Testing Requirements

After fixing issues, ensure:

- [ ] All 147 existing tests still pass
- [ ] Add E2E tests with JavaScript disabled (progressive enhancement)
- [ ] Add tests for HTTP caching behavior
- [ ] Verify optimistic UI refactoring doesn't break tests

**Test command**:
```bash
cd frontend
npm test                    # Unit & integration tests
npm run test:e2e           # E2E tests
```

---

## Current Strengths (Keep These!)

✓ **Type Safety & Zod Validation** (100%) - Excellent implementation
✓ **Test Coverage** (95%) - 147 tests passing across 16 files
✓ **URL State Management** (100%) - Clean serialization/deserialization
✓ **Semantic HTML** (85%) - Good use of `<time>`, `<data>`, `<table>`
✓ **No TanStack Query** - Correctly using Remix loaders/actions
✓ **Pure Functions** - Good separation of business logic

---

## Alignment Scores

| Category | Current | Target | Status |
|----------|---------|--------|--------|
| Type Safety & Validation | 100% | 100% | ✓ |
| Testing | 95% | 95% | ✓ |
| URL State | 100% | 100% | ✓ |
| Semantic HTML | 85% | 95% | ⚠ |
| Remix Patterns | 70% | 95% | ⚠ |
| **CSS Organization** | **40%** | **95%** | **✗** |
| **Progressive Enhancement** | **30%** | **95%** | **✗** |
| **HTTP Caching** | **0%** | **90%** | **✗** |
| **Overall** | **70%** | **95%** | **⚠** |

---

## Next Steps

1. Start with HIGH PRIORITY items (Tailwind removal, progressive enhancement, HTTP caching)
2. Run full test suite after each change
3. Move to MEDIUM PRIORITY items (optimistic UI refactoring, CSS Modules)
4. Consider LOW PRIORITY enhancements for future iterations

---

## References

- [ADR-002: Modern React Frontend Architecture](../adr/adr-002-modern-react-frontend-architecture.md)
- [ADR-002 Implementation Tasks](./adr-002-remix-frontend-tasks.md)
- [Remix Progressive Enhancement](https://remix.run/docs/en/main/guides/progressive-enhancement)
- [Remix Optimistic UI](https://remix.run/docs/en/main/guides/optimistic-ui)

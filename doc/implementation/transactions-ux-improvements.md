# Transactions Table UX Improvements - Planning

**Date:** 2025-11-23
**Status:** Planning

## Overview

This document organizes UX improvement requests for the transactions table into implementation categories.

---

## 📦 Category 1: Minor Visual Polish (CSS Only)

These changes require only CSS modifications, no logic changes.

### 1.1 Fix Category Column Width Stability
**Issue**: Category column changes width when dropdown opens

**Cause**: Likely the dropdown or button inside the cell doesn't have a fixed width
**Fix**: Set explicit `min-width` or `width` on category column/cell
**File**: `frontend/app/styles/components/category-button.css` or TanStack Table column definition
**Effort**: 5 minutes
**Risk**: Low

---

### 1.2 Fix Category Name Text Alignment
**Issue**: Long category names wrap and become centered instead of left-aligned

**Cause**: CSS text-align property likely set to center on wrapped text
**Fix**: Ensure `text-align: left` is consistently applied to category cells
**File**: `frontend/app/styles/components/category-button.css` or table CSS
**Effort**: 5 minutes
**Risk**: Low

---

### 1.3 Tighter Row Heights (Spreadsheet-like)
**Issue**: Transaction rows have too much vertical padding

**Cause**: Generous padding for readability
**Fix**: Reduce padding on `<td>` elements in transaction table
**File**: `frontend/app/styles/components/common.css` or table-specific CSS
**Effort**: 5 minutes
**Risk**: Low - may need to ensure touch targets remain accessible

---

### 1.4 Ensure Amount Column Minimum Width
**Issue**: Amount column sometimes too narrow for large numbers

**Cause**: Column width not explicitly set for monospace numbers
**Fix**: Set `min-width` to accommodate 11 monospace characters (~110px with typical font)
**File**: TanStack Table column definition or CSS
**Effort**: 5 minutes
**Risk**: Low

**Implementation**:
```css
/* Ensure amount column fits at least 11 monospace chars */
.amount-cell {
  min-width: 110px;
  font-variant-numeric: tabular-nums;
}
```

---

## 🎨 Category 2: Frontend Data Display

Changes requiring frontend logic modifications but no backend changes.

### 2.1 Improve Date Formatting
**Current**: Date formatting exists in `frontend/app/lib/format.ts`
**Desired**: "Oct 12, 2025" format with 3-char month abbreviation

**Status**: Need to verify current implementation
**File**: `frontend/app/lib/format.ts` + `tests/unit/format.test.ts`
**Effort**: 10-15 minutes (update function + tests)
**Risk**: Low - pure function with tests

**Implementation**:
```typescript
// frontend/app/lib/format.ts
export function formatDate(isoDate: string): string {
  const date = new Date(isoDate);
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
                  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const month = months[date.getMonth()];
  const day = date.getDate();
  const year = date.getFullYear();
  return `${month} ${day}, ${year}`;
}
```

---

## 🔌 Category 3: Requires Backend Investigation

These may need backend changes depending on current data availability.

### 3.1 Show Account Name in Transactions Table
**Issue**: Transactions table doesn't show which account each transaction belongs to

**Investigation needed**:
1. Does the backend already include account data in transaction responses?
2. Check `/api/transactions` response structure
3. Check `Transaction` type in `frontend/app/lib/api.ts`

**Possible scenarios**:
- **Best case**: Account data already included, just need to display it
- **Moderate**: Need to add account to backend pull pattern: `[* {:transaction/category [*] :transaction/account [*]}]`
- **Worst case**: Need to add account relationship to schema

**Files to check**:
- `backend/src/finance_aggregator/server.clj` - GET /api/transactions endpoint
- `backend/src/finance_aggregator/db/transactions.clj` - query pull pattern
- `frontend/app/lib/api.ts` - Transaction type definition

**Effort**: 15-30 minutes (depends on whether backend change needed)
**Risk**: Low-Medium (backend change would require tests)

---

### 3.2 Default Sort by Date Ascending
**Issue**: Transactions should be sorted by date ascending by default

**Current State**: Need to verify where default sort is set
- Option A: Backend returns unsorted data, frontend sets default
- Option B: Backend should sort by default
- Option C: URL state has default sort

**Investigation needed**:
1. Check `frontend/app/routes/home.tsx` - What's the initial `SortingState`?
2. Check backend `/api/transactions` - Does it accept a sort parameter?
3. Per ADR-002, should we set default in URL or in backend?

**Recommended approach**: Backend should return sorted by date ascending by default
- Reason: Matches "server as source of truth" principle
- Frontend can override with URL params

**Files to check/modify**:
- `backend/src/finance_aggregator/server.clj` - Add default sort to query
- `frontend/app/routes/home.tsx` - Verify default `SortingState` if backend doesn't sort

**Effort**: 15-20 minutes
**Risk**: Low (well-tested area)

---

## 🚀 Category 4: Complex UX Enhancement

These require new interaction logic and more careful implementation.

### 4.1 Enter Key Navigation Between Category Dropdowns
**Issue**: After selecting a category, pressing Enter doesn't jump to the next transaction's category dropdown

**Current Flow**:
1. User selects category from dropdown ✓
2. User presses Tab twice to reach next category dropdown
3. User presses Enter to open dropdown
4. User selects category

**Desired Flow**:
1. User selects category from dropdown ✓
2. User presses Enter → automatically jumps to next transaction's category dropdown (opens it)
3. User selects category

**Implementation Requirements**:
1. Detect Enter key in CategoryDropdown component
2. Find the next transaction row in the table
3. Focus and open the next category dropdown
4. Handle edge cases (last transaction, filtered views, etc.)

**Files to modify**:
- `frontend/app/components/CategoryDropdown.tsx` - Add Enter key handler
- `frontend/app/lib/keyboardNavigation.ts` - May need to extend navigation logic
- `frontend/app/components/OptimisticTransactionTable.tsx` - Coordinate between rows

**Complexity**:
- Need to manage focus across multiple dropdown instances
- May need refs to track dropdown components
- Need to handle pagination (what if next transaction is on next page?)

**Effort**: 45-60 minutes
**Risk**: Medium (keyboard navigation is tricky, needs thorough testing)

**Test scenarios**:
- [ ] Pressing Enter after selecting category focuses next dropdown
- [ ] Works across multiple transactions
- [ ] Last transaction wraps or does nothing
- [ ] Works with filtered/sorted tables
- [ ] Doesn't break existing keyboard navigation

---

### 4.2 Whitespace-Insensitive Category Filtering
**Issue**: Typing "workinc" doesn't match "Work Income"

**Current Implementation**: Check `frontend/app/lib/categoryFiltering.ts`

**Desired Behavior**:
- "workinc" matches "Work Income"
- "foodgroceries" matches "Food - Groceries"
- Case insensitive AND whitespace insensitive

**Implementation**:
```typescript
// Normalize by removing whitespace and lowercasing
function normalizeForFiltering(text: string): string {
  return text.toLowerCase().replace(/\s+/g, '');
}

export function filterCategories(
  categories: Category[],
  query: string
): Category[] {
  if (!query.trim()) return categories;

  const normalizedQuery = normalizeForFiltering(query);

  return categories.filter(category => {
    const normalizedName = normalizeForFiltering(category['category/name']);
    return normalizedName.includes(normalizedQuery);
  });
}
```

**Files to modify**:
- `frontend/app/lib/categoryFiltering.ts` - Update filter logic
- `frontend/app/lib/categoryFiltering.test.ts` - Add tests for whitespace insensitivity (if file exists)

**Effort**: 20-30 minutes (implementation + tests)
**Risk**: Low-Medium (need to ensure tests cover edge cases)

**Test scenarios**:
- [ ] "workinc" matches "Work Income"
- [ ] "foodgroceries" matches "Food - Groceries"
- [ ] Still matches partial words ("work" matches "Work Income")
- [ ] Case insensitive
- [ ] Empty query returns all categories

---

## 📊 Summary by Effort and Risk

### Quick Wins (< 15 min, Low Risk)
1. ✓ Category column width stability (CSS)
2. ✓ Category name text alignment (CSS)
3. ✓ Tighter row heights (CSS)
4. ✓ Amount column minimum width (CSS)

### Medium Effort (15-30 min, Low-Medium Risk)
5. ✓ Date formatting improvement
6. ✓ Show account name (may need backend)
7. ✓ Default sort by date
8. ✓ Whitespace-insensitive filtering

### Higher Effort (45+ min, Medium Risk)
9. ✓ Enter key navigation between dropdowns

---

## Recommended Implementation Order

### Phase 1: Visual Polish (All CSS, ~20 minutes total)
Do these first because they're quick, low-risk, and provide immediate visible improvement:
1. Fix category column width stability
2. Fix category name text alignment
3. Tighten row heights
4. Ensure amount column minimum width

### Phase 2: Data Display & Backend (30-45 minutes)
5. Improve date formatting
6. Default sort by date ascending
7. Add account name to transactions table

### Phase 3: Enhanced Filtering (30 minutes)
8. Whitespace-insensitive category filtering

### Phase 4: Advanced UX (45-60 minutes)
9. Enter key navigation between category dropdowns

---

## Notes on Project Conventions

From ADR-002 and project docs:
- ✓ Use TDD: Write tests before implementation
- ✓ Pure functions in `lib/` with comprehensive tests
- ✓ Use semantic HTML
- ✓ No Tailwind CSS (recently removed)
- ✓ CSS organized in `styles/base/`, `styles/components/`, `styles/layouts/`
- ✓ Run tests after each change: `cd frontend && npm test`

---

## Testing Strategy

After each change:
```bash
cd frontend
npm test                    # Run all unit & integration tests
npm run test:e2e           # Run E2E tests if applicable
```

For CSS changes:
- Visual inspection in browser
- Test at different viewport sizes
- Verify accessibility (color contrast, touch targets)

For logic changes:
- Write tests first (TDD)
- Update existing tests if behavior changes
- Run full test suite before committing

---

## Files Reference

**Frontend Components**:
- `frontend/app/components/OptimisticTransactionTable.tsx` - Main table component
- `frontend/app/components/CategoryDropdown.tsx` - Category selection dropdown
- `frontend/app/routes/home.tsx` - Main route with transactions section

**Frontend Utilities**:
- `frontend/app/lib/format.ts` - Formatting functions (date, amount)
- `frontend/app/lib/categoryFiltering.ts` - Category filtering logic
- `frontend/app/lib/keyboardNavigation.ts` - Keyboard navigation utilities
- `frontend/app/lib/api.ts` - API client and type definitions

**Frontend Styles**:
- `frontend/app/styles/components/category-button.css`
- `frontend/app/styles/components/common.css`
- `frontend/app/styles/components/pagination.css`

**Backend**:
- `backend/src/finance_aggregator/server.clj` - API endpoints
- `backend/src/finance_aggregator/db/transactions.clj` - Transaction queries

**Tests**:
- `frontend/tests/unit/format.test.ts`
- `frontend/tests/components/OptimisticTransactionTable.test.tsx`
- Backend tests in `backend/test/`

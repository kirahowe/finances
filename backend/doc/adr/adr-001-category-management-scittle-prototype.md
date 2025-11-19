# ADR-001: Category Management and Transaction Table - Scittle/Reagent Prototype

**Date:** 2025-11-10
**Updated:** 2025-11-18
**Status:** Superseded (pivot to proper React application)

## Context

The finance aggregator application needed:
1. A way to categorize transactions for better financial tracking
2. An interactive table to view and manage transactions
3. Inline editing capabilities to assign categories to transactions
4. A responsive UI with sorting, pagination, and filtering

### Initial Constraints
- Prototype environment using Scittle (browser-based ClojureScript)
- Reagent for React integration
- Re-frame for state management
- No build tooling or compilation step (rapid prototyping)
- Datalevin as the database with Datalog queries

### Problem Statement
How do we build a fully-featured transaction management UI with modern table capabilities (TanStack Table v8) while working within the constraints of Scittle/Reagent?

## Decision

We implemented a complete category management and transaction table system using:

### Backend Architecture
- **Database Layer**: Datalevin with schema-based entity modeling
- **Category CRUD**: Full create, read, update, delete operations with validation
- **Transaction Association**: Many-to-one relationship (transaction → category)
- **API Layer**: RESTful endpoints for all operations

### Frontend Architecture
- **Scittle/Reagent**: Browser-based ClojureScript for rapid prototyping
- **Re-frame**: Event-driven state management
- **TanStack Table v8**: Advanced table functionality via React interop
- **Pure React Components**: Direct `React.createElement` usage for hook compatibility

### Key Technical Decisions

#### 1. React Hooks Integration
**Problem**: React Error #321 - hooks called outside React function component context when using Reagent wrappers.

**Solution**:
```clojure
(defonce TransactionsTable
  (let [createElement (.-createElement React)]
    (fn [props]  ; Pure JavaScript function component
      (let [sorting-state (useState #js [])]  ; Hooks work here
        ...))))

; Render via Reagent
[:> TransactionsTable {:data data :pageIndex 0}]
```

**Reasoning**: Reagent's `r/reactify-component` and `r/adapt-react-class` don't preserve React component identity needed for hooks. Direct `createElement` bypasses this limitation.

#### 2. Namespaced Keywords in JSON
**Problem**: ClojureScript namespaced keywords (`:transaction/amount`) don't serialize properly to JavaScript.

**Solution**:
```clojure
(clj->js data :keyword-fn (fn [k] (str (namespace k) "/" (name k))))
; {:transaction/amount 50} → {"transaction/amount": 50}
```

**Reasoning**: TanStack Table needs JavaScript objects with string keys. Custom keyword function preserves namespace information as strings.

#### 3. Inline Editing State Management
**Problem**: How to manage edit state across React components and re-frame?

**Solution**: TanStack Table metadata pattern
```clojure
(useReactTable
  #js {:data data
       :columns columns
       :meta #js {:editingTxId editing-tx-id
                  :onEditClick on-edit-click
                  :categories categories}})
```

**Reasoning**: Metadata provides clean way to pass callbacks and state to cell renderers without prop drilling.

#### 4. Optimistic UI Updates
**Implementation**: Update UI immediately, rollback on API failure
```clojure
(rf/dispatch [::update-transaction-category-optimistic tx-id category])
(-> (js/fetch ...)
    (.catch (fn [error]
              (rf/dispatch [::update-transaction-category-optimistic
                           tx-id original-category]))))
```

**Reasoning**: Provides responsive UX while maintaining data consistency.

## Implementation Details

### Data Model

#### Category Entity
```clojure
{:db/id 123
 :category/name "Groceries"
 :category/type :expense
 :category/ident :category/groceries}
```

#### Transaction with Category
```clojure
{:db/id 456
 :transaction/external-id "tx-123"
 :transaction/amount -45.67M
 :transaction/payee "Whole Foods"
 :transaction/category {:db/id 123
                        :category/name "Groceries"
                        :category/type :expense}}
```

### API Endpoints

#### Categories
- `GET /api/categories` - List all categories
- `POST /api/categories` - Create category
  ```json
  {"name": "Groceries", "type": "expense", "ident": "groceries"}
  ```
- `PUT /api/categories/:id` - Update category
- `DELETE /api/categories/:id` - Delete (validates no assigned transactions)

#### Transactions
- `GET /api/transactions` - List transactions with category data (pull pattern)
- `PUT /api/transactions/:id/category` - Assign/update category
  ```json
  {"categoryId": 123}  // or null to remove
  ```

### Files Created
- `src/finance_aggregator/db/categories.clj` - Category database operations
- `test/finance_aggregator/db/categories_test.clj` - Category CRUD tests (6 tests, 27 assertions)
- `test/finance_aggregator/db/transactions_test.clj` - Transaction category tests (2 tests, 10 assertions)
- `resources/public/js/categories.cljs` - Category management UI
- `resources/public/js/transactions.cljs` - Transaction table with TanStack

### Files Modified
- `src/finance_aggregator/server.clj` - Added category and transaction endpoints
- `src/finance_aggregator/db/transactions.clj` - Added `update-category!` function
- `resources/public/index.html` - Integrated TanStack Table CDN, components
- `src/finance_aggregator/data/schema.clj` - Added category schema

### Features Implemented
- ✅ Category CRUD with validation
- ✅ Transaction table with sorting on all columns
- ✅ Pagination (first, previous, next, last, page size)
- ✅ Inline category editing via dropdown
- ✅ Optimistic UI updates with error rollback
- ✅ Real-time category management UI

## Consequences

### Positive
1. **Rapid Prototyping**: Scittle enabled quick iteration without build setup
2. **Full Feature Set**: Successfully integrated complex React library (TanStack Table)
3. **Test Coverage**: Backend has comprehensive test coverage
4. **Learning**: Documented solutions to React hooks + Reagent integration challenges
5. **API Design**: RESTful endpoints ready for proper frontend
6. **Working Prototype**: Fully functional category management system

### Negative
1. **Scittle Limitations**:
   - Reagent wrappers incompatible with React hooks
   - Required workarounds (pure React components)
   - No TypeScript support
   - Limited tooling and debugging
2. **Performance**: Browser-based ClojureScript has overhead
3. **Maintainability**: Mix of ClojureScript patterns and JavaScript interop is complex
4. **Known Issues**: Categories need auto-fetching when transactions load (empty dropdown)

### Lessons Learned

#### React Hooks + Reagent
- Reagent component wrappers break React hook rules
- Solution: Use pure React function components with `React.createElement`
- Render via Reagent's `:>` syntax to maintain React component identity

#### State Management
- TanStack Table metadata pattern works well for passing callbacks
- Re-frame events handle optimistic updates cleanly
- Separate concerns: React for UI, re-frame for state

#### Interop Patterns
- `clj->js` with custom `:keyword-fn` preserves namespace semantics
- `into-array` + `map` for JavaScript array construction
- Direct property access via `aget`/`aset` for performance

## Future Direction

### Decision: Pivot to Proper React Application

**Rationale**:
- Scittle/Reagent workarounds add unnecessary complexity
- Modern React tooling (TypeScript, Vite, ESLint) provides better DX
- TanStack Table designed for standard React, not Reagent
- Proper build pipeline enables optimization and better debugging

**Migration Path**:
1. Preserve backend (Clojure/Datalevin) - well-tested and working
2. Build new React frontend (TypeScript + Vite)
3. Reuse API endpoints as-is
4. Reimplement UI components in standard React
5. Use TanStack libraries directly (Table, Query, Router)

**What to Preserve**:
- Backend architecture and API design
- Data model and schema
- Test coverage and patterns
- UI/UX patterns (inline editing, optimistic updates)
- This ADR as reference for solved problems

**What to Leave Behind**:
- Scittle/Reagent workarounds
- Browser-based ClojureScript compilation
- Re-frame (replace with React Query + Context/Zustand)
- Hiccup syntax (use JSX)

## References

- [TanStack Table v8 Documentation](https://tanstack.com/table/v8)
- [React Hooks Rules](https://react.dev/warnings/invalid-hook-call-warning)
- [Reagent Documentation](https://reagent-project.github.io/)
- [Re-frame Documentation](https://day8.github.io/re-frame/)

## Appendices

### Test Coverage Summary
- **Category CRUD**: 6 tests, 27 assertions (100% coverage)
- **Transaction Category Assignment**: 2 tests, 10 assertions
- **Total Backend Tests**: 8 tests, 37 assertions

### Enhancement Backlog (Future React App)
- Auto-fetch categories when transaction table loads
- Hierarchical categories (parent/child relationships)
- Auto-categorization rules based on payee patterns
- Bulk category assignment
- Category analytics/summaries (spending by category)
- Category colors/icons for visual distinction
- Advanced filtering (by category, date range, amount range)
- Export transactions to CSV/JSON
- Import categorization rules
- Mobile-responsive table design

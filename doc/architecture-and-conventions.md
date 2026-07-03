# Architecture & Conventions

The durable engineering guide for this codebase: the layered architecture, the code-style
bars, which libraries to reach for (and when *not* to), and the verification discipline. Read
this before a refactor or a new feature. It complements [`.claude/CLAUDE.md`](../.claude/CLAUDE.md)
(dev environment, TDD, no-inline-styles) — this doc is the *why* and the layering.

The **transactions feature is the reference implementation** of everything below:
`web/view.clj` (transformation), `web/pages/transactions_view.clj` (view),
`web/pages/transactions.clj` (handler).

---

## 1. Layered architecture

Four layers, dependencies flowing **one direction only** (Data → Transformation → View; the
Handler wires them together). A lower layer never reaches up.

```
  Data ──────────▶ Transformation ──────────▶ View
  (db/*,           (pure, no I/O:              (dumb hiccup:
   datalevin)       web.view, view-state,       transactions-view,
                    transfers, splits,          layout, shell, format)
                    categories, month)               ▲
        ▲                  ▲                          │
        └──────────────────┴──────────────┐          │
                                    Handler (glue)────┘
                          (web.pages.transactions, http/*, ws/*)
```

### Data layer — `db/*`
Queries and mutations against Datalevin. The **only** layer that touches `datalevin.core`.
Returns canonical domain entities (e.g. `list-for-month` pulls + applies the
`with-derived-fields` annotation pipeline). Nothing above it knows about `d/q`/`d/pull`.

### Transformation layer — pure, no I/O
`web.view`, `web.view-state`, `transfers`, `splits`, `categories`, `web.month`, `web.accounts`,
`data.cleaning`. Plain functions: data in, data out. **No `datalevin`, no `db.*` requires.**
- A "pure predicate" belongs here, *not* in the data layer just because it reads an entity.
  (`needs-category?` lived in `db.transactions` but does no I/O — it was moved to `web.view`,
  which then has zero data-layer deps.)
- This layer is exhaustively unit-tested (kaocha) — it's where the rules live, so it's where
  the tests live.

### View layer — strictly dumb, presentational
`web.pages.transactions-view`, `web.layout`, `web.shell`, `web.format`. **Data in, hiccup out.**
A view function never:
- fetches (no `db.*`, no `datalevin`),
- transforms (no calls into `web.view` etc. *inside* a hiccup fn),
- reads ambient state (no `commands` log, no `auth/user-id`).

If a fragment needs a derived value, the **handler computes it and passes it in**. Worked
examples from the refactor:
- `undo-redo-controls` takes `{:undo-label :redo-label}` (was reading the command log).
- `split-editor-modal` takes its `seed` rows (was calling `view/split-editor-seed`).
- the rollup pane is `rollup-pane` (dumb); the handler feeds it `(view/category-rollup …)`.

Result: `transactions-view` requires only `str`, `fmt`, `month`, `render`, `shell`,
`view-state` — all presentation. (`view-state` is allowed: it's static column/layout *config*,
not transformation.)

### Handler layer — glue only, **no logic**
`web.pages.transactions`, `http/*`, `ws/*`. A handler routes data through the layers:
**fetch → transform → render → respond.** Every line is a single call to a lower layer; there is
no business logic, no inline data-shaping, no hiccup. The canonical shape:

```clojure
(let [txs     (db-transactions/list-for-month db-conn month)   ; Data
      view-st (vs/signals->view-state signals)                 ; Transformation (codec)
      model   (view/present txs view-st {:categories cats})]   ; Transformation (presenter)
  (layout/document {…} (tv/page-body {… :model model})))       ; View
```

### The presenter pattern
A handler should not know *which* transformation primitive feeds *which* fragment. One
transformation entry point bundles the whole response view-model:

```clojure
(view/present txs view-st {:linger linger-set :categories cats})
;; => {:result … :counts … :account-options … :institution-options …
;;     :category-options … :rollup …}
```

`page`, `rows`, and `edit-response` all route through `present`; the SSE patcher
(`patch-view!`) reads the model rather than recomputing. Add a presenter per feature.

### Legitimate exceptions (not violations)
- **Command / service layer** (`web.commands`): maps a command-type → the matching DB mutation
  and *does* depend on `db.*`. That's correct — a service orchestrates the data layer. Keep
  commands as **plain data** (`{:type :tx-id :before :after :label}`), never embedded fns, so
  the log stays persistable.
- **SSE-shaped error handling** (`handle-edit`): catches an `ex-info` and renders an error-bar
  *fragment* into the SSE stream. It can't be generic middleware (a Datastar client needs a
  patch, not a 500), so it stays in the handler.

---

## 2. Code style

Functional, data-driven, **"Simple Made Easy"** (Hickey). Prefer plain maps + functions and
multimethods over OO; **no** protocols/records/stateful registries for domain logic.

- **Files ≤ ~1000 lines.** Split along a real seam, not an arbitrary cut. The transactions page
  was split rendering-vs-handler (1163 → 355 + 823), not by line count.
- **Functions: a handful of lines** — but don't chop an irreducible hiccup tree or a dense,
  well-tested algorithm just to hit a number. Length from a big DOM literal or one cohesive
  reduce is fine; length from mixed concerns is not.
- **No inline styles.** CSS only (see `.claude/CLAUDE.md` for the file layout).
- **Type hints where reflection bites** — hot paths and interop (`^Date`, `^LocalDate`,
  `^NumberFormat`). Run `*warn-on-reflection*` after touching interop.
- **No redundant fetching within a request** — compute a dataset once, thread it. (Across
  requests, the app is server-authoritative: re-fetch after every write. Don't "optimize" that
  away.)

---

## 3. Reach for the right library — not dogmatically

- **Tablecloth** — for genuinely **tabular / columnar** work: CSV parsing + column detection
  (`csv/data.clj`), dedup/grouping (`data/cleaning.clj`). **Do NOT** force it onto nested entity
  maps or per-row rendering pipelines (`web.view` filters transactions-with-nested-splits — that
  is `filter`/`sort-by` territory; a dataset there means stuffing maps into cells and writing
  the same `get-in` predicates, slower). Columnar group-by-aggregate → Tablecloth; predicate
  filtering of nested maps → the standard library.
- **tick** — for date/time, especially **zone conversions**. Replace manual
  `.toInstant`/`.atZone`/`.toLocalDate` interop *chains* with tick. The Date→UTC-calendar-date
  rule lives once in `utils/date->local-date`. **But** keep trivial field reads (`.getYear`,
  `.getMonthValue`) as plain interop — tick wins on the zone juggling, not simple accessors.
- **Standard library first.** Most "transformation" is `filter`/`map`/`reduce`/`group-by`/
  `frequencies` over maps. Don't reinvent and don't over-tool.

---

## 4. Verification discipline (before every commit)

1. **kaocha** — `clojure -M:test -m kaocha.runner` (the pure transformation layer is the test
   target).
2. **e2e** — `bb e2e` (Playwright). **This catches what kaocha can't:** a render bug in the
   handler/view path passes every unit test but 500s the page. (A `let` that shadowed a view fn
   with a model key shipped green kaocha and was only caught by e2e.)
3. **bb lint** — clj-kondo + tsc; catches unused/missing requires and unresolved symbols (the
   safety net when moving forms between namespaces).

Other habits that paid off this session:
- **Verify before converting.** For a mechanical/equivalence refactor, prove the new form
  byte-equals the old in the REPL *first* (date conversions, the counts fragment).
- **Commit in logical chunks** with succinct messages; one concern per commit.
- **Surface, don't unilaterally delete.** Dead code that contradicts a stated direction, or a
  hard-to-reverse change, is the user's call — flag it.
- **Push back when a tool doesn't fit.** Using the right tool > satisfying a blanket rule.

---

## Status / scope

The transactions workspace and `setup.clj` are both at this four-layer standard (handler →
presenter → dumb `-view` namespace), and the recent provider-sync engine and monthly-close work
followed it (e.g. `data/ledger.clj` pure math → `web/view.clj` `month-close` presenter → the
`close-panel` dumb view → thin handlers). Apply the same template to any new surface; the next
one on deck is the cross-month tracking view (see
[`monthly-close-handoff.md`](plans/monthly-close-handoff.md)).

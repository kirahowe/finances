# Manual posted dates (+ reviewed → reconciled rename)

**Goal:** support reconciling credit-card statements that include imported transactions
whose provider never supplied a posted date. Lunchflow (and CSV/manual imports) return a
single date; `parse-transaction` copies it into `:transaction/posted-date` as a guess.
When the true posted date crosses a statement boundary, the statement won't tie out and
the user needs to correct that transaction's posted date by hand. Only Plaid supplies a
genuinely independent posted date today.

**Model:** the register-app lineage (ledger-cli's primary/effective dates + the
Quicken-style per-transaction reconcile mark). The per-transaction mark already exists as
`:transaction/reviewed` — renamed to `reconciled` (step 3) because that is what it means
in practice.

## Design

Mirrors the existing `user-description → effective-description` overlay pair exactly.

### Data model

- **New overlay attr** `:transaction/user-posted-date` (`:db.type/instant`), absent =
  no override. Added to `provider.contract/overlay-keys` — providers can never write it,
  so it survives every re-import/resync via the existing `db/insert!` present-keys-only
  invariant. No importer changes needed.
- **New derived field** `:transaction/effective-posted-date` =
  `(or user-posted-date posted-date date)`, computed by a pure helper
  `data.ledger/effective-posted-date` (ledger owns "which date does bucketing go by";
  it requires nothing, so `db.transactions` and `db.transfers` can both use it without
  cycles). `db.transactions/with-derived-fields` annotates it on every pulled row.
- The imported `:transaction/posted-date` is never mutated (append-only overlays).
  Clearing the override (nil) retracts the datom and falls back to the provider value.

### Read paths — everything buckets by the effective value

| Site | Change |
|---|---|
| `db.transactions/list-for-month` | drop the datalog posted-date clauses; pull non-part txns, annotate, month-filter on `effective-posted-date` in Clojure. (The old query already pulled all history before end-of-month and post-filtered — no real perf change at this scale.) |
| `db.transactions/list-for-account-range` | same restructure; span-filter `(from, to]` and sort by effective. |
| `data.ledger/covered?` / `month-coverage` | read `:transaction/effective-posted-date` (callers already pass annotated rows). |
| `web.view` `:date` sort key | effective. |
| `db.transfers` day matching (`:day`, suggestion window) | effective via the ledger helper; add `:transaction/user-posted-date` to its own pull pattern. |
| `date-cell` posted hint | hint renders from effective; when an override exists add `posted-hint--manual` class + title "Posted date set manually". Transaction date display is unchanged. |
| `transaction-pull-pattern` transfer-pair nested pull | also pull the partner's `user-posted-date`. |
| `data.cleaning` dedup | unchanged — import-time, provider data only, no overlays exist yet. |

### Split families

Parts carry *copies* of the parent's posted-date, so the override is family-uniform:

- `db.transactions/set-user-posted-date!` resolves the family root via
  `split-editor-root`, then asserts (or retracts, on nil) the override on the root AND
  every live part in one `d/transact!`.
- `splits/inherited-fields` + the `propagate-inherited-fields!` pull/recipe gain
  `:transaction/user-posted-date`, so parts created after an override inherit it and
  re-import propagation keeps families converged.

### Write path + UI

- Route `PUT /transactions/:id/posted-date`; new value rides a courier signal as
  `YYYY-MM-DD` (empty = clear), mirroring `set-description`. Handler parses, records
  command, `edit-response` re-render.
- Command `:set-posted-date` in `web.commands/mutate!` — value = Date-or-nil;
  `:before` = the family root's prior override; labels "Set posted date" /
  "Cleared posted date". Undo/redo ride the existing machinery.
- UI entry point: row-actions menu item "Set posted date…" →
  `GET /transactions/:id/posted-date-editor` renders a small modal into `#modal-root`
  (match-editor/statement-editor pattern, focus-trap island applies): a `type=date`
  input prefilled with the effective posted date, a muted "Imported: <posted-date>"
  line, Clear (only when an override exists) + Save. Server resolves split parts to the
  family root. All styles in a CSS file (components/), never inline.

### Step 3 — rename `reviewed` → `reconciled`

The flag is the per-transaction reconcile mark; name it so. Full rename (single-user,
refactor-aggressively): schema attr + data migration (assert `:transaction/reconciled`,
retract `:transaction/reviewed`; runs AFTER the legacy split migration, which still
writes the old attr), `overlay-keys`, `set-reviewed!`/command `:set-reviewed`/route
`/reviewed/` → reconciled equivalents, pull patterns, view scope keyword + URL token
(old token in a stale URL must degrade gracefully to the default scope), view-state
column id + label ("Reconciled"), UI copy (scope toggle "To reconcile [n]", gate "All
reconciled" / "N to reconcile", undo labels "Marked reconciled/unreconciled"), CSS
classes `.reviewed-*` → `.reconciled-*`, e2e selectors/copy, e2e seed data.

## Order of work

1. Data layer + contract (schema, overlay-keys, derivation helper, list fns, ledger,
   transfers, view sort, splits inheritance, `set-user-posted-date!`) — TDD, kaocha.
2. Endpoint + command + modal UI + row-menu item + date-cell hint + CSS + e2e spec
   (`v2-posted-date.ts`: menu → modal → save → hint + row moves bucket → undo → clear).
3. The rename, with migration; full kaocha + vitest + e2e.

Each step is a logical commit on `main`.

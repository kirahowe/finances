# Splits as transactions

**Date:** 2026-06-14, expanded 2026-07-07
**Status:** Designed — ready to implement (phases below)

## Problem

Split parts are modeled as child objects (`:transaction/splits` component sub-entities)
hanging off an immutable, imported parent transaction. Every transaction-grain behavior
in the app operates at the **parent grain** and has to be re-derived (often incompletely)
for splits — or is simply missing. Each new transaction feature silently inherits this
blind spot.

Known casualties of the two-grain model:

1. **Filtered views break splits.** A split needing review shows only the parent stub
   under the needs-review filter; parts created while a filter is active aren't even
   reviewable (the parent has no checkbox, the parts aren't rows).
2. **Unreviewing a split corrupts its state** — the parent-flag/part-flag roll-up
   (`with-reviewed` overriding `:transaction/reviewed`) desyncs.
3. **The split modal can't express "uncategorized"** — `validate-splits` requires a
   category per part, so an unknown part can't be saved for later.
4. **The reviewed filter can't see partially reviewed splits** — one per-transaction
   effective flag ("every part reviewed") hides the reviewed part along with the rest.
5. **Keyboard navigation doesn't reach split rows** — grid-nav carries a whole
   `RowKind`/`splitId` sub-model just for splits, and it's still incomplete.
6. **Category filter vs. split attribution don't reconcile** — the rollup attributes
   dollars to part categories; clicking a rollup row may not surface those rows and the
   totals don't tie out.
7. **Transfer badge / matching is parent-only** — a part categorized as a transfer gets
   no badge and no way to match a counterpart.

## Decision

Promote split parts to **real `:transaction/*` rows**, following the precedent set by
manual transactions (first-class rows, `:transaction/provider :manual`, generated
external-id, no dedup). The parent stays in the database untouched (append-only
source-of-truth) but is **excluded from every list/sum once it has parts** — the parts
*replace* it at the row grain. All transaction machinery (filters, counts, sorting,
search, review, categorize, keyboard nav, transfer matching, rollup, reconciliation)
then covers splits by construction, and the split special-casing in the view engine,
grid-nav, and renderer is deleted.

### Schema

```clojure
;; on the part (a normal transaction plus:)
:transaction/split-parent {:db/valueType :db.type/ref}   ; → originating transaction; presence marks a part
:transaction/split-order  {:db/valueType :db.type/long}  ; stable editor/display order among siblings
```

A part is created with:

| attr | value |
|---|---|
| `:transaction/external-id` | `"split-<uuid>"` (generated; stable across editor edits) |
| `:transaction/split-parent` | ref to the parent |
| `:transaction/provider` | `:split` (provenance: user-authored, lifecycle-managed by its parent) |
| `:transaction/account` `/user` `/date` `/posted-date` `/payee` | **copied from the parent** (see "Inherited fields") |
| `:transaction/amount` | the part's signed amount (parts must sum exactly to the parent — unchanged bigdec validation) |
| `:transaction/description` | the part's memo (user-authored, like a manual row's description) |
| `:transaction/category` `/reviewed` `/transfer-pair` … | normal overlays, per part, via the normal endpoints |

`:split/*` sub-entity attrs and `:transaction/splits` are retired after migration.

### The invariants, and how each footgun is closed

**No double counting.** The read layer (the `db.transactions` list fns and every other
"all transactions" query: `db.transfers` suggest/candidates, stats) excludes any
transaction that has parts (`(not [?p :transaction/split-parent ?e])`, centralized).
Parts sum exactly to the parent (validated in `set-splits!`), and they inherit the
parent's posted-date, so **every account/month/statement-span total is unchanged to the
cent** — reconciliation, coverage-strict closing, and statement spans need no split
awareness at all (`ledger/account-computed-deltas` loses its "ignore splits" caveat).

**Immutability / append-only.** The parent's imported datoms are never touched. Parts
are additive user-authored entities, like manual rows. Splitting retracts the parent's
`:transaction/reviewed` overlay (same as today — re-review after un-split); its category
overlay is kept so un-splitting restores it.

**Resync survival.**
- Parent re-imported / `modified`: upsert by external-id asserts only present keys —
  parts and the split-parent refs are untouched.
- Parent **amount** changed by the provider: parts no longer sum to it → surfaced as a
  derived drift flag on each part row + in the editor ("split no longer adds up —
  imported amount changed"), never silently corrected. (Replaces `:transaction/splits-balanced`.)
- Parent **date/posted-date/payee/account** changed: these are *copies of imported
  facts*, not user data — the persist path re-propagates them to parts after insert
  (explicit, idempotent step in `provider.sync/persist-transactions!`). This keeps
  reconciliation bucketing correct when a posted-date shifts.
- Parent **removed** (Plaid removed → `retract-removed!`): parts cascade — unlink any
  transfer pairs (retract the partner's back-ref), purge the command log
  (`commands/forget!`), retract the parts. Same cascade in `delete-manual!` for a split
  manual parent.
- Providers can never write the new attrs: add `:transaction/split-parent` /
  `:transaction/split-order` to `provider.contract/overlay-keys`.

**Editor edits don't nuke part state.** `set-splits!` moves from full-replace to
**diff-by-id**: editor rows carry an optional `:id`; rows with an id update the existing
part (amount/category/memo/order) preserving its reviewed flag, transfer link, and
external-id; id-less rows create; existing parts missing from the payload are retracted
(with transfer unlink + `forget!`). Undo (`:set-splits` before/after value vectors, now
including ids) restores prior values; a part removed-and-undone comes back as a fresh
entity (its own overlays are gone — acceptable, same as today).

**Depth is 1.** `set-splits!` on a row that has `:transaction/split-parent` →
`:bad-request` ("edit the original transaction's split instead"); the UI routes any
part's "Edit split" to the parent's editor.

**A matched parent can't be split.** Splitting a transfer-paired parent would hide a
matched leg. Block with a clear error ("Unmatch this transfer before splitting");
the user re-matches the relevant part afterwards. Part-to-part transfer matching then
works with zero new machinery (parts are normal legs in `suggest-matches`, fixing gap 7 —
and cross-account split-to-split matches come free).

**Uncategorized parts are allowed.** `validate-splits` (and the editor's `canConfirm`)
drop the category requirement; the Uncategorized chip/rollup bucket owns them, like any
row. Rules kept: ≥ 2 parts, non-zero amounts, exact bigdec sum.

### UI

- A part renders as a **normal row** (own date, account, payee, description=memo,
  amount, category, reviewed checkbox, actions) plus a small split marker (⑂-style icon
  by the payee/date) with tooltip "part of <payee> · <parent amount>"; clicking the
  marker (or row-actions → "Edit split") opens the split editor on the parent.
  `split-parent-row` / `split-child-row` and the `is-split-parent` CSS are deleted;
  rows of one family are no longer glued together — they sort/filter/paginate
  independently (they share a date, so date-sort clusters them naturally).
- Grid-nav loses `RowKind`/`splitId`: every row is a plain navigable row (fixes gap 5).
- The split editor keeps its job as the **only place amounts change** (conservation is
  enforced in one place). It seeds from the part transactions (id, magnitude, category
  optional, memo) and posts rows with ids. Category/memo/reviewed remain editable
  inline in the table like any row.
- Counts/pagination now count parts as rows — which is exactly what makes the rollup,
  the category filter, and the table tie out (fixes gaps 1, 4, 6).

### Migration

One idempotent pass (startup or `bb` task): for every transaction with
`:transaction/splits`, create part transactions from the sub-entities
(memo → description, order → split-order, reviewed/category carried over; account/user/
dates/payee copied from the parent), then `retractEntity` each sub-entity. Runs only
over parents that still have old-style splits, so re-running is a no-op. In-memory
undo log doesn't survive restarts, so no command migration.

## Implementation phases (each a logical commit, TDD)

1. **Schema + pure domain.** New attrs; `splits/validate-splits` drops the category
   requirement; `provider.contract/overlay-keys` gains the split attrs.
2. **DB layer.** `set-splits!` creates/diffs part transactions (by-id); read-layer
   parent exclusion (one shared query clause/helper) + part annotation (pulled
   `:transaction/split-parent` summary + drift flag); `current-splits`/`split-editor-seed`
   carry ids; migration fn + test.
3. **Lifecycle integration.** `retract-removed!` cascade, `delete-manual!` cascade,
   inherited-field propagation in the persist path, matched-parent split guard.
4. **View engine simplification.** Delete split branches from `search-haystack`,
   `tx-category-ids`, `needs-category?`, `with-reviewed`, `with-split-balance`,
   `category-rollup`; update `data.ledger` docstrings. Tests shrink accordingly.
5. **Rendering + islands.** Normal-row-with-marker; delete split parent/child row fns;
   simplify `gridNavigation.ts`; split-editor island: optional category, id round-trip.
6. **Transfers.** Parent exclusion in `db.transfers` queries; parts flow through
   suggest/match/candidates untouched.
7. **E2E + docs.** Rewrite `v2-split.ts`; touch `v2-grid`/`v2-rollup`/`v2-review`;
   update architecture doc's split section.

## Open questions (defaults chosen, revisit if they chafe)

- Should editing a part's **amount** clear that part's reviewed flag? Default: no —
  the editor is explicit and the user is looking right at it.
- Marker affordance/placement (date cell vs payee cell) — decide in the design pass of
  phase 5.

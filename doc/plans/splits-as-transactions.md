# Splits as transactions (design notes)

**Date:** 2026-06-14
**Status:** Exploration — no decision made

## Problem

Split parts are currently modeled as child objects (`:transaction/splits`) hanging
off an immutable, imported parent transaction. Every transaction-level behavior in
the UI operates at the **parent grain** and does not descend into the parts, so each
behavior has to be re-derived (often incompletely) for splits — or is simply missing.
The result is a steady trickle of "splits don't do X the way a normal transaction
does" papercuts.

These surfaced while building the category rollup pane, but they are independent of
that feature.

## Concrete gaps found

1. **Transfer badge / matching is parent-only.** Transfer-status rendering reads
   `transaction/transfer-pair` and the parent's `transaction/category` type. The split
   child-cell rendering only shows the split's category name — a split part categorized
   as a transfer gets no badge and no way to match a counterpart.

2. **Reviewed filter can't see partially-reviewed splits.** The reviewed filter keys off
   a single per-transaction `transaction/reviewed` flag, which the server derives as
   "every split reviewed." So a transaction with one reviewed part and one unreviewed
   part reads as a single "unreviewed" row; filtering to "reviewed" hides the whole
   transaction, including the already-reviewed part. Parts are not independently
   filterable because they render hanging off the parent row.

3. **Keyboard navigation doesn't reach split rows.** (User-reported.) Nav is built
   around transaction rows; the child part rows aren't first-class focus targets.

4. **Category filter vs. split attribution don't reconcile.** The category filter
   matches the parent's top-level `transaction/category` only, but the category rollup
   attributes each split *part* to its own category. Clicking a rollup row whose dollars
   came from split parts therefore may not surface those transactions in the table, and
   the row total won't tie out with the filtered set.

## Root cause

Splits are an additive overlay on an immutable parent (see ADRs / the append-only
model). All the "magic that just works" for transactions — transfer matching, the
reviewed filter, keyboard nav, category click-to-filter — keys off transaction-grain
fields and never recurses into parts. Each new transaction feature silently inherits
this blind spot for splits.

## Proposed direction

Promote split parts to **real transactions** (a transaction rendered with a small
"split" icon in the table) instead of child objects. They would then inherit transfer
matching, the reviewed filter, keyboard nav, and category filtering for free, and new
transaction features would cover them automatically.

### Why it's attractive
- One grain to reason about. No more per-feature "…but also handle splits" branch.
- Filtering, counts, and the rollup reconcile by construction (parts are rows).

### Tradeoffs / what makes it non-trivial
This collides with the immutability invariant: imported transactions are the
source-of-truth and are never mutated, whereas split parts are user-authored. To make
parts first-class we'd need to:
- Mark synthetic / user-created rows distinctly from imported ones.
- Keep parts tied to, and balanced against, the originating import.
- Decide how the original behaves in totals once split (net to zero / hidden /
  replaced by its parts).
- Survive re-sync of the parent without orphaning or duplicating user-authored parts.

### Open questions
- A split-transfer part has no separate imported counterpart to match against — but
  there may be a corresponding split on the *other* account's transaction. Matching
  split-to-split is plausible but is net-new machinery. Tentatively acceptable for a
  first cut if a part-transfer simply doesn't get the badge.

## Recommendation

Treat this as its own design spike before any code — not folded into the rollup work.
When touching split handling in the meantime, prefer fixes that move toward
splits-as-transactions over band-aids on the parent/child model.

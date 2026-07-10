# Feature requests
- More flexible date filter/navigaiton
  - Clicking what is currently the month/year should open a date picker with a good range select UX and filter to that arbitrarily, with quick links for recent months. Float some designs first to sanity check the UI direction.
- Automated category assignment with review/commit
- Show account balances, flag if it's different than the reconciled total
- Magic date inputs, e.g. type "April 5", and have the system know that means "2026-04-05"
- localization
  - dates, currency, and language
- Setup UI for reordering categories (drag to set :category/sort-order — every surface now
  respects the stored order, but nothing in the current stack lets you *edit* it; the values
  predate the React frontend's removal)

# Done — July 2026 polish batch (branch ui-polish)

## Transactions table
- ✅ category dropdown looks the same (minus the checkboxes) for header filter and category assignment — funnel renders the combobox's hierarchy/order/skin
- ✅ sorted by date by default (blank sortCol = date asc, shown on the header)
- ✅ filters/sorts persist across month change (month navigator swaps only `month` in the URL)
- ✅ badge counts update when filtered by statement period (lens swaps :counts with :result)
- ✅ "Clear all" button on the chips row (filters + lens; never sort or scope)
- ✅ two-level sort: clicking a second column demotes the first to a tie-break (payee-then-date = date-ordered, ties by payee)

### Add transactions modal
- ✅ account uses combobox search (flat-list mode of the shared Zag island)
- ✅ category uses the same combobox as everywhere else
- ✅ field spacing fixed (shared .form-fields stack rhythm across all form modals)
- ✅ typing in a date no longer clears the field (date inputs are one-way; an invalid date surfaces the error bar instead of a 500)

## Transfers
- ✅ matching copies a lone category onto the uncategorized leg (one undo reverses link + copy)
- ✅ match modal shows the transaction being matched above its candidates
- ✅ review modal rows go stale in place when confirmed/rejected instead of disappearing
- ✅ suggestions scoped to the current date filter (viewed month, or the statement lens)

### Splits
- ✅ split marker is a recognizable branching-path icon, accent-colored at rest
- ✅ typing on a split row's category button opens the dropdown (seeded by the keystroke)

## Reconciliation
- ✅ statement modal spacing fixed; one form design system (.form-fields + shared tokens) across all modals
- ✅ (also landed: a statement's printed start day now counts inside its own period — adjacent statements tile without a coverage gap)

## Setup
- ✅ user category order respected everywhere categories render (funnel, combobox, rollup, modal picker)
- ✅ per-account Lunchflow sync (scoped resync; per-account backfill window)
- ✅ custom account names used throughout the UI (:account/display-name overlay; provider name stays canonical for syncing, rename from /setup)

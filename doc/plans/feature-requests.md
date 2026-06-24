# Feature requests
- Month filter
- Add account column to transactions table
- Automated category assignment
- Transaction "reviewed" attribute
- "Edit mode" with review/commit ability when running automations
- localization
  - dates, currency, and language

## Transactions table
- search
- undo
- toggling "uncategorized" shouldn't change column widths
- category dropdown should look the same (minus the checkboxes) for header filter and category assignment

### Filters
- getting to the point where we really need proper, first-class real filtering
- how do we do this sanely?

## Transfers
- When one side of a transfer is matched, the other should be auto-given the same category (if it doesn't already have one)

### Transfer modal
- show transaction date

### Review transfers
- confirmed rows should look stale, not disappear


## Splits
- closing a split refreshes the table, doesn't leave them as pending uncategorized rows, that would be better
- reviewed splits don't look "stale"
- should probably just be actual transactions
  - we should distinguish them from imported ones
  - but they're just weird in every way (staleness, sorting, categorizing). all this will come for free if they're just transactions with a "split" type. this also lays the groundwork for other "types" of transactions, I think we'll need manually-input ones in the future, and/or bulk imports

### Split modal
- start typing should open the category dropdown here, too

## Setup

### Categories
- preserve user-specified order (in dropdown, rollup, and setup)



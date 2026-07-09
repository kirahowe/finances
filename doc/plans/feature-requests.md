# Feature requests
- More flexible date filter/navigaiton
  - Clicking what is currently the month/year should open a date picker with a good range select UX and filter to that arbitrarily, with quick links for recent months. Float some designs first to sanity check the UI direction.
- Automated category assignment with review/commit
- Show account balances, flag if it's different than the reconciled total
- Magic date inputs, e.g. type "April 5", and have the system know that means "2026-04-05"
- localization
  - dates, currency, and language

# ToDo

## Transactions table
- category dropdown should look the same (minus the checkboxes) for header filter and category assignment
- should be sorted by date by default
- filters/sorts etc should persist across month change
- badge counts don't update when filtered by statement period
- need a "clear all" button to remove all filters at once
- can we allow multi-column sort? not necessarily tracking multiple columns, but if I e.g. sort by "Payee", and then by "Date", ideally I'd see transactions sorted by date and within each day sorted by Payee

### Add transactions modal
- account should use combobox search
- category dropdown should use combobox search like elsewhere
- spacing is all weird between fields
- typing in a month clears the date field, very annoying

## Transfers
- When one side of a transfer is matched, the other should be auto-given the same category (if it doesn't already have one)

### Match transfer modal
- show the transaction being matched (in addition to the candidates)

### Review transfers modal
- confirmed rows should look stale, not disappear from under you
- transfer candidates should be filtered to the current date filter

### Split modal
- start typing should open the category dropdown here, too

## Reconciliatoin
- add statement modal spacing is off. In general the spacing across all forms is inconsistent. Audit all forms and inputs and make a consistent design system for these that they all share.

## Setup

### Categories
- preserve user-specified order (from setup, use in combobox, rollup, anywhere else categories show up)

### Accounts
- sync individual lunchflow accounts, not just all of them at once
- add a custom name for accounts that is used throughout the UI rather than the one that comes from the providers. Be sure not to mess up syncing.


// Real-Chromium proof of manual transactions: a transaction the bank feed didn't import
// becomes a first-class ledger row via the Add-transaction modal (the account picker makes
// the target explicit; the money-out/-in toggle derives the canonical sign, out → negative,
// in → positive), and a manual row can be deleted (with a confirm step) from its row-actions
// menu — while imported rows expose no Delete.
//
// The Account and Category fields are combobox TRIGGERS (.form-combo-trigger) opening the
// shared Zag typeahead — accounts in the flat-list mode, categories in the grouped category
// mode — proven here by keyboard (focus trigger → Enter → type → Enter) and by pointer.
// Also regression-proves the date fix: TYPING a date via keyboard segments must not clear
// the field (the old two-way data-bind wrote the transitional "" back into the input,
// wiping segment state — see transactions_view.clj's one-way date inputs).
//
//   BASE_URL=http://localhost:8099 node e2e/v2-add-transaction.ts
import { chromium, type Locator } from '@playwright/test';

const BASE = process.env.BASE_URL || 'http://localhost:8099';
const results: { name: string; ok: boolean; detail: string }[] = [];
const check = (name: string, ok: unknown, detail: unknown = ''): void => {
  results.push({ name, ok: !!ok, detail: detail == null ? '' : String(detail) });
};

const browser = await chromium.launch();
const page = await browser.newPage();
const logs: string[] = [];
page.on('pageerror', (e) => logs.push('PAGEERROR: ' + e.message));

const modal = page.locator('#modal-root [role="dialog"]');
const search = page.locator('.table-search-input');
const modalClosed = () =>
  page.waitForFunction(() => document.querySelectorAll('#modal-root [role="dialog"]').length === 0,
    null, { timeout: 5000 }).catch(() => {});

// Filter the table to a unique payee and return its single row.
const rowByPayee = async (payee: string): Promise<Locator> => {
  await search.fill(payee);
  await page.waitForFunction((p) => {
    const trs = [...document.querySelectorAll('#tx-tbody tr')];
    return trs.length === 1 && (trs[0].textContent || '').includes(p);
  }, payee, { timeout: 5000 }).catch(() => {});
  return page.locator('#tx-tbody tr').first();
};

const addTxn = async (payee: string, amount: string, dir: 'out' | 'in'): Promise<void> => {
  await page.getByRole('button', { name: 'Add transaction' }).click();
  await modal.waitFor({ state: 'visible', timeout: 5000 });
  if (dir === 'in') await modal.getByRole('radio', { name: 'Money in' }).click();
  await page.locator('#tx-amount').fill(amount);
  await page.locator('#tx-payee').fill(payee);
  await modal.locator('.button-primary').click();
  await modalClosed();
};

await page.request.post(`${BASE}/e2e/reset`);
await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(200);
check('no page errors', !logs.length, logs.join('; '));

// 1. The modal opens from the toolbar with the account combobox trigger + a sensible date
//    default, and Save is gated until the required fields are set.
await page.getByRole('button', { name: 'Add transaction' }).click();
await modal.waitFor({ state: 'visible', timeout: 5000 });
check('add-transaction modal opens', (await modal.count()) === 1);
const accountTrigger = page.locator('#tx-account');
const categoryTrigger = page.locator('#tx-category');
const dropdown = page.locator('.category-dropdown.is-floating');
check('account trigger shows the preselected account (target account is explicit)',
  (await accountTrigger.locator('.form-combo-label').innerText()).trim() === 'Chequing');
check('category trigger reads Uncategorized',
  (await categoryTrigger.locator('.form-combo-label').innerText()).trim() === 'Uncategorized');
check('date defaults into the viewed month',
  (await page.locator('#tx-date').inputValue()).startsWith('2025-01'));
check('Save disabled until required fields set', await modal.locator('.button-primary').isDisabled());

// 1a. Account picked ENTIRELY by keyboard: the focus trap lands on the trigger, Enter opens
//     the flat account list, typing filters it, Enter commits the highlighted match.
await page.waitForFunction(() => document.activeElement?.id === 'tx-account', null, { timeout: 5000 })
  .catch(() => {});
check('focus lands on the account trigger when the modal opens',
  await page.evaluate(() => document.activeElement?.id === 'tx-account'));
await page.keyboard.press('Enter');
await dropdown.waitFor({ state: 'visible', timeout: 5000 });
check('Enter on the trigger opens the floating combobox', (await dropdown.count()) === 1);
await page.locator('.category-dropdown-input').type('sav');
check('typing filters the flat account list',
  (await page.locator('.category-dropdown-item').allInnerTexts()).join(',') === 'Savings');
await page.keyboard.press('Enter');
await dropdown.waitFor({ state: 'hidden', timeout: 5000 }).catch(() => {});
check('keyboard commit updates the trigger label optimistically',
  (await accountTrigger.locator('.form-combo-label').innerText()).trim() === 'Savings');
check('focus returns to the trigger after the combobox closes',
  await page.evaluate(() => document.activeElement?.id === 'tx-account'));
check('the modal stayed open through the combobox round-trip', (await modal.count()) === 1);

// 1b. Category picked through the same combobox (pointer path): click the trigger, type,
//     click the match. The floating input must overlay the trigger border-for-border —
//     regression: it used to open in the grid cell's smaller box (`.is-form-field` was
//     missing) and the field visibly jumped.
const triggerBox = await categoryTrigger.boundingBox();
await categoryTrigger.click();
await dropdown.waitFor({ state: 'visible', timeout: 5000 });
const inputBox = await page.locator('.category-dropdown-input').boundingBox();
const off = (a?: number, b?: number) => Math.abs((a ?? 0) - (b ?? 0));
check('floating input overlays the trigger exactly (no jump on open)',
  triggerBox && inputBox &&
    off(triggerBox.x, inputBox.x) <= 1 && off(triggerBox.y, inputBox.y) <= 1 &&
    off(triggerBox.width, inputBox.width) <= 1 && off(triggerBox.height, inputBox.height) <= 1,
  `trigger=${JSON.stringify(triggerBox)} input=${JSON.stringify(inputBox)}`);
await page.locator('.category-dropdown-input').type('groc');
await page.locator('.category-dropdown-item', { hasText: /^Groceries$/ }).first().click();
await dropdown.waitFor({ state: 'hidden', timeout: 5000 }).catch(() => {});
check('category pick updates the trigger label',
  (await categoryTrigger.locator('.form-combo-label').innerText()).trim() === 'Groceries');

// 1b'. Type-to-open: a printable keystroke on a FOCUSED trigger opens the combobox
//      pre-filtered by it — no click/Enter first (the pointer commit above hands focus
//      back to the trigger). Escape closes it again without committing.
await page.keyboard.press('g');
await dropdown.waitFor({ state: 'visible', timeout: 5000 });
check('typing on a focused trigger opens the combobox pre-filtered by the keystroke',
  (await page.locator('.category-dropdown-input').inputValue()) === 'g');
check('the pre-filtered list highlights the best match',
  (await page.locator('.category-dropdown-item.highlighted').count()) === 1);
await page.keyboard.press('Escape');
await dropdown.waitFor({ state: 'hidden', timeout: 5000 }).catch(() => {});
check('Escape closes the typed-open combobox without committing',
  (await categoryTrigger.locator('.form-combo-label').innerText()).trim() === 'Groceries');

// 1c. REGRESSION (date one-way fix): typing digits into the date's keyboard segments must
//     leave a complete retained value — under the old two-way data-bind the first keystroke's
//     transitional "" was written back into the input, wiping it. The typed digits' MEANING is
//     locale-dependent (Chromium's segment order follows the OS region, not a Playwright
//     setting — en-CA machines read YYYY-MM-DD), so assert completeness rather than one exact
//     date, then fill() the precise date the ledger assertions below depend on.
await page.locator('#tx-date').focus();
await page.keyboard.type('01152025', { delay: 30 });
const typedDate = await page.locator('#tx-date').inputValue();
check('a keyboard-typed date is retained as a complete value (old bug: wiped to "")',
  /^\d{4,6}-\d{2}-\d{2}$/.test(typedDate), `got ${JSON.stringify(typedDate)}`);
await page.locator('#tx-date').fill('2025-01-15');

await page.locator('#tx-amount').fill('12.34');
await page.locator('#tx-payee').fill('ZZ Manual Latte');
check('Save enabled once account + amount + date are set',
  !(await modal.locator('.button-primary').isDisabled()));
await modal.locator('.button-primary').click();
await modalClosed();
check('modal closes after save', (await page.locator('#modal-root [role="dialog"]').count()) === 0);

// 2. The money-out transaction is a ledger row with a negative amount, carrying the
//    combobox-picked account + category and the keyboard-typed date.
const outRow = await rowByPayee('ZZ Manual Latte');
check('money-out transaction appears in the ledger',
  (await outRow.locator('td', { hasText: 'ZZ Manual Latte' }).count()) >= 1);
check('money-out is stored as a negative amount',
  (await outRow.locator('.amount-cell .numeric').getAttribute('class'))?.includes('negative'));
check('amount shows the entered magnitude', /12\.34/.test(await outRow.locator('.amount-cell').innerText()));
const outRowText = await outRow.innerText();
check('the row landed on the combobox-picked account', /Savings/.test(outRowText));
check('the row carries the combobox-picked category', /Groceries/.test(outRowText));
check('the row carries the picked date', /Jan 15/.test(outRowText));

// 3. A money-in transaction stores a positive amount (the toggle derives the sign).
await addTxn('ZZ Manual Refund', '50.00', 'in');
const inRow = await rowByPayee('ZZ Manual Refund');
check('money-in is stored as a positive amount',
  (await inRow.locator('.amount-cell .numeric').getAttribute('class'))?.includes('positive'));

// 4. Imported (non-manual) rows do NOT expose a Delete action.
await search.fill('');
await page.waitForTimeout(400);
const deleteItem = page.locator('#row-actions-menu .row-actions-item.is-danger');
await page.locator('#tx-tbody tr').first().locator('.row-actions-trigger').click();
check('imported row menu hides Delete', !(await deleteItem.isVisible()));
await page.keyboard.press('Escape');

// 5. A manual row can be deleted (with a confirm step) from its row-actions menu.
const delRow = await rowByPayee('ZZ Manual Latte');
await delRow.locator('.row-actions-trigger').click();
check('manual row menu exposes Delete', await deleteItem.isVisible());
await deleteItem.click();
await modal.waitFor({ state: 'visible', timeout: 5000 });
check('delete confirm dialog opens', /Delete transaction\?/.test(await modal.innerText()));
await modal.locator('.button-danger').click();
await modalClosed();
await search.fill('ZZ Manual Latte');
await page.waitForFunction(() => {
  const trs = [...document.querySelectorAll('#tx-tbody tr')];
  return !trs.some((r) => (r.textContent || '').includes('ZZ Manual Latte'));
}, null, { timeout: 5000 }).catch(() => {});
check('deleted manual transaction is gone from the ledger',
  (await page.locator('#tx-tbody tr', { hasText: 'ZZ Manual Latte' }).count()) === 0);

check('still no page errors', !logs.length, logs.join('; '));

await browser.close();
await fetch(`${BASE}/e2e/reset`, { method: 'POST' }).catch(() => {});

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

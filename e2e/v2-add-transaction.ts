// Real-Chromium proof of manual transactions: a transaction the bank feed didn't import
// becomes a first-class ledger row via the Add-transaction modal (the account picker makes
// the target explicit; the money-out/-in toggle derives the canonical sign, out → negative,
// in → positive), and a manual row can be deleted (with a confirm step) from its row-actions
// menu — while imported rows expose no Delete.
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

// 1. The modal opens from the toolbar with the account picker + a sensible date default,
//    and Save is gated until the required fields are set.
await page.getByRole('button', { name: 'Add transaction' }).click();
await modal.waitFor({ state: 'visible', timeout: 5000 });
check('add-transaction modal opens', (await modal.count()) === 1);
check('modal shows the account picker (target account is explicit)',
  (await page.locator('#tx-account option').count()) >= 1);
check('date defaults into the viewed month',
  (await page.locator('#tx-date').inputValue()).startsWith('2025-01'));
check('Save disabled until required fields set', await modal.locator('.button-primary').isDisabled());
await page.locator('#tx-amount').fill('12.34');
await page.locator('#tx-payee').fill('ZZ Manual Latte');
check('Save enabled once account + amount + date are set',
  !(await modal.locator('.button-primary').isDisabled()));
await modal.locator('.button-primary').click();
await modalClosed();
check('modal closes after save', (await page.locator('#modal-root [role="dialog"]').count()) === 0);

// 2. The money-out transaction is a ledger row with a negative amount.
const outRow = await rowByPayee('ZZ Manual Latte');
check('money-out transaction appears in the ledger',
  (await outRow.locator('td', { hasText: 'ZZ Manual Latte' }).count()) >= 1);
check('money-out is stored as a negative amount',
  (await outRow.locator('.amount-cell .numeric').getAttribute('class'))?.includes('negative'));
check('amount shows the entered magnitude', /12\.34/.test(await outRow.locator('.amount-cell').innerText()));

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

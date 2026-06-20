// Browser-driven verification of client-side column sorting (Phase 3d-3b): clicking a
// header reorders rows asc -> desc -> cleared, updates the indicator/aria-sort, keeps
// Datastar filter state intact, and refreshes grid-nav's navigation order.
//   BASE_URL=http://localhost:8099 node e2e/sort.mjs
import { createRequire } from 'module';
import { fileURLToPath } from 'url';
import { dirname, resolve } from 'path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(resolve(root, 'frontend') + '/');
const { chromium } = require('@playwright/test');

const BASE = process.env.BASE_URL || 'http://localhost:8099';
const URL = `${BASE}/?month=2025-01`;
const results = [];
const check = (name, ok, detail = '') => results.push({ name, ok: !!ok, detail });
const isSorted = (xs, dir = 1) => xs.every((v, i) => i === 0 || (dir > 0 ? xs[i - 1] <= v : xs[i - 1] >= v));

const browser = await chromium.launch();
const page = await browser.newPage();
const logs = [];
page.on('pageerror', (e) => logs.push('PAGEERROR: ' + e.message));

await page.goto(URL, { waitUntil: 'networkidle' });

const header = (col) => page.locator(`th[data-sort-col="${col}"]`);
const amounts = () => page.$$eval('table tbody tr[data-sort-amount]', (els) => els.map((e) => parseFloat(e.dataset.sortAmount)));
const payees = () => page.$$eval('table tbody tr[data-sort-date] td:nth-child(4)', (els) => els.map((e) => e.textContent.trim().toLowerCase()));

// 1. Sortable headers exist; non-sortable ones don't.
check('6 sortable headers', (await page.locator('th[data-sort-col]').count()) === 6, `${await page.locator('th[data-sort-col]').count()}`);
check('Reviewed header is not sortable', (await page.locator('th[data-sort-col="reviewed"]').count()) === 0);

const original = await amounts();

// 2. Click Amount -> ascending.
await header('amount').click();
await page.waitForTimeout(60);
check('Amount asc sorts ascending', isSorted(await amounts(), 1), JSON.stringify(await amounts()));
check('Amount header shows asc indicator', (await header('amount').getAttribute('aria-sort')) === 'ascending');

// 3. Click again -> descending.
await header('amount').click();
await page.waitForTimeout(60);
check('Amount desc sorts descending', isSorted(await amounts(), -1));
check('Amount header shows desc indicator', (await header('amount').getAttribute('aria-sort')) === 'descending');

// 4. Click again -> cleared (original order restored).
await header('amount').click();
await page.waitForTimeout(60);
check('third click clears the sort', JSON.stringify(await amounts()) === JSON.stringify(original));
check('Amount header aria-sort none', (await header('amount').getAttribute('aria-sort')) === 'none');

// 5. Sorting by a string column (payee) sorts alphabetically.
await header('payee').click();
await page.waitForTimeout(60);
check('Payee asc sorts alphabetically', isSorted(await payees(), 1), JSON.stringify(await payees()));

// 6. Filters are preserved across a sort (search signal still applies).
await header('amount').click(); // sort asc
await page.waitForTimeout(60);
await page.locator('.table-search-input').fill('Superstore');
await page.waitForTimeout(60);
const visibleRows = await page.locator('table tbody tr:visible').count();
check('search filter still works after sorting', visibleRows === 1, `${visibleRows} visible`);
await page.locator('.table-search-input').fill('');
await page.waitForTimeout(60);

// 7. grid-nav follows the new order: focusing the grid lands on the first sorted row.
await header('amount').click(); // ensure a known sort (asc)
await page.waitForTimeout(60);
const firstTx = await page.$eval('table tbody tr:first-child [data-cell]', (el) => el.dataset.cell.split(':')[0]);
await page.locator('.transactions-table-scroll').focus();
await page.waitForTimeout(60);
const activeTx = await page.evaluate(() => String(window.__gridState?.()?.active?.key?.txId));
check('grid-nav first cell matches the sorted first row', activeTx === firstTx, `${activeTx} vs ${firstTx}`);

check('no page errors', !logs.length, logs.join('; '));

await browser.close();

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

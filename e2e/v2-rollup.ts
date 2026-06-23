// Real-Chromium proof of the category rollup pane: a whole-month breakdown beside the table
// (sections + Net), where clicking a category row filters the table to it (and toggles off),
// reusing the funnel filter signals; the clicked row highlights and an active-filter chip
// appears. The whole-month amounts are computed server-side (web.view/category-rollup, tested).
//
//   BASE_URL=http://localhost:8100 node e2e/v2-rollup.ts
import { chromium } from '@playwright/test';

const BASE = process.env.BASE_URL || 'http://localhost:8100';
const results: { name: string; ok: boolean; detail: string }[] = [];
const check = (name: string, ok: unknown, detail: unknown = ''): void => {
  results.push({ name, ok: !!ok, detail: detail == null ? '' : String(detail) });
};

const browser = await chromium.launch();
const page = await browser.newPage();
const logs: string[] = [];
page.on('pageerror', (e) => logs.push('PAGEERROR: ' + e.message));

await page.request.post(`${BASE}/e2e/reset`);
await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
check('no page errors', !logs.length, logs.join('; '));

const rollup = page.locator('#category-rollup');
const rows = () => page.locator('#tx-tbody tr');

check('rollup pane renders beside the table', await rollup.isVisible());
check('has an Income section', (await rollup.locator('.rollup-section-title', { hasText: 'Income' }).count()) === 1);
check('has an Expenses section', (await rollup.locator('.rollup-section-title', { hasText: 'Expenses' }).count()) === 1);
check('has a Net line', (await rollup.locator('.rollup-net .rollup-amount').count()) === 1);

// Groceries is a childless expense leaf — the seed's one Groceries tx is Superstore -$85.
const groceries = rollup.locator('.rollup-row', { hasText: 'Groceries' }).first();
check('Groceries row shows $85.00',
  (await groceries.locator('.rollup-amount').innerText()).includes('85.00'));

// Click it → the table filters to Groceries (just Superstore), the row highlights, a chip appears.
const before = await rows().count();
check('the month starts with more than one row', before > 1, `rows=${before}`);
await groceries.locator('.rollup-row-button').click();
await page.waitForFunction((b) => document.querySelectorAll('#tx-tbody tr').length < b, before, { timeout: 5000 }).catch(() => {});
check('clicking Groceries filters the table to one row', (await rows().count()) === 1, `rows=${await rows().count()}`);
check('the one row is Superstore', (await rows().filter({ hasText: 'Superstore' }).count()) === 1);
check('the Groceries row is now active', await groceries.evaluate((el) => el.classList.contains('is-active')));
check('a Category filter chip appears',
  (await page.locator('#active-filters .active-chip', { hasText: 'Groceries' }).count()) === 1);

// Click again → toggles the filter off, the table returns.
await groceries.locator('.rollup-row-button').click();
await page.waitForFunction((b) => document.querySelectorAll('#tx-tbody tr').length >= b, before, { timeout: 5000 }).catch(() => {});
check('clicking Groceries again clears the filter', (await rows().count()) === before, `rows=${await rows().count()}`);
check('the Groceries row is no longer active', !(await groceries.evaluate((el) => el.classList.contains('is-active'))));

check('still no page errors', !logs.length, logs.join('; '));

await browser.close();

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

// Real-Chromium proof for the Phase-R2 server-authoritative page (/v2). Every view
// change round-trips: search / sort fire @get('/v2/rows'), the server runs the pure view
// engine, and the tbody + pagination bar morph back. Proves the interaction model end to
// end (escaped Datastar expressions firing + SSE morph), not just the fragment shape.
//
//   BASE_URL=http://localhost:8099 node e2e/v2.mjs
import { createRequire } from 'module';
import { fileURLToPath } from 'url';
import { dirname, resolve } from 'path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(resolve(root, 'frontend') + '/');
const { chromium } = require('@playwright/test');

const BASE = process.env.BASE_URL || 'http://localhost:8099';
const results = [];
const check = (name, ok, detail = '') => results.push({ name, ok: !!ok, detail });

const browser = await chromium.launch();
const page = await browser.newPage();
const logs = [];
page.on('console', (m) => logs.push(m.text()));
page.on('pageerror', (e) => logs.push('PAGEERROR: ' + e.message));

const rowCount = () => page.locator('#tx-tbody tr').count();
const firstAmount = () =>
  page.locator('#tx-tbody tr').first().locator('.amount-cell .numeric').innerText();

await page.goto(`${BASE}/v2?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);

check('no page errors', !logs.some((l) => l.startsWith('PAGEERROR')),
  logs.filter((l) => l.startsWith('PAGEERROR')).join('; '));
check('initial render: 10 rows', (await rowCount()) === 10, `rows=${await rowCount()}`);
check('pagination status rendered', (await page.locator('#pagination-bar .pagination-status').innerText()).includes('Page 1 of 1'));

// Search → server filters → morph (debounced 300ms).
await page.locator('.table-search-input').fill('Superstore');
await page.waitForFunction(() => document.querySelectorAll('#tx-tbody tr').length === 1, null, { timeout: 5000 }).catch(() => {});
check('server-side search → 1 row', (await rowCount()) === 1, `rows=${await rowCount()}`);

await page.locator('.table-search-input').fill('');
await page.waitForFunction(() => document.querySelectorAll('#tx-tbody tr').length === 10, null, { timeout: 5000 }).catch(() => {});
check('cleared search → 10 rows', (await rowCount()) === 10, `rows=${await rowCount()}`);

// Sort by Amount: asc → most negative first, desc → most positive first.
const amountTh = page.locator('th[data-col-id="amount"]');
await amountTh.click();
await page.waitForFunction(() => document.querySelector('#tx-tbody tr .amount-cell .numeric')?.textContent?.includes('2,000'), null, { timeout: 5000 }).catch(() => {});
check('sort amount asc → first row -$2,000.00', (await firstAmount()).includes('-$2,000.00'), await firstAmount());

await amountTh.click();
await page.waitForFunction(() => document.querySelector('#tx-tbody tr .amount-cell .numeric')?.textContent?.includes('4,000'), null, { timeout: 5000 }).catch(() => {});
check('sort amount desc → first row $4,000.00', (await firstAmount()).includes('$4,000.00'), await firstAmount());

await browser.close();

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

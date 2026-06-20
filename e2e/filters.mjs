// Browser-driven verification of the client-side filter toolbar (Phase 3b):
// search, hide-transfers + uncategorized chips, review-scope toggle. All run as
// instant Datastar data-show with no round-trip.
//   BASE_URL=http://localhost:8099 node e2e/filters.mjs
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
page.on('pageerror', (e) => logs.push('PAGEERROR: ' + e.message));

await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
const visible = () => page.locator('table.table tbody tr:visible').count();
const text = (sel) => page.locator(sel).innerText();

// Counts seeded correctly
check('counts: needs-review 10', (await text('#count-unreviewed')).trim() === '10');
check('counts: total 10', (await text('#count-total')).trim() === '10');
check('counts: uncategorized 6', (await text('#count-uncategorized')).trim() === '6');
check('counts: transfers-hidden 2', (await text('#count-transfers')).trim() === '2');
check('all 10 rows visible initially', (await visible()) === 10, `${await visible()}`);

// Search
await page.locator('.table-search-input').fill('mortgage');
await page.waitForTimeout(100);
check('search "mortgage" → 2 rows', (await visible()) === 2, `${await visible()}`);
await page.locator('.table-search-input').fill('');
await page.waitForTimeout(100);
check('clear search → 10 rows', (await visible()) === 10, `${await visible()}`);

// Hide transfers chip (2 hidden legs → 8 visible)
const hideBtn = page.locator('.count-chip', { hasText: 'Hide transfers' });
await hideBtn.click();
await page.waitForTimeout(100);
check('hide transfers → 8 rows', (await visible()) === 8, `${await visible()}`);
check('hide-transfers chip active', (await hideBtn.getAttribute('class')).includes('is-active'));
await hideBtn.click();
await page.waitForTimeout(100);
check('un-hide transfers → 10 rows', (await visible()) === 10, `${await visible()}`);

// Uncategorized chip (6 uncategorized)
const uncatBtn = page.locator('.count-chip', { hasText: 'Uncategorized' });
await uncatBtn.click();
await page.waitForTimeout(100);
check('uncategorized → 6 rows', (await visible()) === 6, `${await visible()}`);
await uncatBtn.click();
await page.waitForTimeout(100);

// Review-scope: needs-review shows all 10 (all unreviewed). Reviewing a row now lets it
// LINGER (stale) in place rather than dropping mid-task — it clears on the next filter
// reset (the full linger cycle is covered by lingering.mjs).
const needsBtn = page.locator('.scope-toggle-btn', { hasText: 'Needs review' });
await needsBtn.click();
await page.waitForTimeout(100);
check('needs-review → 10 (all unreviewed)', (await visible()) === 10, `${await visible()}`);
const acmeRow = page.locator('table.table tbody tr', { hasText: 'Acme Payroll' });
await acmeRow.locator('.reviewed-checkbox').click(); // flip signal only (write-behind not awaited)
await page.waitForTimeout(100);
check('reviewing a row lets it linger (stays, marked stale)',
  (await visible()) === 10 && (await acmeRow.getAttribute('class')).includes('is-stale'),
  `${await visible()}`);

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

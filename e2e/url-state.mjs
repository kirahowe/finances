// Browser-driven verification of URL view-state persistence (Phase 3d): the active view
// (search / scope / chips / funnels / column visibility / sort) is reflected into the query
// string and restored on load, so a reload, a shared link, or month navigation keeps the
// same view ([[feedback_url_view_state]] — URL, never localStorage).
//   BASE_URL=http://localhost:8099 node e2e/url-state.mjs
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

const visible = () => page.locator('table.table tbody tr:visible').count();
const url = () => page.url();
const th = (id) => page.locator(`th[data-col-id="${id}"]`);

await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });

// --- WRITE: interactions reflect into the query string -----------------------------------
await page.locator('.count-chip', { hasText: 'Uncategorized' }).click();
await page.waitForTimeout(80);
check('toggling Uncategorized writes uncat=1', url().includes('uncat=1'), url());

await page.locator('.count-chip', { hasText: 'Hide transfers' }).click();
await page.waitForTimeout(80);
check('toggling Hide-transfers writes ht=1', url().includes('ht=1'), url());

// An account funnel selection.
await page.locator('.th-filter-btn[aria-label="Filter Account"]').click();
await page.waitForTimeout(40);
await page.locator('.header-filter-popover:visible .filter-dropdown-checkbox').first().click();
await page.waitForTimeout(80);
check('selecting an account funnel value writes fa=…', /[?&]fa=\d/.test(url()), url());

// Hide a column.
await page.locator('.column-picker .filter-button').click();
await page.waitForTimeout(40);
await page.locator('.column-picker .filter-dropdown-checkbox-label', { hasText: 'Payee' }).click();
await page.waitForTimeout(80);
check('hiding a column writes hidecols=payee', /[?&]hidecols=[^&]*payee/.test(url()), url());

// Sort a column (sort island owns the `sort` param).
await th('amount').locator('.th-label').click();
await page.waitForTimeout(80);
check('sorting writes sort=amount:asc', decodeURIComponent(url()).includes('sort=amount:asc'), url());

// --- RESTORE: reload the live URL → the whole view comes back ----------------------------
const built = url();
await page.goto(built, { waitUntil: 'networkidle' });
await page.waitForTimeout(120);
check('Uncategorized chip restored active',
  (await page.locator('.count-chip', { hasText: 'Uncategorized' }).getAttribute('class')).includes('is-active'));
check('Hide-transfers chip restored active',
  (await page.locator('.count-chip', { hasText: 'Hide transfers' }).getAttribute('class')).includes('is-active'));
check('Payee column restored hidden', !(await th('payee').isVisible()));
check('Account funnel restored active',
  (await page.locator('.th-filter-btn[aria-label="Filter Account"]').getAttribute('class')).includes('is-active'));
check('sort restored (amount descending arrow / aria-sort)',
  (await th('amount').getAttribute('aria-sort')) === 'ascending', `${await th('amount').getAttribute('aria-sort')}`);

// --- RESTORE from a crafted link ---------------------------------------------------------
await page.goto(`${BASE}/?month=2025-01&scope=needs-review&q=mortgage`, { waitUntil: 'networkidle' });
await page.waitForTimeout(100);
check('scope restored to Needs review',
  (await page.locator('.scope-toggle-btn', { hasText: 'Needs review' }).getAttribute('class')).includes('is-active'));
check('search restored into the box', (await page.locator('.table-search-input').inputValue()) === 'mortgage');
check('crafted filters actually applied (2 mortgage rows)', (await visible()) === 2, `${await visible()}`);

// --- Month navigation preserves the view-state -------------------------------------------
await page.goto(`${BASE}/?month=2025-01&scope=needs-review&uncat=1`, { waitUntil: 'networkidle' });
await page.locator('.month-nav-button', { hasText: '›' }).click();
await page.waitForTimeout(200);
check('next-month nav keeps month change', url().includes('month=2025-02'), url());
check('next-month nav preserves scope + uncat', url().includes('scope=needs-review') && url().includes('uncat=1'), url());

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

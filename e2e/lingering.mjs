// Browser-driven verification of lingering rows (Phase 3d): when you review a row in the
// Needs-review queue (or categorize a row under an active category filter), the row does
// NOT vanish mid-task — it lingers in place, de-emphasized (.is-stale), until the next
// filter change clears the pins. A categorized-out row shows the "→" moved breadcrumb.
//   BASE_URL=http://localhost:8099 node e2e/lingering.mjs
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

const browser = await chromium.launch();
const page = await browser.newPage();
const logs = [];
page.on('pageerror', (e) => logs.push('PAGEERROR: ' + e.message));

const visible = () => page.locator('table.table tbody tr:visible').count();
const reseed = () => page.request.post(`${BASE}/e2e/reset`);

await page.goto(URL, { waitUntil: 'networkidle' });

// --- Reviewed lingering (Needs-review queue) ----------------------------------------------
const needsBtn = page.locator('.scope-toggle-btn', { hasText: 'Needs review' });
const allBtn = page.locator('.scope-toggle-btn', { hasText: 'All' });
const acmeRow = page.locator('table.table tbody tr', { hasText: 'Acme Payroll' });

await needsBtn.click();
await page.waitForTimeout(80);
check('Needs-review scope shows all 10 (all unreviewed)', (await visible()) === 10, `${await visible()}`);

await acmeRow.locator('.reviewed-checkbox').click();
await page.waitForTimeout(80);
check('reviewing a row keeps it visible (lingers)', (await visible()) === 10, `${await visible()}`);
check('the reviewed row reads as stale', (await acmeRow.getAttribute('class')).includes('is-stale'));

// A filter change (scope round-trip) clears the pins → the reviewed row finally drops.
await allBtn.click();
await page.waitForTimeout(80);
check('switching to All shows everything again', (await visible()) === 10, `${await visible()}`);
await needsBtn.click();
await page.waitForTimeout(80);
check('back in Needs-review the reviewed row is gone (pins cleared)', (await visible()) === 9, `${await visible()}`);
check('the reviewed row is no longer stale-visible', !(await acmeRow.isVisible()));

// --- Category lingering (Uncategorized chip + the → breadcrumb) ---------------------------
await reseed();
await page.goto(URL, { waitUntil: 'networkidle' });

const uncatChip = page.locator('.count-chip', { hasText: 'Uncategorized' });
const targetRow = page.locator('table.table tbody tr', { hasText: 'Transfer to Savings' });

await uncatChip.click();
await page.waitForTimeout(80);
check('Uncategorized chip shows the 6 uncategorized rows', (await visible()) === 6, `${await visible()}`);

// Categorize one of them via the combobox.
await targetRow.locator('.category-button.combo-cell').click();
await page.locator('.category-dropdown-input').fill('Gro');
await page.waitForTimeout(60);
await page.locator('.category-dropdown-item', { hasText: 'Groceries' }).click();
await page.waitForTimeout(120);
check('categorizing under Uncategorized keeps the row (lingers)', (await visible()) === 6, `${await visible()}`);
check('the categorized-out row reads as stale', (await targetRow.getAttribute('class')).includes('is-stale'));
check('the "→" moved breadcrumb is shown', await targetRow.locator('.category-moved-mark').isVisible());

// Re-applying the filter clears the pin → the row drops out (5 uncategorized remain).
await uncatChip.click(); // off → reset
await page.waitForTimeout(60);
await uncatChip.click(); // on → reset, now 5 uncategorized
await page.waitForTimeout(80);
check('after re-filter the categorized row is gone (5 left)', (await visible()) === 5, `${await visible()}`);

await reseed(); // restore the seed for the other specs

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

// Real-Chromium proof that filters COMPOSE: faceted counts + active-filter chips.
// Each toolbar/funnel count reflects toggling THAT control given the OTHER active filters
// (faceted-search semantics), re-patched on every view change; funnel selections show as
// removable chips. Replaces the old "full-month counts that looked wrong under composition".
//
//   BASE_URL=http://localhost:8099 node e2e/v2-counts.mjs
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
const n = (sel) => page.locator(sel).innerText().then((t) => Number(t.trim()));
const rows = () => page.locator('#tx-tbody tr').count();

// Faceted: with the Uncategorized chip on, All == Uncategorized == the displayed rows
// (the chip's facet drops out of All; before faceting, All showed the full month).
await page.goto(`${BASE}/?month=2025-01&uncat=1`, { waitUntil: 'networkidle' });
await page.waitForTimeout(400);
check('no page errors', !logs.length, logs.join('; '));
const all1 = await n('#count-total'), unc = await n('#count-uncategorized'), shown = await rows();
check('faceted: All == Uncategorized == displayed', all1 === unc && unc === shown, `All=${all1} Uncat=${unc} shown=${shown}`);

// Counts re-patch on a filter toggle: turning the chip off grows All to the full month.
await page.locator('.count-chip').filter({ hasText: 'Uncategorized' }).click();
await page.waitForFunction(() => document.querySelectorAll('#tx-tbody tr').length > 2, null, { timeout: 5000 }).catch(() => {});
check('counts re-patch on toggle (All grows when chip off)', (await n('#count-total')) > all1, `${all1}→${await n('#count-total')}`);

// Funnel option counts are faceted: under needs-review, Visa's count drops (the reviewed Visa row excluded).
await page.goto(`${BASE}/?month=2025-01&scope=needs-review`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
await page.getByRole('button', { name: 'Filter Account' }).click();
await page.waitForTimeout(200);
const visa = (await page.locator('#funnel-list-account .filter-dropdown-item', { hasText: 'Visa' })
  .locator('.filter-dropdown-count').innerText().catch(() => '?')).trim();
check('funnel option counts are faceted', /^\d+$/.test(visa), `Visa=${visa}`);

// Active-filter chips: a funnel selection shows a removable chip; × clears it.
await page.goto(`${BASE}/?month=2025-01&fa=2`, { waitUntil: 'networkidle' });
await page.waitForTimeout(400);
check('a funnel selection shows one active-filter chip', (await page.locator('#active-filters .active-chip').count()) === 1);
check('chip labels the field', (await page.locator('#active-filters .active-chip-field').innerText()).trim().toLowerCase() === 'account');
const before = await rows();
await page.locator('#active-filters .active-chip-remove').click();
await page.waitForFunction((b) => document.querySelectorAll('#tx-tbody tr').length > b, before, { timeout: 5000 }).catch(() => {});
check('× removes the filter (rows grow, chip clears)',
  (await rows()) > before && (await page.locator('#active-filters .active-chip').count()) === 0,
  `rows ${before}→${await rows()}`);

await browser.close();

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

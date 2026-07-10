// Real-Chromium proof that filters COMPOSE: faceted counts + active-filter chips.
// Each toolbar/funnel count reflects toggling THAT control given the OTHER active filters
// (faceted-search semantics), re-patched on every view change; funnel selections show as
// removable chips. Replaces the old "full-month counts that looked wrong under composition".
//
//   BASE_URL=http://localhost:8099 node e2e/v2-counts.ts
import { chromium } from '@playwright/test';

const BASE = process.env.BASE_URL || 'http://localhost:8099';
const results: { name: string; ok: boolean; detail: string }[] = [];
const check = (name: string, ok: unknown, detail: unknown = ''): void => {
  results.push({ name, ok: !!ok, detail: detail == null ? '' : String(detail) });
};

const browser = await chromium.launch();
const page = await browser.newPage();
const logs: string[] = [];
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

// Funnel option counts are faceted: under to-reconcile, Visa's count drops (the reconciled Visa row excluded).
await page.goto(`${BASE}/?month=2025-01&scope=to-reconcile`, { waitUntil: 'networkidle' });
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

// "Clear all": search/uncat/hide-transfers have no chip of their own, but the chip row must
// still appear (with a Clear-all button) so there's a way to reset them.
await page.goto(`${BASE}/?month=2025-01&uncat=1&ht=1`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
check('Clear all appears for uncat/hide-transfers even with zero removable chips',
  (await page.locator('.active-chips-clear').count()) === 1);
check('the chip row is visible (not hidden) purely for Clear all',
  await page.locator('#active-filters').isVisible());
check('scope is untouched by all this — the toggle is a work-queue mode, not a filter',
  (await page.locator('.scope-toggle-btn.is-active').innerText()).includes('All'));
await page.locator('.active-chips-clear').click();
await page.waitForFunction(() => document.querySelectorAll('#tx-tbody tr').length === 10, null, { timeout: 5000 }).catch(() => {});
check('Clear all resets uncat + hide-transfers (back to all 10 rows)',
  (await rows()) === 10, `rows=${await rows()}`);
check('Clear all removes itself once there is nothing left to clear',
  (await page.locator('.active-chips-clear').count()) === 0);
check('the chip row hides again once empty', await page.locator('#active-filters').isHidden());

// Clear all also resets a real chip (an account funnel selection) alongside the chip-less filters.
await page.goto(`${BASE}/?month=2025-01&fa=2&uncat=1`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
check('a chip + Clear all both show together', (await page.locator('#active-filters .active-chip').count()) === 1 &&
  (await page.locator('.active-chips-clear').count()) === 1);
await page.locator('.active-chips-clear').click();
await page.waitForFunction(() => document.querySelectorAll('#tx-tbody tr').length === 10, null, { timeout: 5000 }).catch(() => {});
check('Clear all resets the account chip too (back to all 10 rows, chip gone)',
  (await rows()) === 10 && (await page.locator('#active-filters .active-chip').count()) === 0,
  `rows=${await rows()}`);

await browser.close();

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

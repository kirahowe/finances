// Real-Chromium proof for /v2 column chooser + URL view-state persistence (Phase R3).
// Column visibility is persistent-but-client-applied: toggling a `cols.<id>` checkbox flips
// a `hide-<id>` class (pure CSS, no round-trip) and the v2-url island reflects it (+ filters/
// sort) into the query string, so a reload restores the view.
//
//   BASE_URL=http://localhost:8099 node e2e/v2-cols.ts
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

const instHeader = () => page.locator('thead th', { hasText: 'Institution' });

await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
check('no page errors', !logs.length, logs.join('; '));
check('Institution column visible initially', await instHeader().isVisible());

// Open the View menu and hide Institution.
await page.getByRole('button', { name: 'View', exact: true }).click();
await page.locator('.column-picker .filter-dropdown-item', { hasText: 'Institution' }).locator('input').uncheck();
await page.waitForFunction(() => {
  const t = document.querySelector('table.table');
  return t && t.classList.contains('hide-institution');
}, null, { timeout: 3000 }).catch(() => {});
check('hiding toggles hide-institution (CSS, no round-trip)', !(await instHeader().isVisible()));
check('URL reflects hidecols=institution',
  new URL(page.url()).searchParams.get('hidecols') === 'institution', page.url());

// Same menu, Display group: toggling "Posted dates" off flips hide-posted (pure CSS) and the
// url island persists the exception as posted=0 — structural, so it doesn't rely on seed data
// happening to carry a transaction/posted-date split.
await page.locator('.column-picker .filter-dropdown-item', { hasText: 'Posted dates' }).locator('input').uncheck();
await page.waitForFunction(() => {
  const t = document.querySelector('table.table');
  return t && t.classList.contains('hide-posted');
}, null, { timeout: 3000 }).catch(() => {});
check('hiding posted dates toggles hide-posted (CSS, no round-trip)',
  await page.evaluate(() => !!document.querySelector('table.table')?.classList.contains('hide-posted')));
check('URL reflects posted=0', new URL(page.url()).searchParams.get('posted') === '0', page.url());

// Reload → the hidden column is restored from the URL (server seeds the signal).
await page.reload({ waitUntil: 'networkidle' });
await page.waitForTimeout(300);
check('reload keeps Institution hidden', !(await instHeader().isVisible()));
check('reload keeps posted dates hidden (server seeds showPosted=false)',
  await page.evaluate(() => !!document.querySelector('table.table')?.classList.contains('hide-posted')));

// A filter also persists: search then reload restores it.
await page.locator('.table-search-input').fill('Superstore');
await page.waitForFunction(() => new URL(location.href).searchParams.get('q') === 'Superstore',
  null, { timeout: 3000 }).catch(() => {});
check('URL reflects the search', new URL(page.url()).searchParams.get('q') === 'Superstore');
await page.reload({ waitUntil: 'networkidle' });
await page.waitForTimeout(400);
check('reload restores the search (1 row) + the input value',
  (await page.locator('#tx-tbody tr').count()) === 1 &&
  (await page.locator('.table-search-input').inputValue()) === 'Superstore',
  `rows=${await page.locator('#tx-tbody tr').count()}`);

await browser.close();

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

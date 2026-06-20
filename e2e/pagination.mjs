// Browser-driven verification of pagination (Phase 3d): client-side, downstream of
// filtering. Page-size controls + first/prev/next/last, page-count + clamp computed by the
// island, all persisted in the URL so a refresh restores the exact page.
// Forced with ?pageSize=3 over the 10-row seed → 4 pages (3,3,3,1).
//   BASE_URL=http://localhost:8099 node e2e/pagination.mjs
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
const status = () => page.locator('.pagination-status').innerText();
const nav = (label) => page.locator(`.pagination-nav-button[aria-label="${label}"]`);
const sizeBtn = (n) => page.locator('.pagination-size-button').filter({ hasText: new RegExp(`^${n}$`) });
const url = () => page.url();

await page.goto(`${BASE}/?month=2025-01&pageSize=3`, { waitUntil: 'networkidle' });
await page.waitForTimeout(120);

// 1. First page shows pageSize rows; the island computed 4 pages.
check('page 1 shows 3 rows', (await visible()) === 3, `${await visible()}`);
check('status: Page 1 of 4', (await status()).trim() === 'Page 1 of 4', await status());
check('First/Prev disabled on page 1', (await nav('First page').isDisabled()) && (await nav('Previous page').isDisabled()));

// 2. Next advances a page and records it in the URL (1-indexed).
await nav('Next page').click();
await page.waitForTimeout(100);
check('Next → page 2 (3 rows)', (await visible()) === 3 && (await status()).trim() === 'Page 2 of 4', await status());
check('URL carries page=2', /[?&]page=2\b/.test(url()), url());

// 3. Last page shows the remainder (10 = 3+3+3+1).
await nav('Last page').click();
await page.waitForTimeout(100);
check('Last → page 4 shows 1 row', (await visible()) === 1 && (await status()).trim() === 'Page 4 of 4', await status());
check('Next/Last disabled on the last page', (await nav('Next page').isDisabled()) && (await nav('Last page').isDisabled()));

// 4. Prev/First walk back.
await nav('Previous page').click();
await page.waitForTimeout(80);
check('Prev → page 3', (await status()).trim() === 'Page 3 of 4', await status());
await nav('First page').click();
await page.waitForTimeout(80);
check('First → page 1', (await status()).trim() === 'Page 1 of 4', await status());

// 5. Refresh restores the exact page (the core principle).
await nav('Next page').click();
await page.waitForTimeout(100);
await page.goto(url(), { waitUntil: 'networkidle' });
await page.waitForTimeout(120);
check('refresh restores page 2', (await status()).trim() === 'Page 2 of 4' && (await visible()) === 3, await status());

// 6. Out-of-range page in the URL clamps to the last page.
await page.goto(`${BASE}/?month=2025-01&pageSize=3&page=99`, { waitUntil: 'networkidle' });
await page.waitForTimeout(150);
check('out-of-range page clamps to the last page', (await status()).trim() === 'Page 4 of 4' && (await visible()) === 1, await status());

// 7. Changing page size repaginates and persists; 25 (default) clears the URL param.
await sizeBtn(25).click();
await page.waitForTimeout(120);
check('page size 25 → all 10 rows, single page', (await visible()) === 10 && (await status()).trim() === 'Page 1 of 1', await status());
check('default page size 25 omitted from URL', !/pageSize=/.test(url()), url());
await sizeBtn(50).click();
await page.waitForTimeout(100);
check('page size 50 persisted in URL', /[?&]pageSize=50\b/.test(url()), url());

// 8. A filter change jumps back to page 1 (React parity) — restore pageSize 3 first.
await page.goto(`${BASE}/?month=2025-01&pageSize=3&page=2`, { waitUntil: 'networkidle' });
await page.waitForTimeout(120);
check('start on page 2 before filtering', (await status()).trim() === 'Page 2 of 4', await status());
await page.locator('.count-chip', { hasText: 'Hide transfers' }).click();
await page.waitForTimeout(120);
check('filtering resets to page 1', (await status()).trim().startsWith('Page 1 of'), await status());

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

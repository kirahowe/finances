// Real-Chromium proof for /v2 column auto-sizing + manual resize (Phase cp2-tail). The
// DOM-driven v2-resize island reads column metadata from the server-rendered headers, auto-
// fits the columns to content on load (fixed layout), lets you drag a border to override, and
// double-click hands a column back to auto-sizing. Hidden columns' <col> are set display:none
// so fixed layout keeps the rest aligned.
//
//   BASE_URL=http://localhost:8099 node e2e/v2-resize.mjs
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

const colW = (n) => page.locator('colgroup col').nth(n).evaluate((c) => parseFloat(c.style.width) || 0);
const colDisplay = (n) => page.locator('colgroup col').nth(n).evaluate((c) => c.style.display);

await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(500);
check('no page errors', !logs.length, logs.join('; '));
check('auto-fit set column widths on load', (await colW(3)) > 0 && (await colW(4)) > 0,
  `payee=${await colW(3)} desc=${await colW(4)}`);

// Drag the Payee (col index 3) resize handle right by 60px → its column grows, and ONLY it:
// every other column must keep its width (resize is local — the freeze-then-local model).
const before = await colW(3);
const acctBefore = await colW(1);
const instBefore = await colW(2);
await page.locator('th[data-col-id="payee"]').hover();
const handle = page.locator('th[data-col-id="payee"] .col-resize-handle');
const box = await handle.boundingBox();
await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
await page.mouse.down();
await page.mouse.move(box.x + box.width / 2 + 60, box.y + box.height / 2, { steps: 6 });
await page.mouse.up();
await page.waitForTimeout(150);
const afterDrag = await colW(3);
check('drag widens the Payee column', afterDrag >= before + 40, `${before} → ${afterDrag}`);
check('resize is LOCAL — other columns unchanged',
  (await colW(1)) === acctBefore && (await colW(2)) === instBefore,
  `account ${acctBefore}→${await colW(1)}, institution ${instBefore}→${await colW(2)}`);

// Double-click the handle → drop the manual width, re-auto-fit (narrower again).
await page.locator('th[data-col-id="payee"]').hover();
await handle.dblclick();
await page.waitForTimeout(150);
check('double-click re-fits (back toward auto width)', (await colW(3)) < afterDrag - 20,
  `${afterDrag} → ${await colW(3)}`);

// Hide a column via the picker → its <col> is display:none (fixed layout stays aligned).
await page.getByRole('button', { name: 'Columns' }).click();
await page.locator('.column-picker .filter-dropdown-item', { hasText: 'Amount' }).locator('input').uncheck();
await page.waitForFunction(() => {
  const c = document.querySelectorAll('colgroup col')[5];
  return c && getComputedStyle(c).display === 'none';
}, null, { timeout: 3000 }).catch(() => {});
check('hidden column <col> is display:none', (await colDisplay(5)) === 'none', `display=${await colDisplay(5)}`);

await browser.close();

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

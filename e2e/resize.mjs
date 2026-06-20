// Browser-driven verification of the column resize / auto-fit island (Phase 3d): the
// table is fixed-layout with island-owned <col> widths — auto-fit on load, drag handles
// to override per column, double-click a handle to re-auto-fit. A drag must not trigger
// the header sort, and column hiding must stay aligned in fixed layout.
//   BASE_URL=http://localhost:8099 node e2e/resize.mjs
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
await page.waitForTimeout(120); // let the island's first auto-fit settle

const th = (id) => page.locator(`th[data-col-id="${id}"]`);
const widthOf = async (id) => Math.round((await th(id).boundingBox()).width);
const left = async (loc) => Math.round((await loc.boundingBox()).x);

// Column-picker helpers (the menu is a toggle; manage its open/close state explicitly).
const pickerBtn = page.locator('.column-picker .filter-button');
const pickerMenu = page.locator('.column-picker .filter-dropdown');
async function toggleColumn(label) {
  if (!(await pickerMenu.isVisible())) { await pickerBtn.click(); await page.waitForTimeout(40); }
  await page.locator('.column-picker .filter-dropdown-checkbox-label', { hasText: label }).click();
  await page.waitForTimeout(120);
}
async function closePicker() {
  if (await pickerMenu.isVisible()) {
    await page.locator('.masthead, h1, body').first().click({ position: { x: 5, y: 5 } });
    await page.waitForTimeout(60);
  }
}

// 1. Fixed-layout table with drag handles on the resizable columns only.
check('table is table-resizable (fixed layout)',
  (await page.locator('table.table-resizable').count()) === 1);
check('7 resize handles (not reviewed/actions)',
  (await page.locator('.col-resize-handle').count()) === 7);
check('reviewed header has no handle',
  (await th('reviewed').locator('.col-resize-handle').count()) === 0);

// 2. Auto-fit ran: every visible column got a positive width above its min.
const payeeAuto = await widthOf('payee');
check('auto-fit gave payee a real width', payeeAuto >= 100, `${payeeAuto}`);

// 3. Dragging a column's handle widens it (manual override).
const handle = th('payee').locator('.col-resize-handle');
const hb = await handle.boundingBox();
await page.mouse.move(hb.x + hb.width / 2, hb.y + hb.height / 2);
await page.mouse.down();
await page.mouse.move(hb.x + hb.width / 2 + 90, hb.y + hb.height / 2, { steps: 6 });
await page.mouse.up();
await page.waitForTimeout(120);
const payeeDragged = await widthOf('payee');
check('drag widens the payee column', payeeDragged > payeeAuto + 50, `${payeeAuto} → ${payeeDragged}`);

// 4. The drag must NOT have triggered the header sort.
check('drag did not sort the column', (await th('payee').getAttribute('aria-sort')) === 'none',
  `${await th('payee').getAttribute('aria-sort')}`);

// 5. A real header click still sorts (collision guard didn't over-swallow).
await th('payee').locator('.th-label').click();
await page.waitForTimeout(80);
check('clicking the header still sorts', (await th('payee').getAttribute('aria-sort')) === 'ascending',
  `${await th('payee').getAttribute('aria-sort')}`);

// 6. The manual width survives a re-fit (toggle a column via the picker → recompute).
await toggleColumn('Date'); // hide Date
check('manual width persists across a re-fit', Math.abs((await widthOf('payee')) - payeeDragged) <= 4,
  `${payeeDragged} → ${await widthOf('payee')}`);
await toggleColumn('Date'); // show Date again
await closePicker();

// 7. Double-clicking the handle drops the manual width and re-auto-fits.
await handle.dblclick();
await page.waitForTimeout(120);
check('double-click re-auto-fits (drops manual width)', Math.abs((await widthOf('payee')) - payeeAuto) <= 6,
  `${await widthOf('payee')} vs auto ${payeeAuto}`);

// 8. Column hiding stays aligned in fixed layout (header left == its body cells' left).
await toggleColumn('Account'); // hide Account
const instHeaderLeft = await left(th('institution'));
const instCellLeft = await left(page.locator('table tbody tr:visible').first().locator('td').nth(2));
check('hidden column keeps header/body aligned', Math.abs(instHeaderLeft - instCellLeft) <= 2,
  `${instHeaderLeft} vs ${instCellLeft}`);
await toggleColumn('Account'); // restore
await closePicker();

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

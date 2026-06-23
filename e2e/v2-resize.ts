// Real-Chromium proof for /v2 column auto-sizing + manual resize (Phase cp2-tail). The
// DOM-driven v2-resize island reads column metadata from the server-rendered headers and auto-
// fits the columns to content on load (fixed layout). The resize model is strictly LOCAL:
// dragging a column changes ONLY that column — the columns to its LEFT keep their exact width,
// the columns to its RIGHT keep their exact width and are pushed right, and the table's total
// width grows by the drag delta so the scroll container scrolls horizontally. Double-clicking a
// handle fits THAT column to its content (Excel-style), again touching only it. Hidden columns'
// <col> are set display:none so fixed layout keeps the rest aligned.
//
//   BASE_URL=http://localhost:8099 node e2e/v2-resize.ts
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

const colW = (n) => page.locator('colgroup col').nth(n).evaluate((c) => parseFloat(c.style.width) || 0);
const colDisplay = (n) => page.locator('colgroup col').nth(n).evaluate((c) => c.style.display);
// Rendered width of the table element — the model says a drag grows this by the drag delta.
const tableW = () => page.locator('table.table-resizable').evaluate((t) => t.getBoundingClientRect().width);
// Real-column indices in colgroup order (the trailing spacer <col> is not in this list).
const COLS = { date: 0, account: 1, institution: 2, payee: 3, description: 4, amount: 5, category: 6, reviewed: 7 };
// Snapshot every real column's <col> width, keyed by id, for byte-identical comparison.
const snapshotCols = async () => {
  const out: Record<string, number> = {};
  for (const [id, i] of Object.entries(COLS)) out[id] = await colW(i);
  return out;
};

await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(500);
check('no page errors', !logs.length, logs.join('; '));
check('auto-fit set column widths on load', (await colW(3)) > 0 && (await colW(4)) > 0,
  `payee=${await colW(3)} desc=${await colW(4)}`);

// Drag the Payee (col index 3) resize handle right → its column grows, and ONLY it. The columns
// to its LEFT (date/account/institution) and to its RIGHT (description/amount/category/reviewed)
// must keep their EXACT widths, and the table's total width must grow by ~the drag delta (the
// table scrolls horizontally rather than stealing width from any other column).
const DRAG = 180;
const beforeCols = await snapshotCols();
const beforeTableW = await tableW();
await page.locator('th[data-col-id="payee"]').hover();
const handle = page.locator('th[data-col-id="payee"] .col-resize-handle');
const box = (await handle.boundingBox())!;
await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
await page.mouse.down();
await page.mouse.move(box.x + box.width / 2 + DRAG, box.y + box.height / 2, { steps: 6 });
await page.mouse.up();
await page.waitForTimeout(150);
const afterCols = await snapshotCols();
const afterTableW = await tableW();

// Payee widened by roughly the drag distance (allow generous slack for clamping/rounding).
check('drag widens the Payee column', afterCols.payee >= beforeCols.payee + DRAG * 0.5,
  `${beforeCols.payee} → ${afterCols.payee} (drag ${DRAG})`);

// Every OTHER real column is byte-identical — left and right alike.
const unchanged = Object.entries(COLS)
  .filter(([id]) => id !== 'payee')
  .filter(([id]) => afterCols[id] !== beforeCols[id]);
check('resize is LOCAL — every other column byte-identical (left + right)', unchanged.length === 0,
  unchanged.map(([id]) => `${id} ${beforeCols[id]}→${afterCols[id]}`).join(', ') || 'all unchanged');

// The table grew by ~the column delta (it did NOT keep the same width by stealing from a flex
// column or the spacer). Relational, not a hardcoded pixel total.
const tableDelta = afterTableW - beforeTableW;
const payeeDelta = afterCols.payee - beforeCols.payee;
check('table total width grew by the drag delta (scrolls, no redistribution)',
  tableDelta >= payeeDelta - 4 && tableDelta <= payeeDelta + 4,
  `table +${tableDelta.toFixed(1)} vs payee +${payeeDelta.toFixed(1)}`);

// Double-click the handle → fit Payee to its content (Excel-style). The dragged width is well
// past the content, so it shrinks; and again ONLY Payee moves.
const beforeFit = await snapshotCols();
await page.locator('th[data-col-id="payee"]').hover();
await handle.dblclick();
await page.waitForTimeout(150);
const afterFit = await snapshotCols();
check('double-click fits Payee to content (shrinks past the over-drag)',
  afterFit.payee < beforeFit.payee - 20, `${beforeFit.payee} → ${afterFit.payee}`);
const fitChanged = Object.entries(COLS)
  .filter(([id]) => id !== 'payee')
  .filter(([id]) => afterFit[id] !== beforeFit[id]);
check('double-click fit is LOCAL — every other column byte-identical', fitChanged.length === 0,
  fitChanged.map(([id]) => `${id} ${beforeFit[id]}→${afterFit[id]}`).join(', ') || 'all unchanged');

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

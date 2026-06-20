// Browser-driven verification of the keyboard grid-navigation island (Phase 3d-1):
// arrow/Home/End movement across the editable cells, click-select, Space toggles the
// reviewed checkbox, roving tabindex + ARIA roles, and yielding the keyboard to an
// open inline editor.
//   BASE_URL=http://localhost:8099 node e2e/grid-nav.mjs
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

await page.goto(URL, { waitUntil: 'networkidle' });

const gridState = () => page.evaluate(() => window.__gridState?.() ?? null);

// 1. ARIA roles are in place.
check('table has role=grid', (await page.locator('table[role=grid]').count()) === 1);
check('rows have role=row', (await page.locator('table tbody tr[role=row]').count()) === 10,
  `${await page.locator('table tbody tr[role=row]').count()}`);
check('editable cells have role=gridcell', (await page.locator('td[role=gridcell]').count()) === 30,
  `${await page.locator('td[role=gridcell]').count()}`);

// 2. Focusing the grid lands on the first cell (description of the first row).
await page.locator('.transactions-table-scroll').focus();
let s = await gridState();
check('focus activates the first cell', s?.active?.col === 'description', JSON.stringify(s?.active));
check('exactly one cell is active', (await page.locator('td.grid-cell-active').count()) === 1);
check('active cell is the roving tab stop', (await page.locator('td.grid-cell-active').getAttribute('tabindex')) === '0');
check('container steps out of the tab order',
  (await page.locator('.transactions-table-scroll').getAttribute('tabindex')) === '-1');

const firstTx = s?.active?.key?.txId;

// 3. ArrowDown moves to the next row, same column.
await page.keyboard.press('ArrowDown');
s = await gridState();
check('ArrowDown keeps the column, changes row', s?.active?.col === 'description' && s?.active?.key?.txId !== firstTx);

// 4. ArrowRight walks the editable columns; clamps at the end.
await page.keyboard.press('ArrowRight');
check('ArrowRight -> category', (await gridState())?.active?.col === 'category');
await page.keyboard.press('ArrowRight');
check('ArrowRight -> reviewed', (await gridState())?.active?.col === 'reviewed');
await page.keyboard.press('ArrowRight');
check('ArrowRight clamps at reviewed', (await gridState())?.active?.col === 'reviewed');

// 5. Home/End jump within the row.
await page.keyboard.press('Home');
check('Home -> row start (description)', (await gridState())?.active?.col === 'description');
await page.keyboard.press('End');
check('End -> row end (reviewed)', (await gridState())?.active?.col === 'reviewed');

// 6. Space toggles the reviewed checkbox of the active (reviewed) cell.
const activeBox = () => page.locator('td.grid-cell-active .reviewed-checkbox');
const before = await activeBox().isChecked();
await page.keyboard.press('Space');
check('Space toggles the active reviewed checkbox', (await activeBox().isChecked()) === !before);
await page.keyboard.press('Space'); // restore (debounced write-behind coalesces to original)
check('Space toggles back', (await activeBox().isChecked()) === before);

// 7. Click selects a cell.
const payeeCellCategory = page.locator('table tbody tr', { hasText: 'Superstore' }).locator('td.category-cell');
await payeeCellCategory.click();
s = await gridState();
check('click selects the clicked cell', s?.active?.col === 'category');

// 8. The grid yields the keyboard to an open inline editor.
const descRow = page.locator('table tbody tr', { hasText: 'Superstore' });
await descRow.locator('.description-button').click();
check('clicking opens the inline editor', await descRow.locator('.description-input').isVisible());
const activeBeforeYield = JSON.stringify((await gridState())?.active);
await page.keyboard.press('ArrowDown'); // should move the text caret, not the grid
check('grid yields ArrowDown to the editor', JSON.stringify((await gridState())?.active) === activeBeforeYield);
await descRow.locator('.description-input').press('Escape');

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

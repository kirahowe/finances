// Browser-driven verification of grid-nav keyboard EDITING (Phase 3d-2): Enter-to-edit,
// type-to-edit, Enter-saves-and-advances down the column, Escape-cancels, Tab commits +
// moves, and Enter on a category cell opens the combobox and advances on select.
//   BASE_URL=http://localhost:8099 node e2e/grid-edit.mjs
// NOTE: mutates the seeded DB; resets it at the end.
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
const active = () => page.evaluate(() => window.__gridState?.()?.active ?? null);
const settle = () => page.waitForTimeout(80);

// Land on the first cell (description) and open its editor with Enter.
await page.locator('.transactions-table-scroll').focus();
await settle();
check('starts on a description cell', (await active())?.col === 'description');
await page.keyboard.press('Enter');
await settle();
check('Enter opens the inline editor', await page.locator('td.grid-cell-active .description-input').isVisible());

// Type + Enter persists and advances down to the next row's description editor.
const a0 = await active();
await page.keyboard.type('Walk the column');
await page.keyboard.press('Enter');
await settle();
const a1 = await active();
check('Enter advances down, same column', a1?.col === 'description' && a1?.key?.txId !== a0?.key?.txId,
  `${a0?.key?.txId} -> ${a1?.key?.txId}`);
check('the advanced cell is editing', await page.locator('td.grid-cell-active .description-input').isVisible());

// Escape cancels the (advanced) editor and returns focus to the cell.
await page.keyboard.press('Escape');
await settle();
check('Escape closes the editor', (await page.locator('.description-cell.editing').count()) === 0);
check('cell stays active after Escape', (await page.locator('td.grid-cell-active').count()) === 1);

// type-to-edit: a printable key opens the editor seeded with that character.
await page.keyboard.type('z');
await settle();
check('type-to-edit opens the editor', await page.locator('td.grid-cell-active .description-input').isVisible());
check('editor is seeded with the typed char',
  (await page.locator('td.grid-cell-active .description-input').inputValue()) === 'z');
await page.keyboard.press('Escape');
await settle();

// Tab in edit mode commits + moves right (description -> category).
await page.keyboard.press('Enter'); // re-open the description editor
await settle();
await page.keyboard.press('Tab');
await settle();
check('Tab from editing moves right to category', (await active())?.col === 'category');
check('no editor left open after Tab', (await page.locator('.description-cell.editing').count()) === 0);

// Enter on a category cell opens the combobox; Enter-select advances to the next row.
await page.keyboard.press('Enter');
await settle();
check('Enter opens the category combobox', await page.locator('.category-dropdown.is-floating').isVisible());
const catBefore = await active();
await page.keyboard.type('Gro');
await settle();
await page.keyboard.press('Enter'); // select the highlighted Groceries
await settle();
const catAfter = await active();
check('combobox select advances to next row category',
  catAfter?.col === 'category' && catAfter?.key?.txId !== catBefore?.key?.txId,
  `${catBefore?.key?.txId} -> ${catAfter?.key?.txId}`);
check('the next row combobox is open', await page.locator('.category-dropdown.is-floating').isVisible());
await page.keyboard.press('Escape');
await settle();
check('Escape closes the combobox', (await page.locator('.category-dropdown.is-floating').count()) === 0);

check('no page errors', !logs.length, logs.join('; '));

// Restore the seed (this spec persisted a description + a category).
await page.evaluate(() => fetch('/e2e/reset', { method: 'POST' }));

await browser.close();

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

// Browser-driven verification of the category combobox island (Phase 3c, first real
// Zag.js widget): open on a cell, type to filter, select to assign optimistically +
// persist via @put, server re-patches the uncategorized count, and clearing works.
//   BASE_URL=http://localhost:8099 node e2e/combobox.mjs
// NOTE: mutates the seeded DB (assigns a category, then clears it back).
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

const ROW = 'Transfer to Savings'; // an uncategorized row in the seed
const catBtn = () => page.locator('table.table tbody tr', { hasText: ROW }).locator('.category-button');
const uncatCount = async () => (await page.locator('#count-uncategorized').innerText()).trim();
const item = (label) => page.locator('.category-dropdown-item', { hasText: label });

// 1. Row starts uncategorized; the seed month has 6 uncategorized rows.
check('row starts Uncategorized', (await catBtn().innerText()).trim() === 'Uncategorized');
check('uncategorized count starts at 6', (await uncatCount()) === '6', await uncatCount());

// 2. Clicking the cell opens the combobox.
await catBtn().click();
check('clicking the cell opens the combobox', await page.locator('.category-dropdown-input').isVisible());

// 3. Typing filters the option list (Zag owns the keyboard/ARIA).
await page.locator('.category-dropdown-input').fill('Gro');
await page.waitForTimeout(50);
check('typing filters to Groceries', (await item('Groceries').count()) === 1 && (await item('Salary').count()) === 0);

// 4. Selecting assigns the category optimistically (no reload) and closes the popup.
const t0 = Date.now();
await item('Groceries').click();
const optimistic = (await catBtn().innerText()).trim();
check('select assigns category optimistically', optimistic === 'Groceries' && Date.now() - t0 < 400,
  `${optimistic} ${Date.now() - t0}ms`);
check('popup closes after select', (await page.locator('.category-dropdown-input').count()) === 0);

// 5. Server re-patches the uncategorized count (now one fewer).
await page.waitForTimeout(500);
check('uncategorized count drops to 5', (await uncatCount()) === '5', await uncatCount());

// 6. Persists across reload.
await page.goto(URL, { waitUntil: 'networkidle' });
check('category persisted across reload', (await catBtn().innerText()).trim() === 'Groceries');
check('count still 5 after reload', (await uncatCount()) === '5', await uncatCount());

// 7. Clear back to Uncategorized (restore the seed).
await catBtn().click();
await item('Uncategorized').click();
await page.waitForTimeout(500);
check('clearing restores Uncategorized', (await catBtn().innerText()).trim() === 'Uncategorized');
check('count back to 6', (await uncatCount()) === '6', await uncatCount());

await page.goto(URL, { waitUntil: 'networkidle' });
check('cleared state persisted', (await catBtn().innerText()).trim() === 'Uncategorized' && (await uncatCount()) === '6');

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

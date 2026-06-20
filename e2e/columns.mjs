// Browser-driven verification of column visibility (Phase 3d-3a): the toolbar "Columns"
// picker toggles read-only columns on/off via Datastar signals + CSS, with no reload.
//   BASE_URL=http://localhost:8099 node e2e/columns.mjs
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

const picker = page.locator('.column-picker .filter-button');
const dropdown = page.locator('.column-picker .filter-dropdown');
const accountHeader = page.locator('table thead th:nth-child(2)'); // Account
const accountCell = page.locator('table tbody tr:first-child td:nth-child(2)');
const dateHeader = page.locator('table thead th:nth-child(1)'); // Date

// 1. The picker exists; the account column starts visible.
check('Columns picker present', await picker.isVisible());
check('Account header starts visible', await accountHeader.isVisible());

// 2. Opening the picker reveals the menu.
await picker.click();
check('clicking opens the column menu', await dropdown.isVisible());

// 3. Unchecking Account hides that column (no reload), leaving the others.
await page.locator('.filter-dropdown-checkbox-label', { hasText: 'Account' }).click();
await page.waitForTimeout(60);
check('Account header hidden after uncheck', !(await accountHeader.isVisible()));
check('an Account cell is hidden too', !(await accountCell.isVisible()));
check('Date column stays visible', await dateHeader.isVisible());

// 4. Re-checking restores it.
await page.locator('.filter-dropdown-checkbox-label', { hasText: 'Account' }).click();
await page.waitForTimeout(60);
check('Account header restored after re-check', await accountHeader.isVisible());

// 5. Clicking outside closes the menu.
await page.locator('h1, .masthead, body').first().click({ position: { x: 5, y: 5 } });
await page.waitForTimeout(60);
check('clicking outside closes the menu', !(await dropdown.isVisible()));

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

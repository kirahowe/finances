// Browser-driven verification of the server-rendered /setup page (Phase 2,
// read-only account list). Run against the seeded e2e backend:
//   BASE_URL=http://localhost:8099 node e2e/setup.mjs
//
// The seed (env/e2e .../seed.clj) has 1 institution, 4 accounts
// (Chequing/Savings/Visa/Mortgage, no provider/mask), 10 transactions.
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
page.on('console', (m) => logs.push(m.text()));
page.on('pageerror', (e) => logs.push('PAGEERROR: ' + e.message));

await page.goto(`${BASE}/setup`, { waitUntil: 'networkidle' });

// 0. No JS errors
check('no page errors', !logs.some((l) => l.startsWith('PAGEERROR')),
  logs.filter((l) => l.startsWith('PAGEERROR')).join('; '));

// 1. Masthead present with Setup tab active
const activeTab = await page.locator('.view-tab.is-active').innerText().catch(() => '');
check('masthead Setup tab active', activeTab.trim() === 'Setup', `active="${activeTab.trim()}"`);

// 2. Stats cards reflect the seed (1 / 4 / 10)
const statValues = await page.locator('.stats-grid .stat-value').allInnerTexts();
check('stat cards = [1, 4, 10]', JSON.stringify(statValues.map((s) => s.trim())) === '["1","4","10"]',
  JSON.stringify(statValues));

// 3. Account table has 4 rows
const rowCount = await page.locator('table.table tbody tr').count();
check('account table has 4 rows', rowCount === 4, `rows=${rowCount}`);

// 4. All four seeded accounts present, in sorted order
const names = await page.locator('table.table tbody tr td:nth-child(3)').allInnerTexts();
check('account names sorted [Chequing, Mortgage, Savings, Visa]',
  JSON.stringify(names.map((n) => n.trim())) === '["Chequing","Mortgage","Savings","Visa"]',
  JSON.stringify(names));

// 5. Type column shows internal account types (no provider-type on seed data)
const types = await page.locator('table.table tbody tr td:nth-child(4)').allInnerTexts();
check('type column = [chequing, loan, savings, credit]',
  JSON.stringify(types.map((t) => t.trim())) === '["chequing","loan","savings","credit"]',
  JSON.stringify(types));

// 6. Section count chip = 4
const sectionCount = (await page.locator('.section-count').innerText()).trim();
check('section count chip = 4', sectionCount === '4', sectionCount);

// 7. The carried-over design system actually applied (typography token resolved)
const bodyFont = await page.evaluate(() => getComputedStyle(document.body).fontFamily);
check('design system CSS applied (Hanken Grotesk on body)', /Hanken Grotesk/i.test(bodyFont), bodyFont);

await browser.close();

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

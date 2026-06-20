// Browser-driven verification of the server-rendered transactions page (Phase 3a,
// read-only table + month nav). Run against the seeded e2e backend:
//   BASE_URL=http://localhost:8099 node e2e/transactions.mjs
//
// Seed month 2025-01 has 10 transactions: real income/expense, two matched
// transfer pairs (4 legs), and one unmatched transfer-typed row.
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

await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });

check('no page errors', !logs.some((l) => l.startsWith('PAGEERROR')),
  logs.filter((l) => l.startsWith('PAGEERROR')).join('; '));

const activeTab = (await page.locator('.view-tab.is-active').innerText().catch(() => '')).trim();
check('Transactions tab active', activeTab === 'Transactions', activeTab);

const monthLabel = (await page.locator('.month-navigator-display').innerText()).trim();
check('month display = January 2025', monthLabel === 'January 2025', monthLabel);

const rowCount = await page.locator('table.table tbody tr').count();
check('10 transaction rows', rowCount === 10, `rows=${rowCount}`);

const payees = await page.locator('table.table tbody tr td:nth-child(4)').allInnerTexts();
const flat = payees.map((p) => p.trim());
check('expected payees present',
  ['Acme Payroll', 'Superstore', 'Mortgage Payment', 'Transfer Out'].every((p) => flat.includes(p)),
  JSON.stringify(flat));

// Amount formatting + sign classes
const salaryAmt = (await page.locator('table.table tbody tr', { hasText: 'Acme Payroll' })
  .locator('.amount-cell .numeric').innerText()).trim();
check('CAD amount formatting', salaryAmt === '$4,000.00', salaryAmt);
const positives = await page.locator('.amount-cell .numeric.positive').count();
const negatives = await page.locator('.amount-cell .numeric.negative').count();
check('amounts color-coded by sign', positives > 0 && negatives > 0, `+${positives} / -${negatives}`);

// Transfer status: 4 matched legs (two pairs) + 1 unmatched "Match"
const matched = await page.locator('.transfer-status-matched').count();
const unmatched = await page.locator('.transfer-status-unmatched').count();
check('4 matched transfer glyphs', matched === 4, `matched=${matched}`);
check('1 unmatched "Match"', unmatched === 1, `unmatched=${unmatched}`);

// Date column not collapsed (col width applied so dates don't truncate)
const dateColW = await page.evaluate(() => {
  const col = document.querySelector('table.table colgroup col');
  return col ? parseInt(getComputedStyle(col).width, 10) : 0;
});
check('date column width applied (>=120px)', dateColW >= 120, `${dateColW}px`);

// Month navigation: clicking next navigates to an empty Feb 2025
await page.locator('.month-nav-button[title="Next month"]').click();
await page.waitForLoadState('networkidle');
const febLabel = (await page.locator('.month-navigator-display').innerText()).trim();
check('next-month nav → February 2025', febLabel === 'February 2025', febLabel);
const febEmpty = await page.locator('.empty-state-title').count();
check('February is empty (empty-state shown)', febEmpty === 1, `empty-states=${febEmpty}`);

await browser.close();

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

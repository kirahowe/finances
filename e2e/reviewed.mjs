// Browser-driven verification of the optimistic reviewed toggle (Phase 3b):
// instant client flip + debounced write-behind that persists to the DB.
//   BASE_URL=http://localhost:8099 node e2e/reviewed.mjs
// NOTE: mutates the seeded DB (toggles a row, then toggles it back).
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

const boxFor = (payee) =>
  page.locator('table.table tbody tr', { hasText: payee }).locator('.reviewed-checkbox');

// 1. Starts unreviewed
const box = boxFor('Acme Payroll');
check('Acme Payroll starts unreviewed', !(await box.isChecked()));

// 2. Click flips instantly (optimistic, no reload)
const t0 = Date.now();
await box.click();
const flipped = await box.isChecked();
check('click flips checkbox instantly', flipped && Date.now() - t0 < 200, `${Date.now() - t0}ms`);

// 3. Wait past the 700ms write-behind debounce, then reload — flag persisted
await page.waitForTimeout(1100);
await page.goto(URL, { waitUntil: 'networkidle' });
check('reviewed flag persisted across reload', await boxFor('Acme Payroll').isChecked());

// 4. Toggle back off and confirm it clears (leave seed as we found it)
await boxFor('Acme Payroll').click();
await page.waitForTimeout(1100);
await page.goto(URL, { waitUntil: 'networkidle' });
check('un-reviewing persists too', !(await boxFor('Acme Payroll').isChecked()));

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

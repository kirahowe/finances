// Browser-driven verification of the inline description editor (Phase 3c): click to
// edit, Enter persists optimistically + via @put write-behind, Escape discards, and a
// cleared override reconciles to the imported description.
//   BASE_URL=http://localhost:8099 node e2e/edit.mjs
// NOTE: mutates the seeded DB (edits a row's description, then clears it back).
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

// Locate by payee — the view button's text follows the signal as you type, so the
// row's description text is not a stable anchor mid-edit.
const PAYEE = 'Superstore';
const row = () => page.locator('table.table tbody tr', { hasText: PAYEE });
const view = () => row().locator('.description-button');
const input = () => row().locator('.description-input');
const desc = async () => (await view().innerText()).trim();

// 1. Seed rows have no imported description -> the cell reads "—".
check('description starts empty (—)', (await desc()) === '—');

// 2. Click opens the editor (input becomes visible/focused).
await view().click();
check('clicking opens the editor', await input().isVisible());

// 3. Type + Enter updates the cell optimistically (no reload).
await input().fill('Costco run');
const t0 = Date.now();
await input().press('Enter');
check('Enter updates cell optimistically', (await desc()) === 'Costco run' && Date.now() - t0 < 400,
  `${await desc()} ${Date.now() - t0}ms`);

// 4. The write-behind persists across a reload.
await page.waitForTimeout(500);
await page.goto(URL, { waitUntil: 'networkidle' });
check('description persisted across reload', (await desc()) === 'Costco run', await desc());

// 5. Escape discards an in-progress edit.
await view().click();
await input().fill('SHOULD NOT SAVE');
await input().press('Escape');
check('Escape reverts the cell', (await desc()) === 'Costco run', await desc());
await page.goto(URL, { waitUntil: 'networkidle' });
check('Escape did not persist', (await desc()) === 'Costco run', await desc());

// 6. Clearing the override reconciles to the imported description (here: empty -> "—").
await view().click();
await input().fill('');
await input().press('Enter');
await page.waitForTimeout(500);
check('cleared override falls back to — optimistically', (await desc()) === '—', await desc());
await page.goto(URL, { waitUntil: 'networkidle' });
check('cleared override persisted', (await desc()) === '—', await desc());

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

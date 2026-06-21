// Real-Chromium proof of the split editor: row-actions menu → modal → live balance math →
// save (round-tripped through the command log) → the row morphs into a split parent + parts →
// undo un-splits it. Mirrors the server-authoritative idiom: discrete change round-trips a
// fragment; the in-modal interaction (typing, fill, add/remove) is the split-editor island.
//
//   BASE_URL=http://localhost:8099 node e2e/v2-split.mjs
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

// Clean slate (this spec mutates: it creates a split), then load the workspace.
await page.request.post(`${BASE}/e2e/reset`);
await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);

const superstore = () => page.locator('#tx-tbody tr', { hasText: 'Superstore' }).first();
const splitParent = () => page.locator('#tx-tbody tr.is-split-parent').filter({ hasText: 'Superstore' });

// Open the row-actions menu and the split editor for the (unsplit) Superstore row.
await superstore().hover();
await superstore().locator('.row-actions-trigger').click();
const menu = page.locator('#row-actions-menu');
await menu.waitFor({ state: 'visible' });
check('menu offers "Split transaction" for an unsplit row',
  (await menu.locator('.row-actions-item').innerText()).trim() === 'Split transaction');
await menu.locator('.row-actions-item').click();

const modal = page.locator('.split-modal-content');
await modal.waitFor({ state: 'visible' });
const dataRows = modal.locator('.split-rows-container .split-row');
check('editor opens with two blank parts', (await dataRows.count()) === 2);

// Allocate part of the total + one category; the editor is unbalanced, so Save is gated.
await dataRows.nth(0).locator('.split-amount-input').fill('80.00');
await dataRows.nth(0).locator('.split-category-select').selectOption({ label: 'Groceries' });
check('save disabled while unbalanced', await modal.locator('.split-save').isDisabled());

// Fill the remainder via the Fill shortcut, categorise it → balanced → Save enabled.
await dataRows.nth(1).locator('.split-fill-button').click();
check('fill set the remainder to 5.00',
  (await dataRows.nth(1).locator('.split-amount-input').inputValue()) === '5.00');
await dataRows.nth(1).locator('.split-category-select').selectOption({ label: 'Housing' });
check('balance reads "Balanced"', /balanced/i.test(await modal.locator('.split-remaining').innerText()));
check('save enabled once balanced', !(await modal.locator('.split-save').isDisabled()));

// Save → the command applies server-side, the row morphs to a split parent + parts, modal closes.
await modal.locator('.split-save').click();
await page.waitForFunction(
  () => [...document.querySelectorAll('#tx-tbody tr')].some(
    (tr) => tr.classList.contains('is-split-parent') && tr.textContent.includes('Superstore')),
  null, { timeout: 5000 }).catch(() => {});
check('Superstore became a split parent', (await splitParent().count()) === 1);
check('two split parts rendered', (await page.locator('#tx-tbody tr.split-child-row').count()) === 2);
check('modal closed after save', (await modal.count()) === 0);

// Undo (toolbar button) restores the unsplit row.
await page.locator('#undo-redo button[aria-label="Undo"]').click();
await page.waitForFunction(
  () => ![...document.querySelectorAll('#tx-tbody tr')].some(
    (tr) => tr.classList.contains('is-split-parent') && tr.textContent.includes('Superstore')),
  null, { timeout: 5000 }).catch(() => {});
check('undo un-splits Superstore', (await splitParent().count()) === 0);
check('no split parts after undo', (await page.locator('#tx-tbody tr.split-child-row').count()) === 0);

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

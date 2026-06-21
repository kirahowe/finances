// Real-Chromium proof for the category combobox on /v2 (Phase R2 cp2). The Zag combobox
// island opens on click; selecting a category @put's a server-confirmed :update-category
// command (morphs the row + the uncategorized count), and it's undoable via the command log.
// The chosen id rides the single $catValue courier (no per-row signals).
//
//   BASE_URL=http://localhost:8099 node e2e/v2-category.mjs
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

// Use a row that's uncategorized, so recategorizing also moves the uncategorized count.
// tx 12 ("Transfer to Savings") is Uncategorized in the seed.
const catCell = () => page.locator('#tx-tbody tr', { has: page.getByText('Transfer to Savings') }).first()
  .locator('.category-cell .category-button.combo-cell');
const catText = () => catCell().innerText();
const uncat = () => page.locator('#count-uncategorized').innerText();

await page.goto(`${BASE}/v2?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(400); // Datastar + combobox island init

check('no page errors', !logs.length, logs.join('; '));
check('row starts Uncategorized', (await catText()).trim() === 'Uncategorized', await catText());
const uncatBefore = Number((await uncat()).trim());

// Open the combobox and pick "Groceries".
await catCell().click();
await page.locator('.category-dropdown.is-floating').waitFor({ state: 'visible', timeout: 5000 });
check('combobox opens', await page.locator('.category-dropdown.is-floating').isVisible());

// Typing auto-highlights the top match (so Enter selects it without arrowing).
await page.locator('.category-dropdown-input').type('gro');
await page.waitForTimeout(150);
check('typing auto-highlights the matched option',
  (await page.locator('.category-dropdown-item.highlighted').first().innerText().catch(() => '')).trim() === 'Groceries');
await page.locator('.category-dropdown-input').fill('');
await page.waitForTimeout(100);

await page.locator('.category-dropdown-item', { hasText: /^Groceries$/ }).first().click();

// Gate on the LAST patch of the response (the undo-redo controls) so the earlier tbody +
// counts patches are guaranteed applied (each is a separate SSE event).
await page.waitForFunction(() => {
  const u = document.querySelector('[aria-label="Undo"]');
  return u && !u.disabled && (u.getAttribute('title') || '').includes('Recategorized');
}, null, { timeout: 5000 }).catch(() => {});
check('selection persists; cell shows Groceries', (await catText()).trim() === 'Groceries', await catText());
check('uncategorized count dropped by 1', Number((await uncat()).trim()) === uncatBefore - 1,
  `${(await uncat()).trim()} (was ${uncatBefore})`);
check('undo button enabled with label',
  (await page.getByRole('button', { name: 'Undo' }).getAttribute('title')).includes('Recategorized'));

// Undo → back to Uncategorized, count restored.
await page.getByRole('button', { name: 'Undo' }).click();
await page.waitForFunction((before) => {
  const c = [...document.querySelectorAll('#tx-tbody tr')].find((r) => r.textContent.includes('Transfer to Savings'));
  const cnt = document.querySelector('#count-uncategorized');
  return c && c.querySelector('.category-button.combo-cell')?.textContent.trim() === 'Uncategorized'
    && cnt && cnt.textContent.trim() === String(before);
}, uncatBefore, { timeout: 5000 }).catch(() => {});
check('undo reverts to Uncategorized', (await catText()).trim() === 'Uncategorized', await catText());
check('undo restores the count', Number((await uncat()).trim()) === uncatBefore, (await uncat()).trim());

await browser.close();
await fetch(`${BASE}/e2e/reset`, { method: 'POST' }).catch(() => {});

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

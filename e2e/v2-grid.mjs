// Real-Chromium proof for keyboard grid-navigation on /v2 (Phase cp2-tail). Arrow keys move
// the active cell, Space toggles reviewed (a server-confirmed edit), Enter opens the inline
// editor. The key /v2 wrinkle: every edit morphs #tx-tbody, which would wipe the client-side
// active highlight — the island's morph observer rebuilds + repaints so the active cell (keyed
// by stable RowKey) survives.
//
//   BASE_URL=http://localhost:8099 node e2e/v2-grid.mjs
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

const active = () => page.evaluate(() => document.querySelector('.grid-cell-active')?.getAttribute('data-cell') ?? null);

await page.goto(`${BASE}/?month=2025-01&scope=all`, { waitUntil: 'networkidle' });
await page.waitForTimeout(400);
check('no page errors', !logs.length, logs.join('; '));

// Focus the grid → lands on the first navigable cell.
await page.locator('.transactions-table-scroll').focus();
check('focus lands on first cell', (await active()) === '10:tx:description', await active());

// Arrow navigation across columns and down rows.
await page.keyboard.press('ArrowRight');
await page.keyboard.press('ArrowRight');
check('ArrowRight → reviewed cell', (await active()) === '10:tx:reviewed', await active());
await page.keyboard.press('ArrowDown');
check('ArrowDown → next row', (await active()) === '13:tx:reviewed', await active());

// Space toggles reviewed (server edit + morph); the active cell survives the morph.
await page.keyboard.press('Space');
await page.waitForTimeout(500);
const checkedAfter = await page.evaluate(() =>
  document.querySelector('[data-cell="13:tx:reviewed"] input')?.checked);
check('Space toggled reviewed (server-confirmed)', checkedAfter === true);
check('active cell survives the edit morph', (await active()) === '13:tx:reviewed', await active());

// Enter opens the inline description editor on a description cell.
await page.keyboard.press('ArrowLeft');
await page.keyboard.press('ArrowLeft');
check('ArrowLeft → description cell', (await active()) === '13:tx:description', await active());
await page.keyboard.press('Enter');
await page.waitForTimeout(150);
check('Enter opens the description editor',
  await page.evaluate(() => document.activeElement?.classList.contains('description-input')));

// Type + Enter commits (server-confirmed); the row reflects it.
await page.keyboard.type('Keyboard note');
await page.keyboard.press('Enter');
await page.waitForFunction(() => {
  const r = [...document.querySelectorAll('#tx-tbody tr')].find((x) => x.querySelector('[data-cell="13:tx:description"]'));
  return r && r.querySelector('.description-button')?.textContent.trim() === 'Keyboard note';
}, null, { timeout: 5000 }).catch(() => {});
check('typed + Enter persists the description',
  await page.evaluate(() => document.querySelector('[data-cell="13:tx:description"] .description-button')?.textContent.trim() === 'Keyboard note'));

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

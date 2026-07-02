// Real-Chromium proof for keyboard grid-navigation on /v2 (Phase cp2-tail). Arrow keys move
// the active cell, Space toggles reviewed (a server-confirmed edit), Enter opens the inline
// editor. The key /v2 wrinkle: every edit morphs #tx-tbody, which would wipe the client-side
// active highlight — the island's morph observer rebuilds + repaints so the active cell (keyed
// by stable RowKey) survives.
//
//   BASE_URL=http://localhost:8099 node e2e/v2-grid.ts
import { chromium } from '@playwright/test';

const BASE = process.env.BASE_URL || 'http://localhost:8099';
const results: { name: string; ok: boolean; detail: string }[] = [];
const check = (name: string, ok: unknown, detail: unknown = ''): void => {
  results.push({ name, ok: !!ok, detail: detail == null ? '' : String(detail) });
};

const browser = await chromium.launch();
const page = await browser.newPage();
const logs: string[] = [];
page.on('pageerror', (e) => logs.push('PAGEERROR: ' + e.message));

const active = () => page.evaluate(() => document.querySelector('.grid-cell-active')?.getAttribute('data-cell') ?? null);

await page.goto(`${BASE}/?month=2025-01&scope=all`, { waitUntil: 'networkidle' });
await page.waitForTimeout(400);
check('no page errors', !logs.length, logs.join('; '));

// Focus the grid → lands on the first navigable cell (a description cell). The seed's
// absolute eids shift with any schema/seed change, so capture the row eids at runtime
// and assert navigation relative to them.
await page.locator('.transactions-table-scroll').focus();
const c1 = await active();
check('focus lands on first cell', !!c1 && /^\d+:tx:description$/.test(c1), c1);
const r1 = c1?.split(':')[0];

// Arrow navigation across columns and down rows.
await page.keyboard.press('ArrowRight');
await page.keyboard.press('ArrowRight');
check('ArrowRight → reviewed cell', (await active()) === `${r1}:tx:reviewed`, await active());
await page.keyboard.press('ArrowDown');
const c2 = await active();
check('ArrowDown → next row', !!c2 && /:tx:reviewed$/.test(c2) && c2 !== `${r1}:tx:reviewed`, c2);
const r2 = c2?.split(':')[0];

// Space toggles reviewed (server edit + morph); the active cell survives the morph.
await page.keyboard.press('Space');
await page.waitForTimeout(500);
const checkedAfter = await page.evaluate((cell) =>
  document.querySelector<HTMLInputElement>(`[data-cell="${cell}"] input`)?.checked, `${r2}:tx:reviewed`);
check('Space toggled reviewed (server-confirmed)', checkedAfter === true);
check('active cell survives the edit morph', (await active()) === `${r2}:tx:reviewed`, await active());

// Enter opens the inline description editor on a description cell.
await page.keyboard.press('ArrowLeft');
await page.keyboard.press('ArrowLeft');
check('ArrowLeft → description cell', (await active()) === `${r2}:tx:description`, await active());
await page.keyboard.press('Enter');
await page.waitForTimeout(150);
check('Enter opens the description editor',
  await page.evaluate(() => document.activeElement?.classList.contains('description-input')));

// Type + Enter commits (server-confirmed); the row reflects it.
await page.keyboard.type('Keyboard note');
await page.keyboard.press('Enter');
await page.waitForFunction((cell) => {
  const r = [...document.querySelectorAll('#tx-tbody tr')].find((x) => x.querySelector(`[data-cell="${cell}"]`));
  return !!r && r.querySelector('.description-button')?.textContent.trim() === 'Keyboard note';
}, `${r2}:tx:description`, { timeout: 5000 }).catch(() => {});
check('typed + Enter persists the description',
  await page.evaluate((cell) => document.querySelector(`[data-cell="${cell}"] .description-button`)?.textContent.trim() === 'Keyboard note', `${r2}:tx:description`));

// Regression: an Enter commit removes the focused input from the morphed tbody, dropping focus to
// <body> — grid-nav must restore it to the active cell so arrow nav keeps working. (The restore is
// async — it rides the commit's morph — so wait for the cell to regain focus before arrowing; a
// real user can't press a key inside that sub-ms window, but the test can.)
await page.waitForFunction(() => document.activeElement?.classList.contains('grid-cell-active'),
  null, { timeout: 3000 }).catch(() => {});
const afterCommit = await active();
await page.keyboard.press('ArrowDown');
await page.waitForTimeout(150);
check('arrow nav still works after an Enter commit', (await active()) !== afterCommit && (await active()) != null,
  `${afterCommit} → ${await active()}`);

// Regression: Escape out of the description editor must hand the keyboard back to grid-nav (it
// used to leave focus on the hidden input, so descEditing() stayed true and arrows died).
await page.keyboard.press('Enter');
await page.waitForTimeout(150);
check('Enter re-opens the editor',
  await page.evaluate(() => document.activeElement?.classList.contains('description-input')));
await page.keyboard.press('Escape');
await page.waitForTimeout(150);
check('Escape returns focus to the cell (not body)',
  await page.evaluate(() => document.activeElement?.classList.contains('grid-cell-active')));
const beforeArrow = await active();
await page.keyboard.press('ArrowUp');
await page.waitForTimeout(150);
check('arrow nav still works after Escape', (await active()) !== beforeArrow && (await active()) != null,
  `${beforeArrow} → ${await active()}`);

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

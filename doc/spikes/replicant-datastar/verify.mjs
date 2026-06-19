// Browser-driven verification of the spike. Exercises all four experiments in a
// real Chromium via Playwright and prints a PASS/FAIL report + screenshot.
//
// Run from the frontend dir (for module resolution):
//   node ../doc/spikes/replicant-datastar/verify.mjs
import { createRequire } from 'module';
// Resolve Playwright from the frontend's node_modules regardless of cwd.
const require = createRequire('/Users/kira/code/projects/finance-aggregator/frontend/');
const { chromium } = require('@playwright/test');

const URL = 'http://localhost:7777/';
const results = [];
const check = (name, cond, detail = '') =>
  results.push({ name, ok: !!cond, detail });

const browser = await chromium.launch();
const page = await browser.newPage();
const logs = [];
page.on('console', (m) => logs.push(m.text()));
page.on('pageerror', (e) => logs.push('PAGEERROR: ' + e.message));

await page.goto(URL, { waitUntil: 'networkidle' });
await page.waitForTimeout(300); // let Datastar + island initialize

const activeCell = () =>
  page.evaluate(() => document.querySelector('.cell-active')?.dataset.cell ?? null);
const countsText = () =>
  page.evaluate(() => document.getElementById('review-counts').innerText.replace(/\s+/g, ' ').trim());

// ── 0. Stack initialized ───────────────────────────────────────────────────
check('island loaded', logs.some((l) => l.includes('grid-nav island ready')),
  logs.find((l) => l.includes('island')) || '');
check('no page errors', !logs.some((l) => l.startsWith('PAGEERROR')),
  logs.filter((l) => l.startsWith('PAGEERROR')).join('; '));

// Datastar actually bound: an initially-reviewed tx (tx3=true) renders checked.
const tx3checked = await page.evaluate(() => {
  const btn = document.querySelector('[data-cell="3:tx:reviewed"] button.check');
  return btn && btn.classList.contains('checked') && btn.textContent.includes('✓');
});
check('Datastar bound initial state (tx3 checked ✓)', tx3checked);

// ── 1. Optimistic toggle + debounced write-behind + server-authoritative counts
const beforeCounts = await countsText();
const sel = '[data-cell="1:tx:reviewed"] button.check';
const wasChecked = await page.evaluate((s) => document.querySelector(s).classList.contains('checked'), sel);
// Click and measure how fast the optimistic class flips (no round-trip).
const t0 = Date.now();
await page.click(sel);
await page.waitForFunction(
  ({ s, was }) => document.querySelector(s).classList.contains('checked') !== was,
  { s: sel, was: wasChecked }, { timeout: 200 });
const optimisticMs = Date.now() - t0;
check('optimistic flip is instant (<200ms, no round-trip)', optimisticMs < 200, `${optimisticMs}ms`);

// The write-behind (debounced 700ms) hits the server; the SSE patch updates the
// counts chip from server-authoritative state.
await page.waitForFunction(
  (before) => document.getElementById('review-counts').innerText.replace(/\s+/g, ' ').trim() !== before,
  beforeCounts, { timeout: 3000 });
const before = +beforeCounts.match(/(\d+) reviewed/)[1];
const after = +(await countsText()).match(/(\d+) reviewed/)[1];
const expected = before + (wasChecked ? -1 : 1);
check('write-behind → SSE patched counts chip (server-authoritative)', after === expected,
  `${before} → ${after}`);

// ── 3. Client-side search filter (no server round-trip) ─────────────────────
await page.fill('input.search', 'spotify');
await page.waitForTimeout(120);
const visibleRows = await page.evaluate(() =>
  [...document.querySelectorAll('tr.txrow')].filter((r) => r.offsetParent !== null).length);
check('client-side search filters instantly (only Spotify row)', visibleRows === 1, `${visibleRows} rows`);
await page.fill('input.search', '');
await page.waitForTimeout(80);

// ── 4. Spreadsheet keyboard navigation (the crux) ───────────────────────────
// Click a known normal row (tx2) description cell to anchor, then drive keys.
await page.click('[data-cell="2:tx:description"]');
check('click selects a cell', (await activeCell()) === '2:tx:description');

// ArrowRight twice: description → category → reviewed (the 3 editable columns).
await page.keyboard.press('ArrowRight');
const col2 = await activeCell();
await page.keyboard.press('ArrowRight');
const col3 = await activeCell();
check('horizontal arrow nav hops editable columns',
  col2 === '2:tx:category' && col3 === '2:tx:reviewed', `${col2} → ${col3}`);

// ArrowDown keeps the preferred column; landing row must change. (Use the
// description column — clicking a category cell now opens the combobox.)
await page.click('[data-cell="2:tx:description"]');
await page.keyboard.press('ArrowDown');
const downCell = await activeCell();
check('vertical arrow nav preserves column, changes row',
  downCell !== '2:tx:description' && downCell.endsWith(':description'), downCell);

// Latency probe: time a single synchronous ArrowRight in the keydown handler.
const navMs = await page.evaluate(() => {
  const grid = document.getElementById('grid');
  const t = performance.now();
  grid.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
  return performance.now() - t;
});
check('single keystroke nav latency < 5ms (pure client)', navMs < 5, `${navMs.toFixed(2)}ms`);

// Space toggles reviewed via island→Datastar interop, on a known reviewed cell.
await page.click('[data-cell="2:tx:reviewed"]');
const tx2Before = await page.evaluate(() =>
  document.querySelector('[data-cell="2:tx:reviewed"] button.check').classList.contains('checked'));
await page.keyboard.press(' ');
await page.waitForTimeout(60);
const tx2After = await page.evaluate(() =>
  document.querySelector('[data-cell="2:tx:reviewed"] button.check').classList.contains('checked'));
check('Space toggles reviewed (island → Datastar handler)', tx2Before !== tx2After,
  `${tx2Before}→${tx2After}`);

// Enter-to-edit on a known description cell; type, commit; span reflects it.
await page.click('[data-cell="9:tx:description"]');
await page.keyboard.press('Enter');
await page.waitForTimeout(40);
const editing = await page.evaluate(() => {
  const td = document.querySelector('[data-cell="9:tx:description"]');
  return td.classList.contains('editing') &&
         document.activeElement === td.querySelector('input.cell-input');
});
check('Enter opens inline editor & focuses input', editing);
await page.keyboard.type(' EDITED');
await page.keyboard.press('Enter');
await page.waitForTimeout(60);
const spanText = await page.evaluate(() =>
  document.querySelector('[data-cell="9:tx:description"] .cell-view').textContent);
check('inline edit commits optimistically to the cell', spanText.includes('EDITED'), spanText);

// ── 5. Category combobox (the downshift replacement) ────────────────────────
check('combobox island loaded', logs.some((l) => l.includes('combobox island ready')));

// Keyboard handoff: arrow to a category cell, Enter opens the combobox island.
await page.click('[data-cell="5:tx:description"]');
await page.keyboard.press('ArrowRight'); // → 5:tx:category
await page.keyboard.press('Enter');
await page.waitForSelector('.combo-dropdown', { timeout: 1000 });
const comboOpenViaKbd = await page.evaluate(() => window.__comboOpen());
check('Enter on a category cell opens the combobox (grid→combo handoff)', comboOpenViaKbd);
await page.keyboard.press('Escape');
await page.waitForTimeout(40);
check('Escape closes the combobox', !(await page.evaluate(() => window.__comboOpen())));

// Click-open + type-ahead filter + arrow highlight + Enter select.
await page.click('[data-cell="1:tx:category"]');
await page.waitForSelector('.combo-input');
await page.fill('.combo-input', 'dining');
await page.waitForTimeout(40);
const hl = await page.evaluate(() => document.querySelector('.combo-opt.hl')?.textContent);
check('combobox type-ahead filters & highlights', hl === 'Dining', hl);
await page.keyboard.press('Enter');
await page.waitForTimeout(50);
const catText = await page.evaluate(() =>
  document.querySelector('[data-cell="1:tx:category"] .cell-view').textContent);
check('combobox Enter selects → cell updates optimistically', catText === 'Dining', catText);

await page.screenshot({ path: '/tmp/spike-screenshot.png', fullPage: true });

// JSON-API persistence: reload and confirm the server kept the new category.
await page.reload({ waitUntil: 'networkidle' });
await page.waitForTimeout(150);
const catPersisted = await page.evaluate(() =>
  document.querySelector('[data-cell="1:tx:category"] .cell-view').textContent);
check('category persisted via JSON API (survives reload)', catPersisted === 'Dining', catPersisted);

// ── 6. Column show/hide (Datastar signals + CSS) ────────────────────────────
await page.click('button.btn');                    // open Columns menu
await page.click('input[data-bind="cols.account"]'); // uncheck Account
await page.waitForTimeout(40);
const acctColHidden = await page.evaluate(() => {
  const td = document.querySelector('td.col-account');
  return td && getComputedStyle(td).display === 'none';
});
check('column hide toggles a column off (pure client)', acctColHidden);
await page.click('input[data-bind="cols.account"]'); // restore
await page.keyboard.press('Escape');

// ── 7. Header filter by account (Datastar array signal + data-show) ──────────
await page.click('.funnel');
await page.click('input[value="sav"]');            // show only Savings
await page.waitForTimeout(60);
const filterState = await page.evaluate(() => {
  const rowVisible = (cell) => {
    const tr = document.querySelector(`[data-cell="${cell}"]`)?.closest('tr');
    return tr && tr.offsetParent !== null;
  };
  return { chkHidden: !rowVisible('1:tx:description'), savVisible: rowVisible('7:tx:description') };
});
check('header filter shows only matching account (Savings)',
  filterState.chkHidden && filterState.savVisible, JSON.stringify(filterState));
await page.click('input[value="sav"]');            // restore
await page.keyboard.press('Escape');

// ── 8. Column resize (pointer-event island) ─────────────────────────────────
const widthBefore = await page.evaluate(() =>
  document.querySelector('col[data-col="description"]').getBoundingClientRect().width);
const handle = await page.locator('th.col-description .resize-handle').boundingBox();
await page.mouse.move(handle.x + handle.width / 2, handle.y + handle.height / 2);
await page.mouse.down();
await page.mouse.move(handle.x + 120, handle.y + handle.height / 2, { steps: 8 });
await page.mouse.up();
await page.waitForTimeout(40);
const widthAfter = await page.evaluate(() =>
  document.querySelector('col[data-col="description"]').getBoundingClientRect().width);
check('column resize drag widens the column', widthAfter > widthBefore + 60,
  `${Math.round(widthBefore)} → ${Math.round(widthAfter)}px`);

await browser.close();

// ── report ──────────────────────────────────────────────────────────────────
console.log('\n================ SPIKE VERIFICATION ================');
let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? '  [' + r.detail + ']' : ''}`);
  if (r.ok) pass++;
}
console.log(`----------------------------------------------------`);
console.log(`${pass}/${results.length} checks passed`);
console.log('screenshot: /tmp/spike-screenshot.png');
process.exit(pass === results.length ? 0 : 1);

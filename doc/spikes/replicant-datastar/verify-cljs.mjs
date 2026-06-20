// Verifies the CLJS + <combobox-framework> web-component island at ?combo=cljs —
// the mirror of verify-zag.mjs. Proves: shared .cljc runs in the browser, Replicant
// renders the custom element, and the web component delivers WAI-ARIA a11y.
import { createRequire } from 'module';
const require = createRequire('/Users/kira/code/projects/finance-aggregator/frontend/');
const { chromium } = require('@playwright/test');

const URL = 'http://localhost:7777/?combo=cljs';
const results = [];
const check = (name, cond, detail = '') => results.push({ name, ok: !!cond, detail });

const browser = await chromium.launch();
const page = await browser.newPage();
const logs = [];
page.on('console', (m) => logs.push(m.text()));
page.on('pageerror', (e) => logs.push('PAGEERROR: ' + e.message));
await page.goto(URL, { waitUntil: 'networkidle' });
await page.waitForTimeout(400);

check('CLJS island loaded', logs.some((l) => l.includes('cljs combobox island ready')));
check('no page errors', !logs.some((l) => l.startsWith('PAGEERROR')),
  logs.filter((l) => l.startsWith('PAGEERROR')).join('; '));
// The shared .cljc (cents->str) ran in the BROWSER — same code the JVM server uses.
check('shared .cljc runs in the browser (cents->str → -$84.23)',
  logs.some((l) => l.includes('shared .cljc fmt: -$84.23')));

// Keyboard-open path (grid-nav → open-combo CustomEvent): arrow to a category
// cell and type — this crosses the JS→CLJS detail boundary that :advanced renames.
await page.click('[data-cell="3:tx:description"]');
await page.keyboard.press('ArrowRight'); // → 3:tx:category
await page.keyboard.press('a');          // type-to-edit → dispatches open-combo
await page.waitForTimeout(120);
check('keyboard (type-to-edit) opens the combobox — no advanced-compile rename bug',
  await page.evaluate(() => window.__comboOpen() && !!document.querySelector('combobox-framework')));
await page.keyboard.press('Escape');
await page.waitForTimeout(60);

// Open via click; Replicant rendered <combobox-framework>, which provides ARIA.
await page.click('[data-cell="1:tx:category"]');
await page.waitForSelector('combobox-framework input');
const aria = await page.evaluate(() => {
  const cbf = document.querySelector('combobox-framework');
  const input = cbf.querySelector('input'), list = cbf.querySelector('ul'), item = cbf.querySelector('li');
  return { inputRole: input?.getAttribute('role'), controls: !!input?.getAttribute('aria-controls'),
    listRole: list?.getAttribute('role'), itemRole: item?.getAttribute('role') };
});
check('web component emits WAI-ARIA (combobox/listbox/option)',
  aria.inputRole === 'combobox' && aria.controls && aria.listRole === 'listbox' && aria.itemRole === 'option',
  JSON.stringify(aria));

// Type-ahead (Fuse.js inside the WC) — type via real keys so the WC handlers fire.
await page.keyboard.type('tra');
await page.waitForTimeout(150);
const visible = await page.evaluate(() =>
  [...document.querySelectorAll('combobox-framework li')]
    .filter((li) => li.offsetParent !== null && !li.hasAttribute('hidden'))
    .map((li) => li.textContent.replace(/\s+/g, ' ').trim()));
check('type-ahead filters to Transport/Transfer',
  visible.length === 2 && visible.every((t) => /Transp|Transf/.test(t)), JSON.stringify(visible));

// Arrow → the WC tracks the highlighted option via aria-selected + moves focus
// into the listbox (the other valid WAI-ARIA combobox pattern vs. Zag's
// aria-activedescendant). Assert the highlight tracks.
await page.keyboard.press('ArrowDown');
await page.waitForTimeout(60);
const ad = await page.evaluate(() => {
  const sel = document.querySelector('combobox-framework li[aria-selected="true"]');
  const focused = document.activeElement;
  return { selText: sel?.textContent?.trim(), focusInList: focused?.tagName === 'LI' || focused?.closest?.('combobox-framework') != null };
});
check('arrow highlights an option (aria-selected tracks)', !!ad.selText, JSON.stringify(ad));

// Enter selects → cell updates + persists via JSON API.
await page.keyboard.press('Enter');
await page.waitForTimeout(100);
const catText = await page.evaluate(() =>
  document.querySelector('[data-cell="1:tx:category"] .cell-view').textContent.trim());
check('Enter selects → cell updates optimistically', /Transp|Transf/.test(catText), catText);

// JSON-API persistence survives reload.
await page.reload({ waitUntil: 'networkidle' });
await page.waitForTimeout(150);
const persisted = await page.evaluate(() =>
  document.querySelector('[data-cell="1:tx:category"] .cell-view').textContent.trim());
check('category persisted via JSON API (survives reload)', /Transp|Transf/.test(persisted), persisted);

// Tab closes and hands focus back to the grid.
await page.click('[data-cell="6:tx:category"]');
await page.waitForSelector('combobox-framework');
await page.keyboard.press('Tab');
await page.waitForTimeout(80);
const tab = { open: await page.evaluate(() => window.__comboOpen()),
  active: await page.evaluate(() => document.querySelector('.cell-active')?.dataset.cell) };
check('Tab closes & moves to next grid cell', !tab.open && tab.active === '6:tx:reviewed', JSON.stringify(tab));

await browser.close();
console.log('\n========= CLJS + WEB-COMPONENT COMBOBOX =========');
let pass = 0;
for (const r of results) { console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? '  [' + r.detail + ']' : ''}`); if (r.ok) pass++; }
console.log(`------------------------------------------------\n${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

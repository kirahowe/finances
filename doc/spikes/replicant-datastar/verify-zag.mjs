// Verifies the Zag.js (vanilla adapter) combobox island at ?combo=zag — focusing
// on the a11y the hand-rolled version omits (virtual focus via aria-activedescendant).
import { createRequire } from 'module';
const require = createRequire('/Users/kira/code/projects/finance-aggregator/frontend/');
const { chromium } = require('@playwright/test');

const URL = 'http://localhost:7777/?combo=zag';
const results = [];
const check = (name, cond, detail = '') => results.push({ name, ok: !!cond, detail });

const browser = await chromium.launch();
const page = await browser.newPage();
const logs = [];
page.on('console', (m) => logs.push(m.text()));
page.on('pageerror', (e) => logs.push('PAGEERROR: ' + e.message));
await page.goto(URL, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);

check('zag island loaded', logs.some((l) => l.includes('zag combobox island ready')));
check('no page errors', !logs.some((l) => l.startsWith('PAGEERROR')),
  logs.filter((l) => l.startsWith('PAGEERROR')).join('; '));

// Open via click, assert the WAI-ARIA combobox roles/attrs Zag provides for free.
await page.click('[data-cell="1:tx:category"]');
await page.waitForSelector('.zag-combobox .combobox-content');
const aria = await page.evaluate(() => {
  const input = document.querySelector('.zag-combobox .combobox-input');
  const content = document.querySelector('.zag-combobox .combobox-content');
  const item = document.querySelector('.zag-combobox .combobox-item');
  return {
    inputRole: input?.getAttribute('role'), expanded: input?.getAttribute('aria-expanded'),
    controls: !!input?.getAttribute('aria-controls'),
    contentRole: content?.getAttribute('role'), itemRole: item?.getAttribute('role'),
  };
});
check('Zag emits WAI-ARIA roles (combobox/listbox/option + aria-expanded/controls)',
  aria.inputRole === 'combobox' && aria.expanded === 'true' && aria.controls &&
  aria.contentRole === 'listbox' && aria.itemRole === 'option', JSON.stringify(aria));

// Type-ahead filter.
await page.fill('.zag-combobox .combobox-input', 'tra');
await page.waitForTimeout(80);
const filtered = await page.evaluate(() =>
  [...document.querySelectorAll('.zag-combobox .combobox-item')].map((li) => li.textContent));
check('type-ahead filters the list', filtered.join(',') === 'Transport,Transfer', filtered.join(','));

// THE a11y differentiator: arrow moves a virtual cursor and the input's
// aria-activedescendant tracks the highlighted option's id (screen-reader focus).
await page.keyboard.press('ArrowDown');
await page.waitForTimeout(60);
const vfocus = await page.evaluate(() => {
  const input = document.querySelector('.zag-combobox .combobox-input');
  const hl = document.querySelector('.zag-combobox .combobox-item[data-highlighted]');
  return { activedescendant: input?.getAttribute('aria-activedescendant'), hlId: hl?.id, hlText: hl?.textContent };
});
check('aria-activedescendant tracks the highlighted option (virtual focus)',
  vfocus.activedescendant && vfocus.activedescendant === vfocus.hlId, JSON.stringify(vfocus));

// Enter selects the highlighted option → cell updates optimistically + persists.
await page.keyboard.press('Enter');
await page.waitForTimeout(80);
const catText = await page.evaluate(() =>
  document.querySelector('[data-cell="1:tx:category"] .cell-view').textContent);
check('Enter selects highlighted → cell updates', catText === vfocus.hlText, `${catText} vs ${vfocus.hlText}`);

// Escape closes.
await page.click('[data-cell="2:tx:category"]');
await page.waitForSelector('.zag-combobox');
await page.keyboard.press('Escape');
await page.waitForTimeout(60);
check('Escape closes', !(await page.evaluate(() => window.__comboOpen())));

// Tab closes and hands focus back to the grid (app glue), moving to next cell.
await page.click('[data-cell="6:tx:category"]');
await page.waitForSelector('.zag-combobox');
await page.keyboard.press('Tab');
await page.waitForTimeout(60);
const tab = { open: await page.evaluate(() => window.__comboOpen()),
  active: await page.evaluate(() => document.querySelector('.cell-active')?.dataset.cell) };
check('Tab closes & moves to next grid cell', !tab.open && tab.active === '6:tx:reviewed', JSON.stringify(tab));

await browser.close();
console.log('\n============ ZAG COMBOBOX VERIFICATION ============');
let pass = 0;
for (const r of results) { console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? '  [' + r.detail + ']' : ''}`); if (r.ok) pass++; }
console.log(`--------------------------------------------------\n${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

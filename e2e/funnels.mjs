// Browser-driven verification of the header-filter funnels (Phase 3d): per-column
// Account / Institution / Category multi-select dropdowns. Each is a floating
// (position:fixed) popover escaping the table overflow; selections drive instant
// Datastar data-show with no round-trip. The category funnel composes with the
// Uncategorized toolbar chip as a union.
//   BASE_URL=http://localhost:8099 node e2e/funnels.mjs
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

await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
const visible = () => page.locator('table.table tbody tr:visible').count();
const funnelBtn = (label) => page.locator(`.th-filter-btn[aria-label="Filter ${label}"]`);
const openPopover = () => page.locator('.header-filter-popover:visible');
const num = async (loc) => parseInt((await loc.innerText()).trim(), 10);

// 1. Three funnels exist (Account / Institution / Category); none open initially.
check('three funnel buttons present', (await page.locator('.th-filter-btn').count()) === 3);
check('account funnel button present', await funnelBtn('Account').isVisible());
check('no popover open initially', (await openPopover().count()) === 0);
check('all 10 rows visible initially', (await visible()) === 10, `${await visible()}`);

// 2. Opening the Account funnel reveals its popover with counted options.
await funnelBtn('Account').click();
await page.waitForTimeout(60);
const acctPop = openPopover();
check('clicking opens the Account popover', (await acctPop.count()) === 1);
const acctOpts = acctPop.locator('.filter-dropdown-item');
check('account options listed', (await acctOpts.count()) >= 2, `${await acctOpts.count()}`);

// 3. Selecting one value filters to that value's count; the button goes active + badged.
const c1 = await num(acctOpts.nth(0).locator('.filter-dropdown-count'));
await acctOpts.nth(0).locator('.filter-dropdown-checkbox').click();
await page.waitForTimeout(80);
check('selecting one account → that count of rows', (await visible()) === c1, `${await visible()} vs ${c1}`);
check('account funnel button is active', (await funnelBtn('Account').getAttribute('class')).includes('is-active'));
check('button count badge shows 1', (await funnelBtn('Account').locator('.th-filter-count').innerText()).trim() === '1');

// 4. A second value ORs within the column (sum of both counts).
const c2 = await num(acctOpts.nth(1).locator('.filter-dropdown-count'));
await acctOpts.nth(1).locator('.filter-dropdown-checkbox').click();
await page.waitForTimeout(80);
check('selecting a second account → OR (sum of counts)', (await visible()) === c1 + c2, `${await visible()} vs ${c1 + c2}`);
check('badge shows 2 selected', (await funnelBtn('Account').locator('.th-filter-count').innerText()).trim() === '2');

// 5. Clear empties the funnel.
await acctPop.locator('.filter-dropdown-clear').click();
await page.waitForTimeout(80);
check('Clear → all 10 rows again', (await visible()) === 10, `${await visible()}`);
check('button no longer active after Clear', !(await funnelBtn('Account').getAttribute('class')).includes('is-active'));

// 6. In-funnel search narrows the option list.
const before = await acctOpts.count();
const firstLabel = (await acctOpts.nth(0).locator('.filter-dropdown-label-text').innerText()).trim();
await acctPop.locator('.filter-dropdown-search').fill(firstLabel.slice(0, 3));
await page.waitForTimeout(80);
const shown = await acctPop.locator('.filter-dropdown-item:visible').count();
check('in-funnel search narrows options', shown >= 1 && shown <= before, `${shown}/${before}`);
await acctPop.locator('.filter-dropdown-search').fill('');

// 7. Switching funnels: opening Institution closes Account.
await funnelBtn('Institution').click();
await page.waitForTimeout(60);
check('opening Institution closes Account (one popover open)', (await openPopover().count()) === 1);
check('aria-expanded tracks the open funnel',
  (await funnelBtn('Institution').getAttribute('aria-expanded')) === 'true'
    && (await funnelBtn('Account').getAttribute('aria-expanded')) !== 'true');

// 8. Outside click closes the open funnel.
await page.locator('.masthead, h1, body').first().click({ position: { x: 5, y: 5 } });
await page.waitForTimeout(80);
check('outside click closes the funnel', (await openPopover().count()) === 0);

// 9. Category funnel: selecting one category filters to its count (split-aware tokens).
await funnelBtn('Category').click();
await page.waitForTimeout(60);
const catPop = openPopover();
const catItem = catPop.locator('.filter-dropdown-item').nth(0);
const catCount = await num(catItem.locator('.filter-dropdown-count'));
await catItem.locator('.filter-dropdown-checkbox').click();
await page.waitForTimeout(80);
check('selecting one category → that count of rows', (await visible()) === catCount, `${await visible()} vs ${catCount}`);

// 10. Union with the Uncategorized chip: category-selected rows OR uncategorized rows.
await page.locator('.count-chip', { hasText: 'Uncategorized' }).click();
await page.waitForTimeout(80);
check('category funnel ∪ Uncategorized chip (union, not intersection)',
  (await visible()) === catCount + 6, `${await visible()} vs ${catCount + 6}`);

// (clicking the chip above is an outside-click that closes the funnel — expected; the
// category selection still stands in the signal, so re-open it to Clear.)
check('clicking the chip closed the open funnel', (await openPopover().count()) === 0);

// 11. Resetting both restores all rows.
await page.locator('.count-chip', { hasText: 'Uncategorized' }).click(); // un-toggle uncat
await funnelBtn('Category').click(); // re-open to reach Clear
await page.waitForTimeout(60);
await openPopover().locator('.filter-dropdown-clear').click();
await page.waitForTimeout(80);
check('clearing category + uncat → all 10 rows', (await visible()) === 10, `${await visible()}`);

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

// Real-Chromium proof of the category rollup pane: a whole-period breakdown beside the table
// (sections + Net), where clicking a category row filters the table to it (and toggles off),
// reusing the funnel filter signals; the clicked row highlights and an active-filter chip
// appears. The amounts are computed server-side (web.view/category-rollup, tested) over the
// SCOPING-filtered rows: search / account-institution funnels / hide-transfers narrow the
// pane ("which money are we looking at" follows the table), while its own DRILL axes — the
// category filter, the Uncategorized chip, the To-reconcile scope — never move its sums,
// since the pane IS the navigation control for those.
//
//   BASE_URL=http://localhost:8100 node e2e/v2-rollup.ts
import { chromium } from '@playwright/test';

const BASE = process.env.BASE_URL || 'http://localhost:8100';
const results: { name: string; ok: boolean; detail: string }[] = [];
const check = (name: string, ok: unknown, detail: unknown = ''): void => {
  results.push({ name, ok: !!ok, detail: detail == null ? '' : String(detail) });
};

const browser = await chromium.launch();
const page = await browser.newPage();
const logs: string[] = [];
page.on('pageerror', (e) => logs.push('PAGEERROR: ' + e.message));

await page.request.post(`${BASE}/e2e/reset`);
await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
check('no page errors', !logs.length, logs.join('; '));

const rollup = page.locator('#category-rollup');
const rows = () => page.locator('#tx-tbody tr');

check('rollup pane renders beside the table', await rollup.isVisible());
check('has an Income section', (await rollup.locator('.rollup-section-title', { hasText: 'Income' }).count()) === 1);
check('has an Expenses section', (await rollup.locator('.rollup-section-title', { hasText: 'Expenses' }).count()) === 1);
check('has a Net line', (await rollup.locator('.rollup-net .rollup-amount').count()) === 1);

// Groceries is a childless expense leaf — the seed's one Groceries tx is Superstore -$85.
const groceries = rollup.locator('.rollup-row', { hasText: 'Groceries' }).first();
check('Groceries row shows $85.00',
  (await groceries.locator('.rollup-amount').innerText()).includes('85.00'));

// Click it → the table filters to Groceries (just Superstore), the row highlights, a chip appears.
const before = await rows().count();
check('the month starts with more than one row', before > 1, `rows=${before}`);
await groceries.locator('.rollup-row-button').click();
await page.waitForFunction((b) => document.querySelectorAll('#tx-tbody tr').length < b, before, { timeout: 5000 }).catch(() => {});
check('clicking Groceries filters the table to one row', (await rows().count()) === 1, `rows=${await rows().count()}`);
check('the one row is Superstore', (await rows().filter({ hasText: 'Superstore' }).count()) === 1);
check('the Groceries row is now active', await groceries.evaluate((el) => el.classList.contains('is-active')));
check('a Category filter chip appears',
  (await page.locator('#active-filters .active-chip', { hasText: 'Groceries' }).count()) === 1);

// Click again → toggles the filter off, the table returns.
await groceries.locator('.rollup-row-button').click();
await page.waitForFunction((b) => document.querySelectorAll('#tx-tbody tr').length >= b, before, { timeout: 5000 }).catch(() => {});
check('clicking Groceries again clears the filter', (await rows().count()) === before, `rows=${await rows().count()}`);
check('the Groceries row is no longer active', !(await groceries.evaluate((el) => el.classList.contains('is-active'))));

// --- Drill axes are IGNORED: the pane is the navigation control for its own category axis,
// so filtering BY category must not collapse it — Salary (a different category) stays in the
// pane, at unchanged amounts, while the table narrows to Groceries.
const salaryRow = () => rollup.locator('.rollup-row', { hasText: 'Salary' });
await groceries.locator('.rollup-row-button').click();
await page.waitForFunction((b) => document.querySelectorAll('#tx-tbody tr').length < b, before, { timeout: 5000 }).catch(() => {});
check('category drill: table narrows to the one Groceries row', (await rows().count()) === 1, `rows=${await rows().count()}`);
check('category drill: the Salary income row STAYS in the pane', (await salaryRow().count()) === 1);
check('category drill: Groceries amount unchanged ($85.00)',
  (await groceries.locator('.rollup-amount').innerText()).includes('85.00'));
await groceries.locator('.rollup-row-button').click();
await page.waitForFunction((b) => document.querySelectorAll('#tx-tbody tr').length >= b, before, { timeout: 5000 }).catch(() => {});

// --- SCOPING filters are FOLLOWED (search): narrowing to Superstore re-buckets the pane to
// just its money — Salary leaves, Groceries stays — and clearing the search restores it.
const paneHasSalary = (want: boolean) =>
  page.waitForFunction((w) => w === /Salary/.test(document.querySelector('#category-rollup')?.textContent || ''),
    want, { timeout: 5000 }).catch(() => {});
await page.locator('.table-search-input').fill('Superstore');
await paneHasSalary(false);
check('search scopes the pane: the Salary row leaves', (await salaryRow().count()) === 0);
check('search scopes the pane: Groceries $85.00 stays',
  (await groceries.locator('.rollup-amount').innerText()).includes('85.00'));
await page.locator('.table-search-input').fill('');
await paneHasSalary(true);
check('clearing the search restores the pane (Salary returns)', (await salaryRow().count()) === 1);

// --- SCOPING filters are FOLLOWED (account funnel): Visa's slice is Groceries -$85 plus the
// uncategorized +$300 transfer leg; Chequing's Salary leaves the pane.
await page.getByRole('button', { name: 'Filter Account' }).click();
const accountPopover = page.locator('.header-filter-popover', { hasText: 'Visa' });
await accountPopover.waitFor({ state: 'visible', timeout: 3000 });
const visaCheckbox = accountPopover.locator('.filter-dropdown-item',
  { has: page.locator('.filter-dropdown-label-text', { hasText: 'Visa' }) }).locator('input');
await visaCheckbox.check();
await paneHasSalary(false);
check('account funnel scopes the pane: Salary leaves', (await salaryRow().count()) === 0);
check('account funnel scopes the pane: Groceries $85.00 stays',
  (await groceries.locator('.rollup-amount').innerText()).includes('85.00'));
const incomeSection = rollup.locator('.rollup-section', { has: page.locator('.rollup-section-title', { hasText: 'Income' }) });
check('account funnel scopes the pane: the Visa transfer leg reads Uncategorized income $300.00',
  (await incomeSection.locator('.rollup-row', { hasText: 'Uncategorized' }).locator('.rollup-amount').innerText()).includes('300.00'));
await visaCheckbox.uncheck();
await paneHasSalary(true);
check('clearing the account funnel restores the pane', (await salaryRow().count()) === 1);
await page.keyboard.press('Escape');

check('still no page errors', !logs.length, logs.join('; '));

await browser.close();

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

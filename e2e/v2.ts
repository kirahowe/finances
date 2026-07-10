// Real-Chromium proof for the Phase-R2 server-authoritative page (/v2). Every view
// change round-trips: search / sort fire @get('/v2/rows'), the server runs the pure view
// engine, and the tbody + pagination bar morph back. Proves the interaction model end to
// end (escaped Datastar expressions firing + SSE morph), not just the fragment shape.
//
//   BASE_URL=http://localhost:8099 node e2e/v2.ts
import { chromium } from '@playwright/test';

const BASE = process.env.BASE_URL || 'http://localhost:8099';
const results: { name: string; ok: boolean; detail: string }[] = [];
const check = (name: string, ok: unknown, detail: unknown = ''): void => {
  results.push({ name, ok: !!ok, detail: detail == null ? '' : String(detail) });
};

const browser = await chromium.launch();
const page = await browser.newPage();
const logs: string[] = [];
page.on('console', (m) => logs.push(m.text()));
page.on('pageerror', (e) => logs.push('PAGEERROR: ' + e.message));

const rowCount = () => page.locator('#tx-tbody tr').count();
const firstAmount = () =>
  page.locator('#tx-tbody tr').first().locator('.amount-cell .numeric').innerText();

await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);

check('no page errors', !logs.some((l) => l.startsWith('PAGEERROR')),
  logs.filter((l) => l.startsWith('PAGEERROR')).join('; '));
check('initial render: 10 rows', (await rowCount()) === 10, `rows=${await rowCount()}`);
check('pagination status rendered', (await page.locator('#pagination-bar .pagination-status').innerText()).includes('Page 1 of 1'));

// Search → server filters → morph (debounced 300ms).
await page.locator('.table-search-input').fill('Superstore');
await page.waitForFunction(() => document.querySelectorAll('#tx-tbody tr').length === 1, null, { timeout: 5000 }).catch(() => {});
check('server-side search → 1 row', (await rowCount()) === 1, `rows=${await rowCount()}`);

await page.locator('.table-search-input').fill('');
await page.waitForFunction(() => document.querySelectorAll('#tx-tbody tr').length === 10, null, { timeout: 5000 }).catch(() => {});
check('cleared search → 10 rows', (await rowCount()) === 10, `rows=${await rowCount()}`);

// Sort by Amount: asc → most negative first, desc → most positive first.
const amountTh = page.locator('th[data-col-id="amount"]');
await amountTh.click();
await page.waitForFunction(() => document.querySelector('#tx-tbody tr .amount-cell .numeric')?.textContent?.includes('2,000'), null, { timeout: 5000 }).catch(() => {});
check('sort amount asc → first row -$2,000.00', (await firstAmount()).includes('-$2,000.00'), await firstAmount());

await amountTh.click();
await page.waitForFunction(() => document.querySelector('#tx-tbody tr .amount-cell .numeric')?.textContent?.includes('4,000'), null, { timeout: 5000 }).catch(() => {});
check('sort amount desc → first row $4,000.00', (await firstAmount()).includes('$4,000.00'), await firstAmount());

// A fresh table defaults to date-ascending, with the Date header showing it (blank $sortCol
// is the canonical encoding — see web.view-state/default-sort).
await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
const dateTh = page.locator('th[data-col-id="date"]');
check('default sort: Date header reads ascending (aria-sort)',
  (await dateTh.getAttribute('aria-sort')) === 'ascending');
check('default sort: the Date header shows the ascending arrow',
  /↑/.test((await dateTh.locator('.th-sort-indicator').first().innerText()) || ''));
const firstDate = () => page.locator('#tx-tbody tr').first().locator('.date-cell .numeric').innerText();
check('default sort: first row is the earliest date (Jan 1)', (await firstDate()).trim() === 'Jan 1', await firstDate());

// Two-level sort: click Payee then Date — per the header-click semantics, the SECOND click's
// column always becomes the new primary and demotes whatever was primary to secondary, so this
// sequence lands on date-primary / payee-secondary ("sort by Payee, then by Date"). The seed has
// two same-day pairs (Jan 12: Visa Payment/Payment Received; Jan 15: Mortgage Payment/Mortgage
// Principal) whose payee order breaks the date tie deterministically.
const payeeTh = page.locator('th[data-col-id="payee"]');
const payees = () => page.locator('#tx-tbody tr .payee-cell').allInnerTexts();
await payeeTh.click();
await page.waitForTimeout(300);
await dateTh.click();
await page.waitForFunction(
  () => document.querySelectorAll('#tx-tbody tr').length === 10, null, { timeout: 5000 }).catch(() => {});
await page.waitForTimeout(300);
const sortedPayees = (await payees()).map((p) => p.trim());
check('two-level sort: Jan 12 tie breaks by payee (Payment Received before Visa Payment)',
  sortedPayees[4] === 'Payment Received' && sortedPayees[5] === 'Visa Payment',
  JSON.stringify(sortedPayees));
check('two-level sort: Jan 15 tie breaks by payee (Mortgage Payment before Mortgage Principal)',
  sortedPayees[6] === 'Mortgage Payment' && sortedPayees[7] === 'Mortgage Principal',
  JSON.stringify(sortedPayees));
check('date TH now reads the primary sort (ascending)',
  (await dateTh.getAttribute('aria-sort')) === 'ascending');
check('payee TH carries the muted secondary indicator',
  (await payeeTh.locator('.th-sort-indicator--secondary').innerText()).includes('↑'));

// Clicking the (now-primary) Date header again cycles to descending; a third click clears back
// to the promoted secondary (payee) as the new primary — proving desc→clear promotes sort2.
await dateTh.click();
await page.waitForTimeout(300);
check('date primary asc → desc on a second click', (await dateTh.getAttribute('aria-sort')) === 'descending');
await dateTh.click();
await page.waitForTimeout(300);
check('date primary desc → clear promotes the old secondary (payee) to primary',
  (await payeeTh.getAttribute('aria-sort')) === 'ascending');

// Month-nav persistence: a search filter + sort survive navigating to the next month (a
// different month's row set restarts at page 0).
await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
await page.locator('.table-search-input').fill('Payroll');
await page.waitForTimeout(400);
await page.locator('th[data-col-id="amount"]').click();
await page.waitForTimeout(300);
const beforeNav = new URL(page.url());
check('search + sort reflected in the URL before navigating',
  beforeNav.searchParams.get('q') === 'Payroll' && beforeNav.searchParams.get('sortCol') === 'amount',
  beforeNav.search);
await Promise.all([
  page.waitForURL(/month=2025-02/, { timeout: 5000 }),
  page.locator('.month-nav-button[title="Next month"]').click(),
]);
await page.waitForLoadState('networkidle');
const afterNav = new URL(page.url());
check('month advanced to 2025-02', afterNav.searchParams.get('month') === '2025-02', afterNav.search);
check('search filter persisted across the month change', afterNav.searchParams.get('q') === 'Payroll', afterNav.search);
check('sort persisted across the month change', afterNav.searchParams.get('sortCol') === 'amount', afterNav.search);
check('page param reset across the month change', !afterNav.searchParams.get('page'), afterNav.search);
check('the search box itself reflects the persisted filter after reload',
  (await page.locator('.table-search-input').inputValue()) === 'Payroll');

await browser.close();

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

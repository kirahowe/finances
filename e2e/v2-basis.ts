// Real-Chromium proof for the date-basis lens: a Posted/Transaction toggle in the period
// picker that re-buckets the TABLE, ROLLUP, and COUNTS by transaction date instead of the
// canonical effective posted date. It's a display lens like the range period: the URL carries
// `basis=transaction` (blank = posted default, url island convention), every switch is an
// in-place SSE view change (@get /transactions/period — the __marker checks prove no reload),
// and reconciliation stays pinned to posted dates — in transaction basis the monthly-close
// panel renders a quiet "Monthly close works on posted dates." note (like range view's note)
// instead of the live panel, and the statement lens is inert.
//
// The seed's proof row is `seed-basis-straddle` ("Hotel Deposit", Visa, -$42.00): POSTED
// Feb 2 (so it's invisible to every posted-basis 2025-01 view) but DATED Jan 30 (so it appears
// in January only under the transaction basis). Read-only throughout (only navigates + toggles
// client-side UI signals), so no /e2e/reset is needed — nothing here mutates persisted data.
//
//   BASE_URL=http://localhost:8099 node e2e/v2-basis.ts
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

const toggle = () => page.locator('#period-toggle');
const popover = () => page.locator('#period-picker');
const basisRow = () => page.locator('.period-picker-basis');
const postedBtn = () => basisRow().getByRole('button', { name: 'Posted' });
const txBtn = () => basisRow().getByRole('button', { name: 'Transaction' });
const basisTag = () => page.locator('.period-basis-tag');
const rowCount = () => page.locator('#tx-tbody tr').count();
const isActive = async (loc: ReturnType<typeof postedBtn>) =>
  ((await loc.getAttribute('class')) || '').includes('is-active');
// The rollup's Expenses section and its Uncategorized row (where the straddler's -$42 lands,
// on top of the seed's uncategorized out-1/-500 + out-2/-300 = $800.00 posted baseline).
const expensesSection = () =>
  page.locator('.rollup-section', { has: page.locator('.rollup-section-title', { hasText: 'Expenses' }) });
const uncatExpense = () => expensesSection().locator('.rollup-row', { hasText: 'Uncategorized' });
// The no-reload proof: a full page load wipes window.__marker; every in-place basis/period
// change must leave it intact. Re-armed after each deliberate page.goto below.
const armMarker = () => page.evaluate(() => ((window as any).__marker = 1));
const marker = () => page.evaluate(() => (window as any).__marker);
const waitForRows = (n: number) =>
  page.waitForFunction((want) => document.querySelectorAll('#tx-tbody tr').length === want,
    n, { timeout: 5000 }).catch(() => {});

// --- 1. Fresh load (posted default): the straddler is invisible; the picker carries the
// basis control with Posted active; the dateline tag is hidden. -----------------------------
await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
await armMarker();
check('no page errors on load', !logs.some((l) => l.startsWith('PAGEERROR')), logs.join('; '));
check('posted default: 10 rows in January', (await rowCount()) === 10, `rows=${await rowCount()}`);
check('posted default: Hotel Deposit (posted Feb 2) is NOT in January',
  !/Hotel Deposit/.test(await page.locator('#tx-tbody').innerText()));
check('the dateline basis tag is hidden under the posted default', !(await basisTag().isVisible()));

await toggle().click();
check('the picker carries the basis control (.period-picker-basis)', (await basisRow().count()) === 1);
check('Posted is active by default', await isActive(postedBtn()));
check('Transaction is not active by default', !(await isActive(txBtn())));
check('Posted mirrors aria-pressed=true', (await postedBtn().getAttribute('aria-pressed')) === 'true');
check('Transaction mirrors aria-pressed=false', (await txBtn().getAttribute('aria-pressed')) === 'false');

// --- 2. Click Transaction: an in-place SSE re-bucket — the URL gains basis=transaction (the
// url island reflects the signal), the table becomes 11 rows including the straddler, the
// dateline tag shows, and the popover STAYS OPEN across the period morph ($_periodOpen is an
// ephemeral client signal that survives the #period-navigator morph — the regression-prone
// bit). No reload: __marker survives. --------------------------------------------------------
await Promise.all([
  page.waitForURL(/basis=transaction/, { timeout: 5000 }),
  txBtn().click(),
]);
await waitForRows(11);
check('transaction basis: 11 rows in January', (await rowCount()) === 11, `rows=${await rowCount()}`);
check('transaction basis: Hotel Deposit appears (dated Jan 30)',
  /Hotel Deposit/.test(await page.locator('#tx-tbody').innerText()));
check('the straddler row leads with its Jan 30 transaction date',
  /Jan 30/.test(await page.locator('#tx-tbody tr', { hasText: 'Hotel Deposit' }).innerText()));
check('the dateline shows the visible "transaction dates" tag',
  (await basisTag().isVisible()) && (await basisTag().innerText()).trim() === 'transaction dates',
  await basisTag().innerText());
check('the popover STAYS OPEN across the morph: aria-expanded stays "true"',
  (await toggle().getAttribute('aria-expanded')) === 'true');
check('the popover stays visible', await popover().isVisible());
check('Transaction is now active', await isActive(txBtn()));
check('Posted is no longer active', !(await isActive(postedBtn())));
check('the switch does NOT reload the page (__marker survives)', (await marker()) === 1, String(await marker()));
const afterSwitch = new URL(page.url());
check('URL carries basis=transaction', afterSwitch.searchParams.get('basis') === 'transaction', afterSwitch.search);
check('page param is absent after the switch', !afterSwitch.searchParams.get('page'), afterSwitch.search);

// --- 3. The reconcile panel is the quiet basis note, not the live panel. --------------------
await page.locator('.reconcile-range-note').waitFor({ state: 'visible', timeout: 5000 });
check('.reconcile-range-note carries the exact basis copy',
  (await page.locator('.reconcile-range-note').innerText()).trim() === 'Monthly close works on posted dates.',
  await page.locator('.reconcile-range-note').innerText());
check('the switch-back button reads "Switch to posted dates"',
  (await page.locator('.reconcile-basis-back').innerText()).trim() === 'Switch to posted dates');

// --- 4. The rollup re-buckets too: the straddler's uncategorized -$42.00 joins the Expenses
// section's Uncategorized row ($800.00 posted baseline -> $842.00 under the lens). -----------
await page.waitForFunction(() => /842\.00/.test(document.querySelector('#category-rollup')?.textContent || ''),
  null, { timeout: 5000 }).catch(() => {});
check('Expenses has an Uncategorized rollup row', (await uncatExpense().count()) === 1);
check('the Uncategorized expenses row includes the straddler ($842.00)',
  (await uncatExpense().locator('.rollup-amount').innerText()).includes('842.00'),
  await uncatExpense().locator('.rollup-amount').innerText());

// --- 5. "Switch to posted dates" (the panel's note button) flips back in place: 10 rows, the
// basis param drops from the URL, the REAL reconcile panel returns, tag hides, no reload. ----
await page.keyboard.press('Escape'); // close the picker so nothing occludes the summary column
check('picker closed before the switch-back', (await toggle().getAttribute('aria-expanded')) === 'false');
await Promise.all([
  page.waitForURL((url) => !url.searchParams.has('basis'), { timeout: 5000 }),
  page.locator('.reconcile-basis-back').click(),
]);
await waitForRows(10);
await page.locator('.reconcile-rows').waitFor({ state: 'attached', timeout: 5000 });
check('back to 10 posted rows', (await rowCount()) === 10, `rows=${await rowCount()}`);
check('basis param gone from the URL', !new URL(page.url()).searchParams.has('basis'), page.url());
check('the real reconcile panel is restored (overview rows render)',
  (await page.locator('.reconcile-rows .reconcile-row').count()) > 0);
check('the gate/Close month controls are back', /Close month/.test(await page.locator('#reconciliation').innerText()));
check('the basis note is gone', (await page.locator('.reconcile-range-note').count()) === 0);
check('the dateline tag is hidden again', !(await basisTag().isVisible()));
check('the switch-back does NOT reload the page (__marker survives)', (await marker()) === 1, String(await marker()));
await page.waitForFunction(() => /800\.00/.test(document.querySelector('#category-rollup')?.textContent || ''),
  null, { timeout: 5000 }).catch(() => {});
check('the Uncategorized expenses row is back to the posted baseline ($800.00)',
  (await uncatExpense().locator('.rollup-amount').innerText()).includes('800.00'),
  await uncatExpense().locator('.rollup-amount').innerText());

// --- 6. Cross-month, both directions. Transaction-basis February does NOT hold the straddler
// (it's dated Jan 30) — and with nothing else in February the fresh load renders the empty
// state; the URL-seeded basis persists (Transaction active, tag visible). Posted February DOES
// hold it (posted Feb 2). ---------------------------------------------------------------------
await page.goto(`${BASE}/?month=2025-02&basis=transaction`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
check('transaction-basis February: Hotel Deposit is NOT there',
  !/Hotel Deposit/.test(await page.locator('.transactions-layout').innerText()));
check('transaction-basis February is empty (the straddler was its only posted row)',
  (await page.locator('.empty-state-title').innerText().catch(() => '')).trim() === 'No transactions this month');
check('the URL-seeded basis shows the dateline tag', await basisTag().isVisible());
await toggle().click();
check('the URL-seeded basis marks Transaction active in the picker', await isActive(txBtn()));
await page.keyboard.press('Escape');

await page.goto(`${BASE}/?month=2025-02`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
check('posted February holds the straddler (1 row)', (await rowCount()) === 1, `rows=${await rowCount()}`);
check('posted February: the row is Hotel Deposit',
  /Hotel Deposit/.test(await page.locator('#tx-tbody').innerText()));

// --- 7. Persistence: a full reload of the basis URL restores the lens server-side; the
// prev/next arrows (period-nav-js reads location.search) carry basis across period changes. --
await page.goto(`${BASE}/?month=2025-01&basis=transaction`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
await armMarker();
check('full reload of the basis URL: 11 rows', (await rowCount()) === 11, `rows=${await rowCount()}`);
check('full reload: the dateline tag is visible', await basisTag().isVisible());
await toggle().click();
check('full reload: Transaction is active in the picker', await isActive(txBtn()));
await page.keyboard.press('Escape');

await Promise.all([
  page.waitForURL(/month=2025-02/, { timeout: 5000 }),
  page.locator('.month-nav-button[title="Next month"]').click(),
]);
await waitForRows(0);
const afterArrow = new URL(page.url());
check('next arrow preserves basis=transaction in the URL',
  afterArrow.searchParams.get('basis') === 'transaction', afterArrow.search);
check('next arrow keeps the lens: transaction-basis February morphs to 0 rows',
  (await rowCount()) === 0, `rows=${await rowCount()}`);
check('arrow nav does NOT reload the page (__marker survives)', (await marker()) === 1, String(await marker()));
await Promise.all([
  page.waitForURL(/month=2025-01/, { timeout: 5000 }),
  page.locator('.month-nav-button[title="Previous month"]').click(),
]);
await waitForRows(11);
check('previous arrow returns to the 11-row transaction-basis January',
  (await rowCount()) === 11 && new URL(page.url()).searchParams.get('basis') === 'transaction',
  page.url());

// --- 8. Reconciliation-surface inertness: filtering to ONE account normally drills the panel
// into that account's focused reconcile card — under the transaction basis it must NOT (the
// panel keeps the basis note; the focused card, and the statement lens it opens, are
// posted-basis affordances). The table still narrows to the account's transaction-basis slice.
await page.getByRole('button', { name: 'Filter Account' }).click();
const accountPopover = page.locator('.header-filter-popover', { hasText: 'Chequing' });
await accountPopover.waitFor({ state: 'visible', timeout: 3000 });
await accountPopover.locator('.filter-dropdown-item',
  { has: page.locator('.filter-dropdown-label-text', { hasText: 'Chequing' }) }).locator('input').check();
await waitForRows(5);
check('filtered to Chequing under the lens: its 5-row transaction-basis slice',
  (await rowCount()) === 5, `rows=${await rowCount()}`);
check('the panel still shows the basis note, NOT a focused reconcile card',
  (await page.locator('.reconcile-range-note').innerText()).trim() === 'Monthly close works on posted dates.');
check('no focused card rendered', (await page.locator('.reconcile-focus').count()) === 0);

// --- 9. Trailing checks. ---------------------------------------------------------------------
check('still no page errors', !logs.some((l) => l.startsWith('PAGEERROR')), logs.join('; '));

await browser.close();

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

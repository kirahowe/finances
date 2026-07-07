// Real-Chromium proof of the monthly-close panel (the #reconciliation aside beside the
// category rollup): the per-account overview (each account a drill button), the FOCUSED
// single-account reconcile card you drill into (opening/closing entry with app-owned dates
// + the period-delta verdict), and the completeness gate. The seed gives three 2025-01
// accounts matching bank snapshots (Chequing/Savings/Visa) and leaves Mortgage with only a
// Dec boundary — "needs balances" — so drilling into Mortgage and entering its Jan closing
// balance (-98000, completing the pair against its -100000 Dec opening + 2000 of activity)
// reconciles it live, and Back returns to the overview with the gate flipped to "match".
//
// Named to sort AFTER the eid-hardcoding specs (v2-funnels/v2-grid): this spec resets the
// seed at its start, which bumps Datalevin's eid counter, and those two assert absolute seed
// eids that only hold before the first reset.
//   BASE_URL=http://localhost:8100 node e2e/v2-reconcile.ts
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

const panel = page.locator('#reconciliation');
const rowBy = (name: string) =>
  page.locator('.reconcile-row', { has: page.locator('.reconcile-account', { hasText: name }) });
const gateText = () => page.locator('.reconcile-gate').innerText();
const focus = page.locator('.reconcile-focus');
const focusReconciled = () =>
  page.waitForFunction(
    () =>
      document.querySelector('.reconcile-focus-status')?.classList.contains('reconcile-status--ok'),
    undefined,
    { timeout: 5000 },
  );

await page.request.post(`${BASE}/e2e/reset`);
await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(200);

check('no page errors', !logs.length, logs.join('; '));

// 1. The panel renders beside the rollup, inside the shared summary column, in overview mode.
check('reconciliation panel present', (await panel.count()) === 1);
check('panel is a sibling of the rollup in .rollup-column',
  (await page.locator('.rollup-column > #reconciliation').count()) === 1);
check('overview lists accounts as drill buttons', (await panel.locator('.reconcile-drill').count()) > 0);

// 2. An account whose activity matches its bank snapshots reads "matches".
check('Chequing reconciles (matches)',
  (await rowBy('Chequing').getAttribute('class'))?.includes('reconcile-row--reconciled'));
check('Chequing status says matches', /matches/i.test(await rowBy('Chequing').innerText()));

// 3. Mortgage has no in-month closing balance → "needs balances".
check('Mortgage is unreconciled (needs balances)',
  (await rowBy('Mortgage').getAttribute('class'))?.includes('reconcile-row--no-snapshot'));
check('Mortgage row reads "needs balances"', /needs balances/i.test(await rowBy('Mortgage').innerText()));

// 4. The gate reflects an unreconciled account, and Close is blocked.
check('gate shows balances unreconciled', /unreconciled/i.test(await gateText()));
check('Close month button disabled', await page.locator('.reconcile-close-btn').isDisabled());

// 5. Drill into Mortgage: the panel focuses that account AND the table filters to it.
await rowBy('Mortgage').locator('.reconcile-drill').click();
await focus.waitFor({ state: 'visible', timeout: 5000 });
check('drilling into an account opens the focused card', (await focus.count()) === 1);
check('focused card is titled with the account', /Mortgage/.test(await focus.innerText()));
check('drilling filters the table to that account (an account chip appears)',
  (await page.locator('.active-chip', { hasText: 'Mortgage' }).count()) >= 1);
check('the opening prefills from the Dec boundary balance on file',
  parseFloat(await page.locator('#recon-open').inputValue()) === -100000);
check('opening field labels its app-owned end-of-day date',
  /end of Dec 31, 2024/.test(await focus.innerText()));
check('closing field labels the month-end date',
  /end of Jan 31, 2025/.test(await focus.innerText()));

// 6. Enter the Jan closing balance and Save → the verdict flips to "matches" (–98000 vs the
//    –100000 opening + 2000 of activity = a –98000 change, which the tracked activity explains).
await page.locator('#recon-close').fill('-98000');
await page.locator('.reconcile-save').click();
await focusReconciled().catch(() => {});
check('focused card reconciles after entering the closing balance',
  (await page.locator('.reconcile-focus-status').getAttribute('class'))?.includes('reconcile-status--ok'));
check('the readout shows the tracked vs expected change',
  /Expected change/i.test(await focus.innerText()) && /Tracked activity/i.test(await focus.innerText()));

// 7. Back returns to the overview by clearing the account filter; Mortgage now matches and
//    the balance half of the gate flips to "match".
await page.locator('.reconcile-back').click();
await page.locator('.reconcile-rows').waitFor({ state: 'visible', timeout: 5000 });
check('Back returns to the overview list', (await page.locator('.reconcile-rows').count()) === 1);
check('Mortgage now reconciles in the overview',
  (await rowBy('Mortgage').getAttribute('class'))?.includes('reconcile-row--reconciled'));
check('gate flips to balances match', /balances match/i.test(await gateText()));

// 7b. Drilling back in and clearing the closing balance would revert it — prove the round-trip
//     by re-drilling and confirming the saved closing is prefilled.
await rowBy('Mortgage').locator('.reconcile-drill').click();
await focus.waitFor({ state: 'visible', timeout: 5000 });
check('the saved closing balance is prefilled on re-drill',
  parseFloat(await page.locator('#recon-close').inputValue()) === -98000);
await page.locator('.reconcile-back').click();
await page.locator('.reconcile-rows').waitFor({ state: 'visible', timeout: 5000 }).catch(() => {});

// 8. Close stays blocked while transactions remain unreviewed (the seed month is full of
//    un-reviewed rows) — the completeness half of the gate.
check('Close still blocked by unreviewed transactions',
  await page.locator('.reconcile-close-btn').isDisabled());
check('gate still lists review work', /to review/i.test(await gateText()));

// 9. Statements (arbitrary-span reconciliation) on Visa — a credit card. The seed gives Visa a
//    -85 groceries txn on Jan 5, so a statement Jan 4 → Jan 6 with balances 0 → -85 reconciles.
await rowBy('Visa').locator('.reconcile-drill').click();
await focus.waitFor({ state: 'visible', timeout: 5000 });
await focus.locator('.reconcile-add-statement').waitFor({ state: 'visible', timeout: 5000 });
check('Visa focused card shows a Statements section', (await focus.locator('.reconcile-statements').count()) === 1);
check('the statements list starts empty', (await focus.locator('.reconcile-statement').count()) === 0);
check("Visa's month view shows the Jan 12 payment", /Payment Received/.test(await page.locator('#tx-tbody').innerText()));

const stModal = page.locator('#modal-root [role="dialog"]');
await focus.locator('.reconcile-add-statement').click();
await stModal.waitFor({ state: 'visible', timeout: 5000 });
check('the add-statement modal opened', (await stModal.count()) === 1);
await page.locator('#st-start').fill('2025-01-04');
await page.locator('#st-start-bal').fill('0');
await page.locator('#st-end').fill('2025-01-06');
await page.locator('#st-end-bal').fill('-85');
await page.locator('.form-modal-content .button-primary').click();
await focus.locator('.reconcile-statement').first().waitFor({ state: 'visible', timeout: 5000 });
check('the statement appears in the list', (await focus.locator('.reconcile-statement').count()) === 1);
check('the statement reconciles (matches)', /matches/i.test(await focus.locator('.reconcile-statement').innerText()));

// The header dateline reads the whole month until we narrow.
check('the header dateline shows the month (with year) before narrowing',
  /January 2025/.test(await page.locator('#period-navigator-display').innerText()));

// Clicking the statement narrows the table to its span — just the Jan 5 groceries, not Jan 12.
await focus.locator('.reconcile-statement-span').click();
await page.waitForFunction(
  () => !/Payment Received/.test(document.querySelector('#tx-tbody')?.textContent || ''),
  undefined, { timeout: 5000 }).catch(() => {});
check('narrowing to the statement shows its span txn (Superstore)',
  /Superstore/.test(await page.locator('#tx-tbody').innerText()));
check('narrowing excludes txns outside the span (the Jan 12 payment)',
  !/Payment Received/.test(await page.locator('#tx-tbody').innerText()));
check('the selected statement is highlighted',
  (await focus.locator('.reconcile-statement.is-selected').count()) === 1);
// The header dateline now reflects the actual narrowed span, not the calendar month.
await page.waitForFunction(
  () => /–/.test(document.querySelector('#period-navigator-display')?.textContent || ''),
  undefined, { timeout: 5000 }).catch(() => {});
check('the header dateline switches to the narrowed span',
  /Jan 4 – Jan 6, 2025/.test(await page.locator('#period-navigator-display').innerText()));

// Edit it to an off-by end balance → the verdict flips.
await focus.locator('.reconcile-statement-edit').click();
await stModal.waitFor({ state: 'visible', timeout: 5000 });
check('the edit modal prefills the statement dates', (await page.locator('#st-start').inputValue()) === '2025-01-04');
await page.locator('#st-end-bal').fill('-100');
await page.locator('.form-modal-content .button-primary').click();
await page.waitForFunction(
  () => /off by/i.test(document.querySelector('.reconcile-statement')?.textContent || ''),
  undefined, { timeout: 5000 }).catch(() => {});
check('editing the balance flips the verdict to off by',
  /off by/i.test(await focus.locator('.reconcile-statement').innerText()));

// Delete it via the modal.
await focus.locator('.reconcile-statement-edit').click();
await stModal.waitFor({ state: 'visible', timeout: 5000 });
await page.locator('.form-modal-content .button-danger').click();
await page.waitForFunction(
  () => document.querySelectorAll('.reconcile-statement').length === 0,
  undefined, { timeout: 5000 }).catch(() => {});
check('deleting removes the statement from the list', (await focus.locator('.reconcile-statement').count()) === 0);

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

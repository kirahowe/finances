// Real-Chromium proof of the monthly-close panel (the #reconciliation aside beside
// the category rollup): per-account period-delta reconciliation, the completeness
// gate, and the statement-entry flow that SSE-morphs the panel. The seed gives
// three 2025-01 accounts matching bank snapshots (Chequing/Savings/Visa) and leaves
// Mortgage with only a Dec boundary — "no statement" — so entering its Jan statement
// balance (-98000, completing the pair against its -100000 Dec balance and +2000 of
// activity) reconciles it live.
//
// Named to sort AFTER the eid-hardcoding specs (v2-funnels/v2-grid): this spec
// resets the seed at its start, which bumps Datalevin's eid counter, and those two
// assert absolute seed eids that only hold before the first reset.
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
const rowReconciled = (name: string) =>
  page.waitForFunction((n) => {
    const row = [...document.querySelectorAll('.reconcile-row')]
      .find((r) => r.querySelector('.reconcile-account')?.textContent?.trim() === n);
    return !!row && row.classList.contains('reconcile-row--reconciled');
  }, name, { timeout: 5000 });

await page.request.post(`${BASE}/e2e/reset`);
await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(200);

check('no page errors', !logs.length, logs.join('; '));

// 1. The panel renders beside the rollup, inside the shared summary column.
check('reconciliation panel present', (await panel.count()) === 1);
check('panel is a sibling of the rollup in .rollup-column',
  (await page.locator('.rollup-column > #reconciliation').count()) === 1);

// 2. An account whose activity matches its bank snapshots reads "matches".
check('Chequing reconciles (matches)',
  (await rowBy('Chequing').getAttribute('class'))?.includes('reconcile-row--reconciled'));
check('Chequing status says matches', /matches/i.test(await rowBy('Chequing').innerText()));

// 3. Mortgage has no in-month statement → "no statement" + an inline entry.
check('Mortgage is unreconciled (no statement)',
  (await rowBy('Mortgage').getAttribute('class'))?.includes('reconcile-row--no-snapshot'));
check('Mortgage offers a statement-balance entry',
  (await rowBy('Mortgage').locator('.reconcile-stmt').count()) === 1);

// 4. The gate reflects an unreconciled account, and Close is blocked (unreviewed +
//    the unreconciled balance).
check('gate shows balances unreconciled', /unreconciled/i.test(await gateText()));
check('Close month button disabled', await page.locator('.reconcile-close-btn').isDisabled());

// 5. Enter the Mortgage statement balance → the panel SSE-morphs and the row
//    reconciles, flipping the balance gate to "match".
await rowBy('Mortgage').locator('.reconcile-stmt').fill('-98000');
await rowBy('Mortgage').locator('.reconcile-stmt-save').click();
await rowReconciled('Mortgage').catch(() => {});
check('Mortgage reconciles after entering its statement balance',
  (await rowBy('Mortgage').getAttribute('class'))?.includes('reconcile-row--reconciled'));
check('gate flips to balances match', /balances match/i.test(await gateText()));

// 6. Close stays blocked while transactions remain unreviewed (the seed month is
//    full of un-reviewed rows) — the completeness half of the gate.
check('Close still blocked by unreviewed transactions',
  await page.locator('.reconcile-close-btn').isDisabled());
check('gate still lists review work', /to review/i.test(await gateText()));

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

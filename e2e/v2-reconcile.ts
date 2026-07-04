// Real-Chromium proof of the monthly-close panel (the #reconciliation aside beside
// the category rollup): per-account period-delta reconciliation, the completeness
// gate, and the statement-balance MODAL flow that SSE-morphs the panel. The seed gives
// three 2025-01 accounts matching bank snapshots (Chequing/Savings/Visa) and leaves
// Mortgage with only a Dec boundary — "no statement" — so recording its Jan statement
// balance (-98000, completing the pair against its -100000 Dec balance and +2000 of
// activity) via the modal reconciles it live and lists the balance with its date.
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

// 3. Mortgage has no in-month statement → "no statement" + a "Set balance" button.
check('Mortgage is unreconciled (no statement)',
  (await rowBy('Mortgage').getAttribute('class'))?.includes('reconcile-row--no-snapshot'));
check('Mortgage offers a Set balance action',
  (await rowBy('Mortgage').locator('.reconcile-set-balance').count()) === 1);

// 4. The gate reflects an unreconciled account, and Close is blocked (unreviewed +
//    the unreconciled balance).
check('gate shows balances unreconciled', /unreconciled/i.test(await gateText()));
check('Close month button disabled', await page.locator('.reconcile-close-btn').isDisabled());

// 5. Open the statement modal from Mortgage's row and record its Jan statement balance.
//    The date defaults to the viewed month-end; on Save the panel SSE-morphs, the modal
//    closes, and the row reconciles, flipping the balance gate to "match".
await rowBy('Mortgage').locator('.reconcile-set-balance').click();
const modal = page.locator('#modal-root [role="dialog"]');
await modal.waitFor({ state: 'visible', timeout: 5000 });
check('statement modal opened', (await modal.count()) === 1);
check('date defaults to the viewed month-end',
  (await page.locator('#stmt-date').inputValue()) === '2025-01-31');
await page.locator('#stmt-balance').fill('-98000');
await page.locator('.form-modal-content .button-primary').click();
await rowReconciled('Mortgage').catch(() => {});
check('statement modal closed after save',
  (await page.locator('#modal-root [role="dialog"]').count()) === 0);
check('Mortgage reconciles after recording its statement balance',
  (await rowBy('Mortgage').getAttribute('class'))?.includes('reconcile-row--reconciled'));
check('gate flips to balances match', /balances match/i.test(await gateText()));

// 5b. The recorded balance is listed in the panel with the date it's applied on, and
//     can be removed (× → the row reverts to "no statement").
check('recorded balance listed with its applied date',
  /Jan 31, 2025/.test(await panel.locator('.reconcile-statement').innerText()));
await panel.locator('.reconcile-statement-del').first().click();
await page.waitForFunction(() => {
  const row = [...document.querySelectorAll('.reconcile-row')]
    .find((r) => r.querySelector('.reconcile-account')?.textContent?.trim() === 'Mortgage');
  return !!row && row.classList.contains('reconcile-row--no-snapshot');
}, undefined, { timeout: 5000 }).catch(() => {});
check('removing the balance reverts Mortgage to no statement',
  (await rowBy('Mortgage').getAttribute('class'))?.includes('reconcile-row--no-snapshot'));

// 5c. Re-record it so the completeness half of the gate can be exercised below.
await rowBy('Mortgage').locator('.reconcile-set-balance').click();
await modal.waitFor({ state: 'visible', timeout: 5000 });
await page.locator('#stmt-balance').fill('-98000');
await page.locator('.form-modal-content .button-primary').click();
await rowReconciled('Mortgage').catch(() => {});

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

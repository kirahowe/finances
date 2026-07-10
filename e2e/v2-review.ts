// Real-Chromium proof of the bulk transfer-review modal: the toolbar "Review transfers"
// action lists auto-suggested pairs; Confirm links a pair and Reject ("Not a transfer")
// records a rejection — both act immediately, morphing JUST the acted-on row into a stale
// in-place variant (buttons → quiet status) so nothing shifts under the cursor. Reopening
// the modal recomputes the list, so acted-on pairs drop out.
//
// The seed has one suggestion (the uncategorized 500 inverse pair within the auto window).
// The confirm section first UNMATCHES the seeded same-day Visa-payment pair via the row
// modal, which then becomes a second (closer, so first-sorted) suggestion — giving the
// nothing-moves assertion a second row to watch.
//
//   BASE_URL=http://localhost:8100 node e2e/v2-review.ts
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

const modal = page.locator('.transfer-modal-content');
const suggestions = page.locator('.transfer-suggestion');
const modalGone = () =>
  page.waitForFunction(() => document.querySelectorAll('.transfer-modal-content').length === 0,
    null, { timeout: 5000 }).catch(() => {});
const staleWhenMorphed = (id: string) =>
  page.waitForFunction((rowId) => document.getElementById(rowId)?.classList.contains('is-stale'),
    id, { timeout: 5000 }).catch(() => {});

async function reset() {
  await page.request.post(`${BASE}/e2e/reset`);
  await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(200);
}

async function openReview() {
  await page.getByRole('button', { name: 'Review transfers' }).click();
  await modal.waitFor({ state: 'visible' });
}

// Unmatch the pre-matched Visa-payment pair through the per-row modal, so its same-day
// 300 inverse pair joins the suggestion list.
async function unmatchVisaPayment() {
  const row = page.locator('#tx-tbody tr', { hasText: 'Visa Payment' }).first();
  await row.hover();
  await row.locator('.row-actions-trigger').click();
  const menu = page.locator('#row-actions-menu');
  await menu.waitFor({ state: 'visible' });
  await page.waitForTimeout(80);
  await menu.locator('.row-actions-item').nth(1).click(); // [0]=Split, [1]=Match
  await modal.waitFor({ state: 'visible' });
  await page.locator('.transfer-unmatch-button').click();
  await modalGone();
}

// (A) Two suggestions → Confirm the first → it goes stale IN PLACE (buttons → "✓ Matched"),
//     the other row doesn't move; a fresh open drops the confirmed pair.
await reset();
check('no page errors', !logs.length, logs.join('; '));
await unmatchVisaPayment();
await openReview();
check('review modal lists two suggestions after unmatching the Visa pair', (await suggestions.count()) === 2);
check('suggestion shows the account route',
  (await page.locator('.transfer-suggestion-route').first().innerText()).includes('→'));
const ids = await suggestions.evaluateAll((els) => els.map((el) => el.id));
check('each suggestion row carries a stable pair id',
  ids.length === 2 && ids.every((id) => /^suggestion-\d+-\d+$/.test(id)), ids.join(', '));
const firstRow = page.locator(`#${ids[0]}`);
const secondRow = page.locator(`#${ids[1]}`);
await firstRow.locator('.transfer-confirm-button').click();
await staleWhenMorphed(ids[0]);
check('the confirmed row stays visible and goes stale in place',
  (await suggestions.count()) === 2 &&
  (await firstRow.evaluate((el) => el.classList.contains('is-stale')).catch(() => false)));
check('its buttons are replaced by a quiet "✓ Matched" status',
  (await firstRow.locator('button').count()) === 0 &&
  (await firstRow.locator('.transfer-suggestion-status').innerText()).includes('Matched'));
const idsAfter = await suggestions.evaluateAll((els) => els.map((el) => el.id));
check('the rows did not reorder', ids.join(',') === idsAfter.join(','), idsAfter.join(','));
check('the other row is untouched (fresh, buttons intact)',
  !(await secondRow.evaluate((el) => el.classList.contains('is-stale'))) &&
  (await secondRow.locator('.transfer-confirm-button').count()) === 1 &&
  (await secondRow.locator('.transfer-reject-button').count()) === 1);
await page.getByRole('button', { name: 'Done' }).click();
await modalGone();
await openReview();
check('reopening shows a fresh list without the confirmed pair', (await suggestions.count()) === 1);
check('the remaining suggestion is the 500 pair',
  /500/.test(await page.locator('.transfer-suggestion-amount').first().innerText()));
await page.getByRole('button', { name: 'Done' }).click();
await modalGone();

// (B) Fresh slate → Reject the one suggestion → stale in place reading "Not a transfer";
//     a fresh open shows the empty state.
await reset();
await openReview();
check('suggestion present again after reset', (await suggestions.count()) === 1);
const rejectId = await suggestions.first().evaluate((el) => el.id);
await page.locator('.transfer-reject-button').first().click();
await staleWhenMorphed(rejectId);
check('the rejected row stays visible and stale',
  (await suggestions.count()) === 1 &&
  (await suggestions.first().evaluate((el) => el.classList.contains('is-stale'))));
check('its status reads "Not a transfer" with no buttons left',
  (await suggestions.first().locator('button').count()) === 0 &&
  (await suggestions.first().locator('.transfer-suggestion-status').innerText()).trim() === 'Not a transfer');
check('no empty-state swap while the modal is open', (await page.locator('.transfer-empty').count()) === 0);
await page.getByRole('button', { name: 'Done' }).click();
await modalGone();
await openReview();
check('reopening shows the empty state (the rejected pair is gone)',
  (await page.locator('.transfer-empty').count()) === 1 && (await suggestions.count()) === 0);

check('still no page errors', !logs.length, logs.join('; '));

await browser.close();
// Leave a clean slate (DB + in-memory undo log) for whatever spec runs next.
await fetch(`${BASE}/e2e/reset`, { method: 'POST' }).catch(() => {});

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

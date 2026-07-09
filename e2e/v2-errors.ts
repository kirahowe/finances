// Global error-bar proof: a mutation the server REJECTS must surface its message in the
// dismissable #error-bar (role=alert) instead of failing silently — the pre-rewrite gap was
// that a thrown ex-info reached the JSON exception middleware, so a Datastar @put got a
// non-SSE response, nothing patched, and the user saw no change and no error.
//
// We force the rejection by writing an invalid split payload straight into the #split-courier
// (bypassing the island's canConfirm gate — that's the point: prove the SERVER's validation is
// surfaced). Then: the bar appears with a message, the modal closes (so the bar isn't hidden
// behind the backdrop), dismiss clears it, and a subsequent successful edit auto-clears it.
//
//   BASE_URL=http://localhost:8099 node e2e/v2-errors.ts
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

await page.request.post(`${BASE}/e2e/reset`);
await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);

const superstore = () => page.locator('#tx-tbody tr', { hasText: 'Superstore' }).first();

// Open the split editor, then shove an unbalanced payload referencing a non-existent category
// through the courier — set-splits! rejects it (:bad-request) before writing anything.
const forceBadSplit = async (): Promise<void> => {
  await superstore().hover();
  await superstore().locator('.row-actions-trigger').click();
  await page.locator('#row-actions-menu').waitFor({ state: 'visible' });
  await page.locator('#row-actions-menu .row-actions-item').first().click(); // Split transaction
  await page.locator('.split-modal-content').waitFor({ state: 'visible' });
  await page.evaluate(() => {
    const c = document.getElementById('split-courier') as HTMLInputElement;
    c.value = JSON.stringify([
      { amount: '0.01', categoryId: 999999 },
      { amount: '0.02', categoryId: 999999 },
    ]);
    c.dispatchEvent(new Event('change', { bubbles: true }));
  });
};

await forceBadSplit();

const bar = page.locator('#error-bar .error-banner');
await bar.waitFor({ state: 'visible', timeout: 5000 });
check('error bar appears on a server-rejected mutation', await bar.isVisible());
check('error bar carries a message', (await bar.locator('span').innerText()).trim().length > 0);
check('the rejected mutation closed the modal (bar not hidden behind it)',
  (await page.locator('.split-modal-content').count()) === 0);
check('#error-bar is role=alert (announced)',
  (await page.locator('#error-bar').getAttribute('role')) === 'alert');

// Dismiss empties the bar (the wrapper + role persist for the next error).
await page.locator('#error-bar .error-banner button').click();
await page.waitForTimeout(100);
check('dismiss clears the error bar', (await page.locator('#error-bar .error-banner').count()) === 0);

// Trigger it again, then a SUCCESSFUL edit (toggle a reconciled checkbox) must auto-clear it.
await forceBadSplit();
await bar.waitFor({ state: 'visible', timeout: 5000 });
check('error bar re-appears on a second rejection', await bar.isVisible());

await superstore().locator('.reconciled-checkbox').click();
await page.waitForFunction(
  () => !document.querySelector('#error-bar .error-banner'),
  null, { timeout: 5000 }).catch(() => {});
check('a successful edit auto-clears the error bar',
  (await page.locator('#error-bar .error-banner').count()) === 0);

check('no uncaught page errors', !logs.length, logs.join('; '));

await browser.close();

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

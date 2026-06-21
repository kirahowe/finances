// Real-Chromium proof of the bulk transfer-review modal: the toolbar "Review transfers" action
// lists auto-suggested pairs; Confirm links a pair and Reject ("Not a transfer") records a
// rejection — both act immediately and refresh the (now-smaller) list in place. The seed has
// exactly one suggestion (the uncategorized 500 inverse pair within the auto window).
//
//   BASE_URL=http://localhost:8100 node e2e/v2-review.mjs
import { createRequire } from 'module';
import { fileURLToPath } from 'url';
import { dirname, resolve } from 'path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(resolve(root, 'frontend') + '/');
const { chromium } = require('@playwright/test');

const BASE = process.env.BASE_URL || 'http://localhost:8100';
const results = [];
const check = (name, ok, detail = '') => results.push({ name, ok: !!ok, detail });

const browser = await chromium.launch();
const page = await browser.newPage();
const logs = [];
page.on('pageerror', (e) => logs.push('PAGEERROR: ' + e.message));

const modal = page.locator('.transfer-modal-content');
const suggestions = page.locator('.transfer-suggestion');
const emptyWhenZero = () =>
  page.waitForFunction(() => document.querySelectorAll('.transfer-suggestion').length === 0,
    null, { timeout: 5000 }).catch(() => {});

async function openReview() {
  await page.request.post(`${BASE}/e2e/reset`);
  await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(200);
  await page.getByRole('button', { name: 'Review transfers' }).click();
  await modal.waitFor({ state: 'visible' });
}

// (A) Confirm the suggested pair → it links and drops out of the list (modal stays open).
await openReview();
check('no page errors', !logs.length, logs.join('; '));
check('review modal lists the one seeded suggestion', (await suggestions.count()) === 1);
check('suggestion shows its 500 amount',
  /500/.test(await page.locator('.transfer-suggestion-amount').first().innerText()));
check('suggestion shows the account route',
  (await page.locator('.transfer-suggestion-route').first().innerText()).includes('→'));
await page.locator('.transfer-confirm-button').first().click();
await emptyWhenZero();
check('confirmed pair drops out of the list', (await suggestions.count()) === 0);
check('empty state shown, modal still open', (await page.locator('.transfer-empty').count()) === 1 && (await modal.count()) === 1);

// (B) Fresh slate → Reject the suggestion ("Not a transfer") → it also drops out.
await openReview();
check('suggestion present again after reset', (await suggestions.count()) === 1);
await page.locator('.transfer-reject-button').first().click();
await emptyWhenZero();
check('rejected pair drops out of the list', (await suggestions.count()) === 0);

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

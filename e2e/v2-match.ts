// Real-Chromium proof of the per-row transfer match/unmatch modal: row-actions menu →
// the modal shows the transaction being matched above its candidate list → confirm links
// the pair → the row reads "Matched transfer" → unmatch → undo re-links. Also proves the
// auto-category effect: matching a categorized leg to an uncategorized one copies the
// category, and ONE undo reverts both the link and the copy. Pure Datastar (no island):
// clicking a candidate @put's the confirm.
//
//   BASE_URL=http://localhost:8100 node e2e/v2-match.ts
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

const row = (text) => page.locator('#tx-tbody tr', { hasText: text }).first();
const menu = page.locator('#row-actions-menu');
const matchItem = menu.locator('.row-actions-item').nth(1); // [0]=Split, [1]=Match
const modal = page.locator('.transfer-modal-content');
const modalGone = () =>
  page.waitForFunction(() => document.querySelectorAll('.transfer-modal-content').length === 0,
    null, { timeout: 5000 }).catch(() => {});
const categoryOf = (text) => row(text).locator('.category-button').innerText();

async function openMenu(text) {
  await row(text).hover();
  await row(text).locator('.row-actions-trigger').click();
  await menu.waitFor({ state: 'visible' });
  await page.waitForTimeout(80); // let data-text settle from $_rowMenuMatched
}

// (A) Unmatched row → "Match transfer" → the modal shows the source transaction above
//     its offered counterpart → confirm links it.
await openMenu('Transfer to Savings');
check('unmatched row offers "Match transfer"', (await matchItem.innerText()).trim() === 'Match transfer');
await matchItem.click();
await modal.waitFor({ state: 'visible' });
check('modal shows the transaction being matched as a static leg',
  (await modal.locator('.transfer-candidate.is-static', { hasText: 'Transfer to Savings' }).count()) === 1);
check('the source and candidate groups each get a quiet label',
  (await modal.locator('.transfer-modal-section-label').count()) === 2);
const candidate = modal.locator('.transfer-candidate', { hasText: 'Transfer from Chequing' });
check('the +500 savings counterpart is offered', (await candidate.count()) === 1);
await candidate.first().click();
await modalGone();
check('modal closed after confirming the match', (await modal.count()) === 0);

// (B) Re-open the same row → now "Matched transfer" → shows the partner + Unmatch.
await openMenu('Transfer to Savings');
check('row now reads "Matched transfer"', (await matchItem.innerText()).trim() === 'Matched transfer');
await matchItem.click();
await modal.waitFor({ state: 'visible' });
check('matched modal shows the partner card', (await modal.locator('.transfer-candidate.is-static').count()) === 1);
check('matched modal offers Unmatch', (await modal.locator('.transfer-unmatch-button').count()) === 1);
await modal.locator('.transfer-unmatch-button').click();
await modalGone();

// (C) Unmatched again, then undo the unmatch → re-matched.
await openMenu('Transfer to Savings');
check('row reads "Match transfer" again after unmatch', (await matchItem.innerText()).trim() === 'Match transfer');
await page.keyboard.press('Escape');
await page.locator('#undo-redo button[aria-label="Undo"]').click();
await page.waitForTimeout(400);
await openMenu('Transfer to Savings');
check('undo re-matches the pair', (await matchItem.innerText()).trim() === 'Matched transfer');
await page.keyboard.press('Escape');

// (D) Auto-category: match the Transfer-categorized -750 leg to its uncategorized +750
//     counterpart → the blank leg gets the category; ONE undo unlinks AND reverts it.
check('the counterpart starts uncategorized', (await categoryOf('Transfer In Later')).trim() === 'Uncategorized');
await openMenu('Transfer Out');
check('the -750 transfer row offers "Match transfer"', (await matchItem.innerText()).trim() === 'Match transfer');
await matchItem.click();
await modal.waitFor({ state: 'visible' });
const counterpart = modal.locator('.transfer-candidate:not(.is-static)', { hasText: 'Transfer In Later' });
check('the outside-auto-window +750 counterpart is offered manually', (await counterpart.count()) === 1);
await counterpart.first().click();
await modalGone();
await page.waitForTimeout(300);
check('matching copied the Transfer category onto the blank leg',
  (await categoryOf('Transfer In Later')).trim() === 'Transfer');
await page.locator('#undo-redo button[aria-label="Undo"]').click();
await page.waitForTimeout(400);
check('undo reverts the copied category', (await categoryOf('Transfer In Later')).trim() === 'Uncategorized');
await openMenu('Transfer Out');
check('and unlinks the pair in the same step', (await matchItem.innerText()).trim() === 'Match transfer');

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

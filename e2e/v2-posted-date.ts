// Real-Chromium proof of the manual posted-date override: row-actions menu → "Set posted
// date…" opens a small modal showing the imported date, saving a same-month date shows an
// inline "· posted <date>" hint marked posted-hint--manual, saving a date in a DIFFERENT
// month moves the row out of the current month's table entirely (reconciliation buckets by
// the EFFECTIVE posted date — data.ledger/effective-posted-date), undo brings it back
// (restoring the prior override), and Clear override removes the hint.
//
//   BASE_URL=http://localhost:8100 node e2e/v2-posted-date.ts
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

const row = (text: string) => page.locator('#tx-tbody tr', { hasText: text }).first();
const menu = page.locator('#row-actions-menu');
// [0]=Split transaction, [1]=Match transfer, [2]=Set posted date…, [3]=Delete (hidden, manual only)
const postedItem = menu.locator('.row-actions-item').nth(2);
const modal = page.locator('#modal-root [role="dialog"]');
const modalGone = () =>
  page.waitForFunction(() => document.querySelectorAll('#modal-root [role="dialog"]').length === 0,
    null, { timeout: 5000 }).catch(() => {});

async function openMenu(text: string) {
  await row(text).hover();
  await row(text).locator('.row-actions-trigger').click();
  await menu.waitFor({ state: 'visible' });
  await page.waitForTimeout(80);
}

async function openPostedDateModal(text: string) {
  await openMenu(text);
  check(`"${text}" row menu offers Set posted date…`, (await postedItem.innerText()).trim() === 'Set posted date…');
  await postedItem.click();
  await modal.waitFor({ state: 'visible' });
}

// (A) Open the editor on the Superstore row (Visa, Jan 5, no override yet).
await openPostedDateModal('Superstore');
check('modal title carries the payee', /Superstore/.test(await modal.locator('h2').innerText()));
check('shows the imported date', /Imported:\s*Jan 5/.test(await modal.locator('.form-modal-hint').innerText()));
check('the date input opens prefilled with the effective (imported) date',
  (await page.locator('#posted-date-input').inputValue()) === '2025-01-05');
check('no Clear override button yet (no override set)',
  (await modal.getByRole('button', { name: 'Clear override' }).count()) === 0);

// (B) Save a different date in the SAME month → an inline manual hint appears.
await page.locator('#posted-date-input').fill('2025-01-08');
await modal.getByRole('button', { name: 'Save' }).click();
await modalGone();
check('modal closed after saving', (await modal.count()) === 0);

const hint = () => row('Superstore').locator('.posted-hint');
const superstoreRowText = () =>
  page.evaluate(() => {
    const rows = [...document.querySelectorAll('#tx-tbody tr')];
    return rows.find((tr) => tr.textContent?.includes('Superstore'))?.textContent || '';
  });
await page.waitForFunction(() => {
  const rows = [...document.querySelectorAll('#tx-tbody tr')];
  const r = rows.find((tr) => tr.textContent?.includes('Superstore'));
  return !!r && /posted Jan 8/.test(r.textContent || '');
}, undefined, { timeout: 5000 }).catch(() => {});
check('the row now shows a "posted Jan 8" hint', /posted Jan 8/.test(await superstoreRowText()));
check('the hint is marked manual', (await hint().getAttribute('class'))?.includes('posted-hint--manual'));
check('the hint explains itself via title', (await hint().getAttribute('title')) === 'Posted date set manually');

// (C) Re-open the editor: prefilled with the NEW effective date, Clear now offered, imported
// date is still the untouched provider value.
await openPostedDateModal('Superstore');
check('re-opens prefilled with the override date',
  (await page.locator('#posted-date-input').inputValue()) === '2025-01-08');
check('imported date is unchanged', /Imported:\s*Jan 5/.test(await modal.locator('.form-modal-hint').innerText()));
check('Clear override is now offered', (await modal.getByRole('button', { name: 'Clear override' }).count()) === 1);

// (D) Save a date in a DIFFERENT month → the row leaves the January table entirely
// (list-for-month now buckets by the effective posted date).
await page.locator('#posted-date-input').fill('2025-02-01');
await modal.getByRole('button', { name: 'Save' }).click();
await modalGone();
await page.waitForFunction(() => !/Superstore/.test(document.querySelector('#tx-tbody')?.textContent || ''),
  undefined, { timeout: 5000 }).catch(() => {});
check('the row leaves the January table once its effective date moves to February',
  (await row('Superstore').count()) === 0);

// (E) Undo restores the PRIOR override (Jan 8, not the original absence) — the row reappears
// in January with its manual hint intact.
await page.locator('#undo-redo button[aria-label="Undo"]').click();
await page.waitForFunction(() => /Superstore/.test(document.querySelector('#tx-tbody')?.textContent || ''),
  undefined, { timeout: 5000 }).catch(() => {});
check('undo brings the row back into January', (await row('Superstore').count()) === 1);
check('undo restores the prior override, not a bare reset', /posted Jan 8/.test(await superstoreRowText()));

// (F) Clear override removes the hint entirely (falls back to the imported date).
await openPostedDateModal('Superstore');
await modal.getByRole('button', { name: 'Clear override' }).click();
await modalGone();
await page.waitForFunction(() => {
  const rows = [...document.querySelectorAll('#tx-tbody tr')];
  const r = rows.find((tr) => tr.textContent?.includes('Superstore'));
  return !!r && !/posted/.test(r.textContent || '');
}, undefined, { timeout: 5000 }).catch(() => {});
check('clearing the override removes the hint', (await hint().count()) === 0);

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

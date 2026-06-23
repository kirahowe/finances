// Real-Chromium proof for inline description editing on /v2 (Phase R2 cp2). Click-to-edit
// (class-swap), Enter commits a server-confirmed command (morphs the row back), Escape
// reverts, and the edit is undoable via the command log. No per-row signals — the input
// holds its own text; a single $editValue courier carries the value on commit.
//
//   BASE_URL=http://localhost:8099 node e2e/v2-desc.ts
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

const firstCell = () => page.locator('#tx-tbody tr').first().locator('.description-cell');
const btnText = () => firstCell().locator('.description-button').innerText();

await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
check('no page errors', !logs.length, logs.join('; '));

// Open the editor → the cell swaps to its input.
await firstCell().locator('.description-button').click();
check('click opens the inline editor', await firstCell().evaluate((c) => c.classList.contains('editing')));

// Type + Enter → server-confirmed; the row morphs back showing the new text.
await firstCell().locator('.description-input').fill('Coffee with Sam');
await firstCell().locator('.description-input').press('Enter');
await page.waitForFunction(() => {
  const b = document.querySelector('#tx-tbody tr .description-cell .description-button');
  return b && b.textContent.trim() === 'Coffee with Sam';
}, null, { timeout: 5000 }).catch(() => {});
check('Enter persists; row shows the new description', (await btnText()).trim() === 'Coffee with Sam',
  await btnText());

// Undo via the toolbar button → reverts to the imported (blank → "—").
await page.getByRole('button', { name: 'Undo' }).click();
await page.waitForFunction(() => {
  const b = document.querySelector('#tx-tbody tr .description-cell .description-button');
  return b && b.textContent.trim() === '—';
}, null, { timeout: 5000 }).catch(() => {});
check('undo reverts the description', (await btnText()).trim() === '—', await btnText());

// Escape cancels without persisting.
await firstCell().locator('.description-button').click();
await firstCell().locator('.description-input').fill('discard me');
await firstCell().locator('.description-input').press('Escape');
await page.waitForTimeout(200);
check('Escape cancels (button unchanged, editor closed)',
  (await btnText()).trim() === '—' && !(await firstCell().evaluate((c) => c.classList.contains('editing'))),
  await btnText());

await browser.close();
await fetch(`${BASE}/e2e/reset`, { method: 'POST' }).catch(() => {});

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

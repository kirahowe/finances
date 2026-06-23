// Real-Chromium proof for Phase-R2 cp2: server-confirmed reviewed edit + lingering + undo.
// Reviewing a row in Needs-review scope persists server-side; instead of vanishing, the row
// LINGERS (is-stale) so a fast mistake is noticeable; the toolbar Undo/Redo buttons (and
// Cmd/Ctrl+Z) reverse it. All driven by the command log (web.commands) over SSE morph.
//
//   BASE_URL=http://localhost:8099 node e2e/v2-edit.ts
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

const rows = () => page.locator('#tx-tbody tr').count();
const staleRows = () => page.locator('#tx-tbody tr.is-stale').count();
const unreviewed = () => page.locator('#count-unreviewed').innerText();
const undoBtn = () => page.getByRole('button', { name: 'Undo' });
const redoBtn = () => page.getByRole('button', { name: 'Redo' });

// Start in Needs-review scope so a reviewed row would normally drop out.
await page.goto(`${BASE}/?month=2025-01&scope=needs-review`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);

check('no page errors', !logs.length, logs.join('; '));
check('needs-review: 10 rows, count 10', (await rows()) === 10 && (await unreviewed()).trim() === '10',
  `rows=${await rows()} count=${await unreviewed()}`);
check('undo/redo both disabled on load', (await undoBtn().isDisabled()) && (await redoBtn().isDisabled()));

// Review the first row's checkbox → server persists; row lingers (stale) instead of vanishing.
await page.locator('#tx-tbody tr').first().locator('input.reviewed-checkbox').check();
await page.waitForFunction(() => document.querySelectorAll('#tx-tbody tr.is-stale').length === 1, null, { timeout: 5000 }).catch(() => {});
check('edit lingers the row (stays, is-stale)', (await rows()) === 10 && (await staleRows()) === 1,
  `rows=${await rows()} stale=${await staleRows()}`);
check('unreviewed count dropped to 9', (await unreviewed()).trim() === '9', `count=${await unreviewed()}`);
check('undo button now enabled, with label', !(await undoBtn().isDisabled()) &&
  ((await undoBtn().getAttribute('title')) ?? '').includes('Marked reviewed'),
  await undoBtn().getAttribute('title'));

// Undo via the toolbar button → reverses; row matches again (no longer stale), count back to 10.
await undoBtn().click();
await page.waitForFunction(() => document.querySelectorAll('#tx-tbody tr.is-stale').length === 0, null, { timeout: 5000 }).catch(() => {});
check('undo reverses the edit (no stale rows)', (await staleRows()) === 0, `stale=${await staleRows()}`);
check('undo restored count to 10', (await unreviewed()).trim() === '10', `count=${await unreviewed()}`);
check('after undo: undo disabled, redo enabled', (await undoBtn().isDisabled()) && !(await redoBtn().isDisabled()));

// Keyboard: review again, then Cmd/Ctrl+Z undoes.
await page.locator('#tx-tbody tr').first().locator('input.reviewed-checkbox').check();
await page.waitForFunction(() => document.querySelectorAll('#tx-tbody tr.is-stale').length === 1, null, { timeout: 5000 }).catch(() => {});
await page.keyboard.press(process.platform === 'darwin' ? 'Meta+z' : 'Control+z');
await page.waitForFunction(() => document.querySelectorAll('#tx-tbody tr.is-stale').length === 0, null, { timeout: 5000 }).catch(() => {});
check('Cmd/Ctrl+Z undoes', (await staleRows()) === 0 && (await unreviewed()).trim() === '10',
  `stale=${await staleRows()} count=${await unreviewed()}`);

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

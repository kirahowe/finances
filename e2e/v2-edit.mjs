// Real-Chromium proof for Phase-R2 cp2: server-confirmed reviewed edit + lingering + undo.
// Reviewing a row in Needs-review scope persists server-side; instead of vanishing, the row
// LINGERS (is-stale) so a fast mistake is noticeable; an undo toast (and Cmd/Ctrl+Z) reverses
// it. All driven by the command log (web.commands) over SSE morph — no per-row signals.
//
//   BASE_URL=http://localhost:8099 node e2e/v2-edit.mjs
import { createRequire } from 'module';
import { fileURLToPath } from 'url';
import { dirname, resolve } from 'path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(resolve(root, 'frontend') + '/');
const { chromium } = require('@playwright/test');

const BASE = process.env.BASE_URL || 'http://localhost:8099';
const results = [];
const check = (name, ok, detail = '') => results.push({ name, ok: !!ok, detail });

const browser = await chromium.launch();
const page = await browser.newPage();
const logs = [];
page.on('pageerror', (e) => logs.push('PAGEERROR: ' + e.message));

const rows = () => page.locator('#tx-tbody tr').count();
const staleRows = () => page.locator('#tx-tbody tr.is-stale').count();
const unreviewed = () => page.locator('#count-unreviewed').innerText();

// Start in Needs-review scope so a reviewed row would normally drop out.
await page.goto(`${BASE}/v2?month=2025-01&scope=needs-review`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);

check('no page errors', !logs.length, logs.join('; '));
check('needs-review: 10 rows, count 10', (await rows()) === 10 && (await unreviewed()).trim() === '10',
  `rows=${await rows()} count=${await unreviewed()}`);

// Review the first row's checkbox → server persists; row lingers (stale) instead of vanishing.
await page.locator('#tx-tbody tr').first().locator('input.reviewed-checkbox').check();
await page.waitForFunction(() => document.querySelectorAll('#tx-tbody tr.is-stale').length === 1, null, { timeout: 5000 }).catch(() => {});
check('edit lingers the row (stays, is-stale)', (await rows()) === 10 && (await staleRows()) === 1,
  `rows=${await rows()} stale=${await staleRows()}`);
check('unreviewed count dropped to 9', (await unreviewed()).trim() === '9', `count=${await unreviewed()}`);
check('undo toast shown', (await page.locator('#toast.is-visible .toast-label').innerText()).includes('Marked reviewed'));

// Undo via the toast button → reverses; row matches again (no longer stale), count back to 10.
await page.locator('#toast .toast-undo').click();
await page.waitForFunction(() => document.querySelectorAll('#tx-tbody tr.is-stale').length === 0, null, { timeout: 5000 }).catch(() => {});
check('undo reverses the edit (no stale rows)', (await staleRows()) === 0, `stale=${await staleRows()}`);
check('undo restored count to 10', (await unreviewed()).trim() === '10', `count=${await unreviewed()}`);
check('toast cleared after undo', (await page.locator('#toast').getAttribute('class')) === 'toast',
  await page.locator('#toast').getAttribute('class'));

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

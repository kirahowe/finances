// Real-Chromium proof for the server-authoritative spike rendered through hiccup2.
// The point: hiccup2 escapes attribute values (single quotes -> &apos;), so this
// verifies a real browser DECODES them and Datastar still fires the data-on
// expressions — the foundation of the Replicant -> hiccup2 rewrite. Also proves the
// server-side scope filter + the server-confirmed reviewed toggle (SSE morph by id).
//
//   BASE_URL=http://localhost:8099 node e2e/spike-hiccup2.mjs
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
page.on('console', (m) => logs.push(m.text()));
page.on('pageerror', (e) => logs.push('PAGEERROR: ' + e.message));

const rowCount = () => page.locator('#tx-tbody tr').count();
const activeName = async () => {
  for (const n of ['Needs review', 'All']) {
    const cls = (await page.getByRole('button', { name: new RegExp(n) }).getAttribute('class')) || '';
    if (cls.includes('is-active')) return n;
  }
  return '(none)';
};

await page.goto(`${BASE}/spike?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300); // Datastar init

// 1. No JS errors — Datastar parsed every (escaped) data-* expression on the page.
check('no page errors (Datastar parsed escaped expressions)',
  !logs.some((l) => l.startsWith('PAGEERROR')),
  logs.filter((l) => l.startsWith('PAGEERROR')).join('; '));

// 2. data-signals (escaped JSON) bound: scope defaults to 'all' -> All is active.
check('data-signals JSON decoded → $scope="all" active', (await activeName()) === 'All',
  `active=${await activeName()}`);

// 3. The escaped data-on:click fires: clicking Needs review flips $scope (data-class).
await page.getByRole('button', { name: /Needs review/ }).click();
await page.waitForTimeout(150);
check('escaped data-on:click works → Needs review active', (await activeName()) === 'Needs review',
  `active=${await activeName()}`);

// 4. Server-side scope filter returned the month (all 10 seed txs unreviewed).
const baseRows = await rowCount();
check('needs-review tbody rendered via SSE morph', baseRows === 10, `rows=${baseRows}`);

// 5. Server-confirmed reviewed toggle: check row 1 → @put(.../true) → row drops out.
await page.locator('#tx-tbody tr').first().locator('input.reviewed-checkbox').check();
await page.waitForFunction((n) => document.querySelectorAll('#tx-tbody tr').length === n, baseRows - 1,
  { timeout: 5000 }).catch(() => {});
check('reviewed @put + morph → row filtered out', (await rowCount()) === baseRows - 1,
  `rows=${await rowCount()}`);

await browser.close();

// Restore the seed deterministically (this spec mutates a reviewed flag).
await fetch(`${BASE}/e2e/reset`, { method: 'POST' }).catch(() => {});

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

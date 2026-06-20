// Browser-driven verification of the Phase-1 scaffold (/_scaffold), run in real
// Chromium. Proves what curl can't: that the Datastar runtime and the esbuild
// island actually execute in a browser, and that the SSE round-trip patches the
// DOM. Mirrors the spike's verify.mjs pattern.
//
// Usage (with an e2e/dev server already serving the backend):
//   BASE_URL=http://localhost:8099 node e2e/scaffold.mjs
import { createRequire } from 'module';
import { fileURLToPath } from 'url';
import { dirname, resolve } from 'path';

// Resolve Playwright from the frontend's node_modules regardless of cwd.
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

await page.goto(`${BASE}/_scaffold`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300); // let Datastar + island initialize

const countText = () => page.locator('strong[data-text="$count"]').innerText();

// 0. No JS errors on load
check('no page errors', !logs.some((l) => l.startsWith('PAGEERROR')),
  logs.filter((l) => l.startsWith('PAGEERROR')).join('; '));

// 1. Datastar bound the initial signal ($count -> "0")
check('datastar bound initial $count = 0', (await countText()).trim() === '0',
  `got "${(await countText()).trim()}"`);

// 2. Datastar reactivity: +1 twice increments client-side, no round-trip
await page.getByRole('button', { name: '+1' }).click();
await page.getByRole('button', { name: '+1' }).click();
check('client reactivity: $count = 2 after two clicks', (await countText()).trim() === '2',
  `got "${(await countText()).trim()}"`);

// 3. The esbuild island executed (ran pure lib code in the browser)
const islandReady = await page.locator('#island-demo').getAttribute('data-island-ready');
const islandText = await page.locator('#island-demo').innerText();
check('island executed (data-island-ready)', islandReady === 'true', `ready=${islandReady}`);
check('island ran bundled lib code (centsToAmountString)', islandText.includes('1234.56'),
  islandText);

// 4. SSE round-trip: sync the current count to the server, fragment patches back
await page.getByRole('button', { name: 'Sync count to server' }).click();
await page.waitForFunction(
  () => document.getElementById('sync-result')?.textContent?.includes('server received count'),
  { timeout: 5000 },
).catch(() => {});
const syncText = (await page.locator('#sync-result').innerText()).trim();
check('SSE patched server-authoritative fragment', syncText === 'server received count = 2', syncText);

await browser.close();

// Report
let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

// Real-Chromium proof for /v2 header-filter funnels (Phase cp1b). A funnel opens a floating
// popover; checking an option updates the persistent filter.<col> array and @get's the rows
// (the server view engine filters); the selection persists to the URL; Clear empties it.
//
//   BASE_URL=http://localhost:8099 node e2e/v2-funnels.ts
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
const accountPopover = () => page.locator('.header-filter-popover', { hasText: 'Chequing' });
// Select funnel options by account NAME and read the account's eid at runtime — the
// seed's absolute eids shift with any schema/seed change, so nothing here hardcodes them.
const accountOption = (name: string) =>
  accountPopover().locator('.filter-dropdown-item',
    { has: page.locator('.filter-dropdown-label-text', { hasText: name }) });

await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
check('no page errors', !logs.length, logs.join('; '));
check('starts at 10 rows', (await rows()) === 10, `rows=${await rows()}`);

// Open the Account funnel and read the Chequing/Visa account ids the server assigned.
await page.getByRole('button', { name: 'Filter Account' }).click();
await accountPopover().waitFor({ state: 'visible', timeout: 3000 });
check('account funnel opens', await accountPopover().isVisible());
const chequingBox = accountOption('Chequing').locator('input');
const chequingId = await chequingBox.getAttribute('value');
const visaId = await accountOption('Visa').locator('input').getAttribute('value');

// Select Chequing → the server filters to its 5 rows and the URL records its id.
await chequingBox.check();
await page.waitForFunction(() => document.querySelectorAll('#tx-tbody tr').length === 5, null, { timeout: 5000 }).catch(() => {});
check('server filters to Chequing (5 rows)', (await rows()) === 5, `rows=${await rows()}`);
check('URL reflects fa=<chequing id>', new URL(page.url()).searchParams.get('fa') === chequingId, page.url());
check('funnel button shows active count 1',
  (await page.getByRole('button', { name: 'Filter Account' }).locator('.th-filter-count').innerText()).trim() === '1');

// Clear → back to all rows, URL param dropped.
await accountPopover().locator('.filter-dropdown-clear').click();
await page.waitForFunction(() => document.querySelectorAll('#tx-tbody tr').length === 10, null, { timeout: 5000 }).catch(() => {});
check('Clear restores 10 rows', (await rows()) === 10, `rows=${await rows()}`);
check('URL fa cleared', !new URL(page.url()).searchParams.get('fa'), page.url());

// A funnel URL is server-seeded (= what a reload/shared link restores): Visa → 2 rows,
// with its checkbox pre-checked.
await page.goto(`${BASE}/?month=2025-01&fa=${visaId}`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
check('URL-seeded funnel filters server-side (Visa → 2 rows)', (await rows()) === 2, `rows=${await rows()}`);
check('seeded funnel button shows active count 1',
  (await page.getByRole('button', { name: 'Filter Account' }).locator('.th-filter-count').innerText()).trim() === '1');

await browser.close();

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

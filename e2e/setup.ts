// Browser-driven verification of the server-rendered /setup page: the
// connection-grouped account list (status pill, last-synced, per-connection
// Resync), the action bar (Sync all / Link Bank Account / Connect Lunchflow),
// and that the Plaid Link island loads without error. Run against the seeded
// e2e backend:
//   BASE_URL=http://localhost:8099 node e2e/setup.ts
//
// The seed (env/e2e .../seed.clj) has 1 institution, a synced Plaid connection
// ("Test Bank") owning 4 accounts (Chequing/Savings/Visa/Mortgage), 10 txns.
import { chromium } from '@playwright/test';

const BASE = process.env.BASE_URL || 'http://localhost:8099';
const results: { name: string; ok: boolean; detail: string }[] = [];
const check = (name: string, ok: unknown, detail: unknown = ''): void => {
  results.push({ name, ok: !!ok, detail: detail == null ? '' : String(detail) });
};

const browser = await chromium.launch();
const page = await browser.newPage();
const logs: string[] = [];
page.on('console', (m) => logs.push(m.text()));
page.on('pageerror', (e) => logs.push('PAGEERROR: ' + e.message));

await page.goto(`${BASE}/setup`, { waitUntil: 'networkidle' });

// 0. No JS errors — also proves the plaid-link island module loads cleanly.
check('no page errors', !logs.some((l) => l.startsWith('PAGEERROR')),
  logs.filter((l) => l.startsWith('PAGEERROR')).join('; '));

// 1. Masthead present with Setup tab active.
const activeTab = await page.locator('.view-tab.is-active').innerText().catch(() => '');
check('masthead Setup tab active', activeTab.trim() === 'Setup', `active="${activeTab.trim()}"`);

// 2. Stats cards reflect the seed (1 / 4 / 10).
const statValues = await page.locator('.stats-grid .stat-value').allInnerTexts();
check('stat cards = [1, 4, 10]', JSON.stringify(statValues.map((s) => s.trim())) === '["1","4","10"]',
  JSON.stringify(statValues));

// 3. One connection card for "Test Bank".
const cardCount = await page.locator('.connection-card').count();
check('one connection card', cardCount === 1, `cards=${cardCount}`);
const connName = await page.locator('.connection-card .connection-name').first().innerText().catch(() => '');
check('connection named Test Bank', connName.trim() === 'Test Bank', connName);

// 4. The connection shows a Synced status pill and a last-synced line.
const pill = await page.locator('.connection-card .status-pill').first().innerText().catch(() => '');
check('status pill = Synced', /synced/i.test(pill), pill);
const synced = await page.locator('.connection-card .connection-synced').first().innerText().catch(() => '');
check('shows last synced', /last synced/i.test(synced), synced);

// 5. Per-connection Resync action present, posting to /setup/resync.
// Resync is a Datastar @post (patches the card live), NOT a reload-causing form.
const resyncAttr = await page.locator('.connection-card button', { hasText: 'Resync' })
  .getAttribute('data-on:click');
check('Resync is a Datastar @post to /setup/resync', /@post\('\/setup\/resync/.test(resyncAttr || ''),
  String(resyncAttr));
check('no reload-causing resync form', await page.locator('form.connection-resync').count() === 0);

// 6. The connection's account table has 4 rows, names sorted in the first column.
const rowCount = await page.locator('.connection-card table.table tbody tr').count();
check('account table has 4 rows', rowCount === 4, `rows=${rowCount}`);
const names = await page.locator('.connection-card table.table tbody tr td:nth-child(1)').allInnerTexts();
check('account names sorted [Chequing, Mortgage, Savings, Visa]',
  JSON.stringify(names.map((n) => n.trim())) === '["Chequing","Mortgage","Savings","Visa"]',
  JSON.stringify(names));

// 7. Type column shows internal account types (no provider-type on seed data).
const types = await page.locator('.connection-card table.table tbody tr td:nth-child(2)').allInnerTexts();
check('type column = [chequing, loan, savings, credit]',
  JSON.stringify(types.map((t) => t.trim())) === '["chequing","loan","savings","credit"]',
  JSON.stringify(types));

// 8. Action bar: Sync all (Datastar @post, no form → no flash), Link Bank Account
//    (island button), Connect Lunchflow.
const syncAllAttr = await page.locator('button', { hasText: 'Sync all' }).getAttribute('data-on:click');
check('Sync all is a Datastar @post to /setup/sync', /@post\('\/setup\/sync'\)/.test(syncAllAttr || ''),
  String(syncAllAttr));
check('no reload-causing sync form', await page.locator('form[action="/setup/sync"]').count() === 0);
check('Link Bank Account button present (#plaid-link-btn)',
  await page.locator('#plaid-link-btn').count() === 1);
const lunchHref = await page.locator('a', { hasText: 'Connect Lunchflow' }).getAttribute('href');
check('Connect Lunchflow links to /setup/lunchflow', lunchHref === '/setup/lunchflow', String(lunchHref));

// 9. The carried-over design system actually applied (typography token resolved).
const bodyFont = await page.evaluate(() => getComputedStyle(document.body).fontFamily);
check('design system CSS applied (Hanken Grotesk on body)', /Hanken Grotesk/i.test(bodyFont), bodyFont);

// (No-reload is proven structurally above: the actions are Datastar @post with no
//  <form>, so there's no full-page submit. We deliberately don't *click* Sync all
//  here — it would fire a real Plaid call against the seed connection, making the
//  otherwise-hermetic suite depend on the network. The live-patch render is covered
//  by the kaocha render test; verify the end-to-end sync manually against Plaid.)

// 10. The Lunchflow selection page renders. The handler renders an inline error
//     when Lunchflow is unreachable (no key in the e2e env), which still proves
//     the page + masthead render.
await page.goto(`${BASE}/setup/lunchflow`, { waitUntil: 'networkidle' });
const lunchTitle = await page.locator('.page-title').innerText().catch(() => '');
check('lunchflow page renders', lunchTitle.trim() === 'Connect Lunchflow', lunchTitle);

await browser.close();

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

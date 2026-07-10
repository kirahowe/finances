// Browser-driven verification of the server-rendered /setup page: the
// connection-grouped account list (status pill, last-synced, per-connection
// Resync), the action bar (Sync all / Link Bank Account / Connect Lunchflow),
// and that the Plaid Link island loads without error. Run against the seeded
// e2e backend:
//   BASE_URL=http://localhost:8099 node e2e/setup.ts
//
// The seed (env/e2e .../seed.clj) has 1 institution, a synced Plaid connection
// ("Test Bank") owning 4 accounts (Chequing/Savings/Visa/Mortgage), 11 txns
// (the 10 January rows + the basis-lens straddler posted 2025-02).
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

// 2. Stats cards reflect the seed (1 institution / 4 accounts / 11 txns — the 10 January
// rows plus the basis-lens straddler, which is posted into February but still counts here).
const statValues = await page.locator('.stats-grid .stat-value').allInnerTexts();
check('stat cards = [1, 4, 11]', JSON.stringify(statValues.map((s) => s.trim())) === '["1","4","11"]',
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

// 6. The connection's account table has 4 rows. The Name cell is an inline click-to-edit
//    (resting text = the shown label; a hidden input commits over @put — web.inline-edit),
//    sorted by the shown label. No form, no Save button, no bordered inputs at rest.
const rowCount = await page.locator('.connection-card table.table tbody tr').count();
check('account table has 4 rows', rowCount === 4, `rows=${rowCount}`);
const nameButtons = page.locator('.connection-card td.account-name-cell button.account-name-button');
const restingNames = await nameButtons.allInnerTexts();
check('account names sorted [Chequing, Mortgage, Savings, Visa]',
  JSON.stringify(restingNames.map((t) => t.trim())) === '["Chequing","Mortgage","Savings","Visa"]',
  JSON.stringify(restingNames));
check('no rename form / Save buttons anywhere',
  (await page.locator('.connection-card form.account-rename-form').count()) === 0 &&
  (await page.locator('.connection-card button:has-text("Save")').count()) === 0);
check('no muted original-name caption shows without an override',
  await page.locator('.connection-card .account-original-name').count() === 0);
const commitJs = await page.locator('.connection-card input.account-name-input').first()
  .getAttribute('data-on:keydown');
check('name commit @puts /setup/account/<id>/name', /@put\('\/setup\/account\/.+\/name'\)/.test(commitJs || ''),
  String(commitJs));

// 6b. A real rename round-trip: click Chequing's name, type an override, Enter → the cell
//     morphs back server-confirmed with a transient ✓ and the muted provider-name caption;
//     then clear it back (blank commit retracts the override) so the shared seed DB leaves
//     this spec exactly as it entered (the suite's trailing-reset convention).
const chequingCell = page.locator('.connection-card td.account-name-cell', { hasText: 'Chequing' }).first();
await chequingCell.locator('button.account-name-button').click();
const chequingInput = chequingCell.locator('input.account-name-input');
await chequingInput.fill('Daily Chequing');
await chequingInput.press('Enter');
await page.waitForFunction(() =>
  document.querySelector('.connection-card .account-original-name') !== null, null, { timeout: 5000 });
check('rename morphs back with the override as the resting name',
  (await chequingCell.locator('button.account-name-button').innerText()).trim() === 'Daily Chequing');
check('saved ✓ flashes in the morphed cell',
  (await chequingCell.locator('.name-saved-check').count()) === 1);
check('provider name shows muted alongside the override',
  (await chequingCell.locator('.account-original-name').innerText()).trim() === 'Chequing');
await chequingCell.locator('button.account-name-button').click();
await chequingInput.fill('');
await chequingInput.press('Enter');
await page.waitForFunction(() =>
  document.querySelector('.connection-card .account-original-name') === null, null, { timeout: 5000 });
check('blank commit clears the override back to the provider name',
  (await chequingCell.locator('button.account-name-button').innerText()).trim() === 'Chequing');

// 7. Type column shows internal account types (no provider-type on seed data).
const types = await page.locator('.connection-card table.table tbody tr td:nth-child(2)').allInnerTexts();
check('type column = [chequing, loan, savings, credit]',
  JSON.stringify(types.map((t) => t.trim())) === '["chequing","loan","savings","credit"]',
  JSON.stringify(types));

// 7b. No per-row Sync button — the seed's accounts are all Plaid (Sync is Lunchflow-only).
check('no per-account Sync button for Plaid accounts',
  await page.locator('.connection-card [data-on\\:click*="/setup/sync-account"]').count() === 0);

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

// 9. The design system actually applied (typography token resolved).
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

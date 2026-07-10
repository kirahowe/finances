// Institution avatars + the Institution column's name-or-logo display option.
// The avatar (web.shell/institution-avatar) is a logo <img> or a letter-circle
// fallback; the seed's "Test Bank" has no logo, so these checks prove the
// FALLBACK path at every surface (the <img> path is covered by the kaocha render
// tests). The column option is the showPosted pattern: a View-menu Display
// checkbox flips the table's `inst-logos` class off $instLogo — a pure CSS flip,
// no round-trip — and the url island persists the exception as instlogo=1. In
// logo mode the name span is CLIPPED (1px, not display:none) so in-page search
// and screen readers keep the text; the cell title carries the hover name.
// Read-only throughout (navigations + client-side signals + the reconcile
// drill's filter @get) — no /e2e/reset needed; nothing mutates persisted data.
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

const instCell = () => page.locator('#tx-tbody tr td.institution-cell').first();

// --- 1. Default = name mode: text visible, avatar hidden, clean URL. -----------
await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
check('institution cells render', (await instCell().count()) === 1);
check('name mode: institution name text shows', /Test Bank/.test(await instCell().innerText()), await instCell().innerText());
check('name mode: avatar hidden', !(await instCell().locator('.institution-avatar').isVisible().catch(() => false)));
check('name mode: no instlogo in the URL', !page.url().includes('instlogo'), page.url());

// --- 2. View menu → "Institution logos" flips to logo mode, URL persists it. ---
await page.locator('.column-picker .filter-button').click();
const logoToggle = page.locator('.view-menu .filter-dropdown-checkbox-label', { hasText: 'Institution logos' });
check('View menu Display group offers "Institution logos"', (await logoToggle.count()) === 1);
await logoToggle.locator('input').click();
await page.waitForTimeout(300);
const letter = instCell().locator('.institution-avatar--letter');
check('logo mode: letter-circle fallback visible', await letter.isVisible());
check('logo mode: the letter is T (Test Bank)', (await letter.innerText()).trim() === 'T', await letter.innerText());
const nameBox = await instCell().locator('.institution-cell-name').boundingBox();
check('logo mode: name span clipped to 1px, NOT display:none', !!nameBox && nameBox.width <= 1 && nameBox.height <= 1, JSON.stringify(nameBox));
check('logo mode: cell title carries the hover name', (await instCell().getAttribute('title')) === 'Test Bank', String(await instCell().getAttribute('title')));
check('logo mode: URL gains instlogo=1', page.url().includes('instlogo=1'), page.url());

// --- 3. Reload restores logo mode from the URL. ---------------------------------
await page.reload({ waitUntil: 'networkidle' });
await page.waitForTimeout(300);
check('reload keeps logo mode', await instCell().locator('.institution-avatar--letter').isVisible());
check('reload keeps the View checkbox checked', await page.locator('.view-menu input[data-bind="instLogo"]').isChecked());

// --- 4. Reconcile panel: overview rows + the focused card carry the avatar. -----
check('reconcile overview row carries the avatar',
  (await page.locator('.reconcile-row .reconcile-account .institution-avatar--letter').first().count()) === 1);
await page.locator('.reconcile-drill').first().click();
await page.waitForSelector('.reconcile-focus', { timeout: 5000 });
check('focused card title carries the avatar',
  (await page.locator('.reconcile-focus-title .institution-avatar--letter').count()) === 1);
await page.locator('.reconcile-back').click();
await page.waitForSelector('.reconcile-row', { timeout: 5000 });

// --- 5. Edges: only instlogo=1 means logo mode; hiding the column wins. ---------
await page.goto(`${BASE}/?month=2025-01&instlogo=banana`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
check('instlogo=banana → name mode (1 is the only logo token)',
  /Test Bank/.test(await instCell().innerText())
  && !(await instCell().locator('.institution-avatar').isVisible().catch(() => false)));
await page.goto(`${BASE}/?month=2025-01&instlogo=1&hidecols=institution`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
check('logo mode + hidden Institution column → the cell hides entirely',
  !(await instCell().isVisible().catch(() => false)));

// --- 6. Setup: the connection card shows the avatar next to the institution. ----
await page.goto(`${BASE}/setup`, { waitUntil: 'networkidle' });
const connAvatar = page.locator('.connection-card .connection-id .institution-avatar--letter');
check('setup connection card carries the avatar', (await connAvatar.count()) === 1);
check('setup avatar letter is T', (await connAvatar.innerText()).trim() === 'T', await connAvatar.innerText());

check('no page errors', !logs.some((l) => l.startsWith('PAGEERROR')),
  logs.filter((l) => l.startsWith('PAGEERROR')).join('; '));

await browser.close();

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

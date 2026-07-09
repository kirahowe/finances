// Real-Chromium proof of the split editor under the "splits as transactions" model: row-actions
// menu → modal → live balance math (uncategorized parts are now saveable) → save (round-tripped
// through the command log) → the original row disappears and TWO independent, first-class rows
// render in its place (each with its own live reviewed checkbox and category cell, plus a
// payee-cell marker back to the family) → those rows filter independently under the
// needs-review scope (the headline bug the redesign fixes) → the marker/row-actions menu route
// back to the parent's editor → undo fully reverts.
//
//   BASE_URL=http://localhost:8099 node e2e/v2-split.ts
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

// Clean slate (this spec mutates: it creates a split), then load the workspace.
await page.request.post(`${BASE}/e2e/reset`);
await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
check('no page errors on load', !logs.length, logs.join('; '));

const superstore = () => page.locator('#tx-tbody tr', { hasText: 'Superstore' }).first();
// A split part row: a normal #tx-tbody row carrying `is-split-part`, sharing the parent's payee.
const splitParts = () => page.locator('#tx-tbody tr.is-split-part').filter({ hasText: 'Superstore' });
const partWithCategory = (name: string) => splitParts().filter({ hasText: name });
const unsplitSuperstore = () =>
  page.locator('#tx-tbody tr:not(.is-split-part)').filter({ hasText: 'Superstore' });

// --- Open the row-actions menu and the split editor for the (unsplit) Superstore row. ---------
await superstore().hover();
await superstore().locator('.row-actions-trigger').click();
const menu = page.locator('#row-actions-menu');
await menu.waitFor({ state: 'visible' });
const splitItem = menu.locator('.row-actions-item').first(); // [0]=Split, [1]=Match
check('menu offers "Split transaction" for an unsplit row',
  (await splitItem.innerText()).trim() === 'Split transaction');
await splitItem.click();

const modal = page.locator('.split-modal-content');
await modal.waitFor({ state: 'visible' });
const dataRows = modal.locator('.split-rows-container .split-row');
check('editor opens with two blank parts', (await dataRows.count()) === 2);

// The split category control is the SAME Zag combobox/typeahead as the grid cell: click the
// row's category button → the floating dropdown opens → type to filter → pick. (Mirrors
// e2e/v2-category.ts: root .category-dropdown.is-floating, input .category-dropdown-input,
// options .category-dropdown-item.) The pick updates LOCAL row state only — nothing persists
// until Save.
const dropdown = page.locator('.category-dropdown.is-floating');
const pickCategory = async (row, name) => {
  await row.locator('.split-category-cell .category-button').click();
  await dropdown.waitFor({ state: 'visible', timeout: 5000 });
  await page.locator('.category-dropdown-input').fill('');
  await page.locator('.category-dropdown-input').type(name);
  await page.waitForTimeout(120); // re-filter + typeahead highlight settle
  await page.locator('.category-dropdown-item', { hasText: new RegExp(`^${name}$`) }).first().click();
  await dropdown.waitFor({ state: 'hidden', timeout: 5000 }).catch(() => {});
};

// Part 1: allocate + categorize. The editor is unbalanced, so Save is gated.
await dataRows.nth(0).locator('.split-amount-input').fill('80.00');
await pickCategory(dataRows.nth(0), 'Groceries');
check('category button shows the picked category',
  (await dataRows.nth(0).locator('.split-category-cell .category-button').innerText()).trim() === 'Groceries');
check('save disabled while unbalanced', await modal.locator('.split-save').isDisabled());

// Part 2: fill the remainder, but leave it UNCATEGORIZED — the new behavior under test.
await dataRows.nth(1).locator('.split-fill-button').click();
check('fill set the remainder to 5.00',
  (await dataRows.nth(1).locator('.split-amount-input').inputValue()) === '5.00');
check('part 2 stays uncategorized (placeholder shown)',
  (await dataRows.nth(1).locator('.split-category-cell .category-button').innerText()).trim() === 'Select category…');
check('balance reads "Balanced"', /balanced/i.test(await modal.locator('.split-remaining').innerText()));
check('save enabled once balanced with an uncategorized part',
  !(await modal.locator('.split-save').isDisabled()));

// --- Save → the command applies server-side, the row morphs into two first-class part rows. ---
await modal.locator('.split-save').click();
await page.waitForFunction(
  () => document.querySelectorAll('#tx-tbody tr.is-split-part').length === 2,
  null, { timeout: 5000 }).catch(() => {});
check('modal closed after save', (await modal.count()) === 0);
check('the original (unsplit) row is gone', (await unsplitSuperstore().count()) === 0);
check('two split-part rows render instead', (await splitParts().count()) === 2);
check('each part has a split-marker in the payee cell',
  (await splitParts().locator('.payee-cell .split-marker').count()) === 2);
check('each part has a live reviewed checkbox',
  (await splitParts().locator('.reviewed-checkbox').count()) === 2);
check('the categorized part reads "Groceries"', (await partWithCategory('Groceries').count()) === 1);
check("the uncategorized part's category cell reads Uncategorized",
  (await partWithCategory('Uncategorized').locator('.category-button').innerText()).trim() === 'Uncategorized');

// --- Toggle exactly one part's reviewed checkbox → it persists across the SSE morph. -----------
await partWithCategory('Groceries').locator('.reviewed-checkbox').click();
await page.waitForTimeout(400);
check("the Groceries part's reviewed checkbox stays checked after the morph",
  await partWithCategory('Groceries').locator('.reviewed-checkbox').isChecked());
check("the Uncategorized part's reviewed checkbox is untouched",
  !(await partWithCategory('Uncategorized').locator('.reviewed-checkbox').isChecked()));

// --- Scope to needs-review: parts filter INDEPENDENTLY — the headline bug this fixes. ----------
await page.locator('.scope-toggle-btn', { hasText: 'Needs review' }).click();
await page.waitForTimeout(400);
check('needs-review hides the now-reviewed part', (await partWithCategory('Groceries').count()) === 0);
check('needs-review still shows the unreviewed part', (await partWithCategory('Uncategorized').count()) === 1);

// --- Back to all: click a part's split-marker → editor opens in "Edit split" state. ------------
await page.locator('.scope-toggle-btn', { hasText: 'All' }).click();
await page.waitForTimeout(400);
check('both parts visible again under All', (await splitParts().count()) === 2);

await partWithCategory('Uncategorized').locator('.split-marker').click();
await modal.waitFor({ state: 'visible' });
check('editor re-opens titled "Edit split"',
  (await page.locator('#split-modal-title').innerText()).trim() === 'Edit split');
const editRows = modal.locator('.split-rows-container .split-row');
check('editor is seeded with the two existing parts', (await editRows.count()) === 2);
check('seeded amounts are 80.00 and 5.00',
  (await editRows.nth(0).locator('.split-amount-input').inputValue()) === '80.00' &&
  (await editRows.nth(1).locator('.split-amount-input').inputValue()) === '5.00');
check('the seeded uncategorized part still shows the placeholder',
  (await editRows.nth(1).locator('.split-category-cell .category-button').innerText()).trim() === 'Select category…');

// Cancel closes without persisting.
await modal.locator('.split-cancel').click();
check('Cancel closes the editor', (await modal.count()) === 0);

// --- A part's row-actions menu reads "Edit split" and targets the parent. ----------------------
const somePart = () => partWithCategory('Groceries');
await somePart().hover();
await somePart().locator('.row-actions-trigger').click();
await menu.waitFor({ state: 'visible' });
check('a part\'s row-actions menu reads "Edit split"',
  (await menu.locator('.row-actions-item').first().innerText()).trim() === 'Edit split');
await page.keyboard.press('Escape');
await menu.waitFor({ state: 'hidden' }).catch(() => {});

// --- Undo until the split is fully reverted. ----------------------------------------------------
// Two commands are on the stack (the reviewed toggle, then the split itself), so undo may need
// to run twice before the parts are gone.
for (let i = 0; i < 4 && (await splitParts().count()) > 0; i++) {
  await page.locator('#undo-redo button[aria-label="Undo"]').click();
  await page.waitForTimeout(400);
}
check('undo fully reverts the split (no split-part rows remain)', (await splitParts().count()) === 0);
check('the original single Superstore row is back', (await unsplitSuperstore().count()) === 1);
check("un-splitting restores the parent's prior category (Groceries)",
  (await unsplitSuperstore().locator('.category-button').innerText()).trim() === 'Groceries');

check('no page errors', !logs.length, logs.join('; '));

await browser.close();

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

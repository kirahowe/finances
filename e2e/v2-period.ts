// Real-Chromium proof for the period picker (the popover under the transactions dateline).
// The dateline is a button (#period-toggle) that toggles a popover (#period-picker): a rail of
// quick links, a year-steppable month grid (SSE re-patched — GET /transactions/period-picker/
// months), and a custom-range footer (two native date inputs + Apply). Every destination
// navigates like the prev/next arrows — a real page load that preserves the rest of the view
// state (see period-nav-js) — except the year steppers, which only SSE-morph the grid pane.
// Read-only throughout (only navigates + toggles client-side UI signals), so no /e2e/reset is
// needed — nothing here mutates persisted data.
//
//   BASE_URL=http://localhost:8099 node e2e/v2-period.ts
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

const toggle = () => page.locator('#period-toggle');
const popover = () => page.locator('#period-picker');
const dateline = () => page.locator('#period-navigator-display');
const monthsPane = () => page.locator('#period-picker-months');
const yearLabel = () => page.locator('.period-picker-year-label');
const monthCell = (label: string) => monthsPane().getByRole('link', { name: label, exact: true });
const rowCount = () => page.locator('#tx-tbody tr').count();

// --- 1. Fresh load: dateline is a button, closed by default; a filter (q=Payroll) rides
// along for every persistence check below. -----------------------------------------------
await page.goto(`${BASE}/?month=2025-01&q=Payroll`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
check('no page errors on load', !logs.some((l) => l.startsWith('PAGEERROR')), logs.join('; '));
check('dateline is a button#period-toggle', (await toggle().count()) === 1);
check('closed by default: aria-expanded=false', (await toggle().getAttribute('aria-expanded')) === 'false');
check('closed by default: popover hidden', !(await popover().isVisible()));

// --- 2. Open the picker: rail (8 quick links), year label + grid for the VIEWED year (2025,
// not the real current year), Jan selected. ----------------------------------------------
await toggle().click();
check('open: aria-expanded=true', (await toggle().getAttribute('aria-expanded')) === 'true');
check('open: popover visible', await popover().isVisible());
check('rail has 8 quick-link items (this month + 5 previous + YTD + last 90 days)',
  (await page.locator('.period-picker-rail-item').count()) === 8);
check('no rail quick-link is selected (viewing 2025-01, real "today" is 2026-07)',
  (await page.locator('.period-picker-rail-item.is-selected').count()) === 0);
check('grid initially shows the VIEWED period\'s year (2025), not the real current year',
  (await yearLabel().innerText()).trim() === '2025');
check('grid renders 12 month cells', (await monthsPane().locator('a.period-picker-month').count()) === 12);
check('January cell is selected', (await monthCell('January 2025').getAttribute('class'))?.includes('is-selected'));

// --- 3. Year stepper: SSE re-patches #period-picker-months only — no full navigation, so
// the URL stays put. ----------------------------------------------------------------------
const urlBeforeYearStep = page.url();
await monthsPane().getByRole('button', { name: /^Previous year/ }).click();
await page.waitForFunction(() => document.querySelector('.period-picker-year-label')?.textContent?.trim() === '2024',
  null, { timeout: 5000 }).catch(() => {});
check('‹ steps the pane back to 2024', (await yearLabel().innerText()).trim() === '2024');
check('year step does not navigate (URL unchanged)', page.url() === urlBeforeYearStep, page.url());

await monthsPane().getByRole('button', { name: /^Next year/ }).click();
await page.waitForFunction(() => document.querySelector('.period-picker-year-label')?.textContent?.trim() === '2025',
  null, { timeout: 5000 }).catch(() => {});
check('› steps the pane back to 2025', (await yearLabel().innerText()).trim() === '2025');

// --- 4. Click a month cell: a real navigation that preserves other params, drops page,
// and lands closed (a fresh load never carries the popover open). ------------------------
await Promise.all([
  page.waitForURL(/month=2025-02/, { timeout: 5000 }),
  monthCell('February 2025').click(),
]);
await page.waitForLoadState('networkidle');
await page.waitForTimeout(300);
const afterMonthClick = new URL(page.url());
check('month-grid click navigates to that month', afterMonthClick.searchParams.get('month') === '2025-02', afterMonthClick.search);
check('month-grid click preserves other params (q=Payroll)', afterMonthClick.searchParams.get('q') === 'Payroll', afterMonthClick.search);
check('month-grid click resets page', !afterMonthClick.searchParams.get('page'), afterMonthClick.search);
check('popover is closed after the fresh load', (await toggle().getAttribute('aria-expanded')) === 'false');

// --- 5. Custom range: Apply disabled when to < from, enabled once corrected; Apply navigates
// to from/to (no month), preserving q. ----------------------------------------------------
await page.goto(`${BASE}/?month=2025-01&q=Payroll`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
await toggle().click();
const applyBtn = page.locator('.period-picker-apply');

await page.locator('#picker-from').fill('2025-01-20');
await page.locator('#picker-to').fill('2025-01-10');
check('Apply disabled when to < from', await applyBtn.isDisabled());

await page.locator('#picker-from').fill('2025-01-10');
await page.locator('#picker-to').fill('2025-01-20');
check('Apply enabled once from <= to', !(await applyBtn.isDisabled()));

await Promise.all([
  page.waitForURL(/from=2025-01-10/, { timeout: 5000 }),
  applyBtn.click(),
]);
await page.waitForLoadState('networkidle');
await page.waitForTimeout(300);
const afterApply = new URL(page.url());
check('Apply navigates to the custom range', afterApply.searchParams.get('from') === '2025-01-10' && afterApply.searchParams.get('to') === '2025-01-20', afterApply.search);
check('Apply drops the month param', !afterApply.searchParams.get('month'), afterApply.search);
check('Apply preserves q=Payroll', afterApply.searchParams.get('q') === 'Payroll', afterApply.search);

// --- 6. In range view: dateline shows the span, × appears, the reconcile panel shows the
// range note, and the next arrow's title names its own (equal-length) target span. ---------
const datelineText = () => dateline().innerText();
check('dateline shows the range span', /Jan 10/.test(await datelineText()) && /Jan 20/.test(await datelineText()), await datelineText());
const clearLink = page.locator('a.month-nav-clear');
check('× (back-to-month) is visible in range view', await clearLink.isVisible());
check('× title names the range\'s containing month', (await clearLink.getAttribute('title')) === 'Back to January 2025');
check('.reconcile-range-note is visible with the exact copy',
  (await page.locator('.reconcile-range-note').innerText()).trim() === 'Monthly close works on calendar months.');
check('the range-back link reads "Back to January 2025"',
  (await page.locator('.reconcile-range-back').innerText()).trim() === 'Back to January 2025');
const nextArrow = page.locator('a.month-nav-button[title^="Next"]');
check('next-arrow title reads "Next: …" (a range step, not "Next month")', /^Next: /.test((await nextArrow.getAttribute('title')) || ''));
const prevArrow = page.locator('a.month-nav-button[title^="Previous"]');
check('previous-arrow title reads "Previous: …"', /^Previous: /.test((await prevArrow.getAttribute('title')) || ''));

// Compound filter proof: q=Payroll (Acme Payroll, Jan 1) is still riding the URL here, and Jan 1
// falls OUTSIDE the 10-20 range — so the table reads 0 rows. That's a real assertion about the
// range narrowing, not a tautology: were the date bound not actually applied server-side, the
// search alone would still surface the 1 Payroll row regardless of range.
check('range + search compose: q=Payroll has no match inside Jan 10-20 -> 0 rows', (await rowCount()) === 0, `rows=${await rowCount()}`);

// A clean look at the SAME range with no search filter riding along: the seed's 10 January
// transactions land on 1, 5, 10, 11, 12, 12, 15, 15, 18, 25 -- exactly 7 of those fall inside
// [10, 20], so the range itself (independent of any search) narrows 10 -> 7.
await page.goto(`${BASE}/?from=2025-01-10&to=2025-01-20`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);
check('range alone (no search) narrows the month\'s 10 rows to 7', (await rowCount()) === 7, `rows=${await rowCount()}`);

// Back to the exact from/to/q state to continue the slide + × assertions.
await page.goto(`${BASE}/?from=2025-01-10&to=2025-01-20&q=Payroll`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);

// Click the next arrow: an 11-day range (Jan 10-20 inclusive) slides forward by its own
// length, landing on [Jan 21, Jan 31].
await Promise.all([
  page.waitForURL(/from=2025-01-21/, { timeout: 5000 }),
  page.locator('a.month-nav-button[title^="Next"]').click(),
]);
await page.waitForLoadState('networkidle');
await page.waitForTimeout(300);
const afterSlide = new URL(page.url());
check('next arrow slides an 11-day range by 11 days', afterSlide.searchParams.get('from') === '2025-01-21' && afterSlide.searchParams.get('to') === '2025-01-31', afterSlide.search);
check('dateline updates to the slid span', /Jan 21/.test(await datelineText()) && /Jan 31/.test(await datelineText()), await datelineText());

// --- 7. × leaves range view for the range's containing month, dropping from/to but keeping
// other params. -----------------------------------------------------------------------------
await Promise.all([
  page.waitForURL(/month=2025-01/, { timeout: 5000 }),
  page.locator('a.month-nav-clear').click(),
]);
await page.waitForLoadState('networkidle');
await page.waitForTimeout(300);
const afterClear = new URL(page.url());
check('× navigates back to month=2025-01', afterClear.searchParams.get('month') === '2025-01', afterClear.search);
check('× drops from/to', !afterClear.searchParams.get('from') && !afterClear.searchParams.get('to'), afterClear.search);
check('× preserves q=Payroll', afterClear.searchParams.get('q') === 'Payroll', afterClear.search);

// --- 8. Escape and click-outside both close the popover. -----------------------------------
await toggle().click();
check('reopened: aria-expanded=true', (await toggle().getAttribute('aria-expanded')) === 'true');
await page.keyboard.press('Escape');
check('Escape closes the popover', (await toggle().getAttribute('aria-expanded')) === 'false');
check('Escape closes the popover: hidden', !(await popover().isVisible()));

await toggle().click();
check('reopened again: aria-expanded=true', (await toggle().getAttribute('aria-expanded')) === 'true');
// A plain background click, not `.table-search-input`: the popover (anchored under the
// dateline, on the left) overlays much of the toolbar's width, so a "click outside" target
// still has to land outside its actual bounding box or the click just hits the popover itself
// (and never reaches anything's click-outside listener).
await page.mouse.click(5, 5);
check('click-outside closes the popover', (await toggle().getAttribute('aria-expanded')) === 'false');
check('click-outside closes the popover: hidden', !(await popover().isVisible()));

check('still no page errors', !logs.some((l) => l.startsWith('PAGEERROR')), logs.join('; '));

await browser.close();

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

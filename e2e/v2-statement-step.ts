// Real-Chromium proof for stepping the statement lens (the reconcile panel's per-statement
// table-narrowing — see reconcile-range in web.pages.transactions) via the navigator's prev/
// next arrows. While the lens is active, an arrow click steps it to the account's REAL adjacent
// statement (web.statement-lens/adjacent-span, by start-date) instead of the plain period —
// landing the viewed month on the new span's end date — and falls back to shifting the current
// span by one calendar month when there's no further statement in that direction. The step is
// an in-place SSE round trip (GET /transactions/statement-step, morphing the same fragments
// /transactions/period does — see period-change-response), and the whole thing is now
// URL-persisted (month/reconFrom/reconTo — see url.ts + the `page` handler's restore), so a
// reload lands on the exact narrowed view.
//
//   BASE_URL=http://localhost:8099 node e2e/v2-statement-step.ts
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

const focus = page.locator('.reconcile-focus');
const rowBy = (name: string) =>
  page.locator('.reconcile-row', { has: page.locator('.reconcile-account', { hasText: name }) });
const dateline = () => page.locator('#period-navigator-display');
const stModal = page.locator('#modal-root [role="dialog"]');
const nextArrow = () => page.locator('.month-nav-button').nth(1);
// The no-reload proof: a full page load wipes window.__marker; an in-place statement step
// (like every other in-place view change) must leave it intact.
const armMarker = () => page.evaluate(() => ((window as unknown as { __marker?: number }).__marker = 1));
const marker = () => page.evaluate(() => (window as unknown as { __marker?: number }).__marker);

const addStatement = async (start: string, startBal: string, end: string, endBal: string) => {
  await focus.locator('.reconcile-add-statement').click();
  await stModal.waitFor({ state: 'visible', timeout: 5000 });
  await page.locator('#st-start').fill(start);
  await page.locator('#st-start-bal').fill(startBal);
  await page.locator('#st-end').fill(end);
  await page.locator('#st-end-bal').fill(endBal);
  await page.locator('.form-modal-content .button-primary').click();
};

await page.request.post(`${BASE}/e2e/reset`);
await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(200);
check('no page errors on load', !logs.length, logs.join('; '));

// 1. Filter to one account (Visa) via the reconcile panel's drill.
await rowBy('Visa').locator('.reconcile-drill').click();
await focus.waitFor({ state: 'visible', timeout: 5000 });
check('drilled into Visa', /Visa/.test(await focus.innerText()));

// 2. Two adjacent statements: Jan 1-10, then Jan 11-20.
await addStatement('2025-01-01', '0', '2025-01-10', '-50');
await focus.locator('.reconcile-statement').first().waitFor({ state: 'visible', timeout: 5000 });
await addStatement('2025-01-11', '-50', '2025-01-20', '-85');
await page.waitForFunction(
  () => document.querySelectorAll('.reconcile-statement').length === 2,
  undefined, { timeout: 5000 });
check('both statements created', (await focus.locator('.reconcile-statement').count()) === 2);

// 3. Lens onto the first statement.
await focus.locator('.reconcile-statement', { hasText: 'Jan 1' }).first()
  .locator('.reconcile-statement-span').click();
await page.waitForFunction(
  () => /Jan 1 –/.test(document.querySelector('#period-navigator-display')?.textContent || ''),
  undefined, { timeout: 5000 });
check('dateline narrows to the first statement\'s span', /Jan 1 – Jan 10, 2025/.test(await dateline().innerText()));
check('the first statement is highlighted',
  (await focus.locator('.reconcile-statement.is-selected').count()) === 1);

// 4. Click next: steps to the SECOND statement's span (a real adjacent statement, not a plain
//    period step) and highlights it — an in-place SSE round trip (the marker survives).
await armMarker();
await nextArrow().click();
await page.waitForFunction(
  () => /Jan 11/.test(document.querySelector('#period-navigator-display')?.textContent || ''),
  undefined, { timeout: 5000 }).catch(() => {});
check('next arrow steps the dateline to the second statement\'s span',
  /Jan 11 – Jan 20, 2025/.test(await dateline().innerText()), await dateline().innerText());
const selected = await focus.locator('.reconcile-statement.is-selected').innerText().catch(() => '');
check('the second statement is now the highlighted one', /Jan 11/.test(selected), selected);
check('the step happened in place (no full reload — window.__marker survived)', (await marker()) === 1);

const urlAfterFirstStep = new URL(page.url());
check('url carries month=2025-01', urlAfterFirstStep.searchParams.get('month') === '2025-01', urlAfterFirstStep.toString());
check('url carries reconFrom=2025-01-11', urlAfterFirstStep.searchParams.get('reconFrom') === '2025-01-11', urlAfterFirstStep.toString());
check('url carries reconTo=2025-01-20', urlAfterFirstStep.searchParams.get('reconTo') === '2025-01-20', urlAfterFirstStep.toString());
check('url still carries the account focus (fa)', !!urlAfterFirstStep.searchParams.get('fa'), urlAfterFirstStep.toString());

// 5. Reload right here: the table stays narrowed and the dateline still shows the span — the
//    lens is restored from the URL on a full load (the `page` handler), same as every other
//    piece of persistent view state.
await page.reload({ waitUntil: 'networkidle' });
await page.waitForTimeout(200);
check('after reload, the dateline still shows the narrowed span', /Jan 11 – Jan 20, 2025/.test(await dateline().innerText()));
check('after reload, the focused card is still Visa', /Visa/.test(await focus.innerText()));
check('after reload, the second statement is still highlighted',
  (await focus.locator('.reconcile-statement.is-selected').count()) === 1);
check('still no page errors', !logs.length, logs.join('; '));

// 6. Click next again: no THIRD statement exists, so the lens falls back to shifting the
//    current span by one calendar month (both ends) — landing on Feb 11-20, and the viewed
//    month follows to February (the span's own end date).
await nextArrow().click();
await page.waitForFunction(
  () => /Feb/.test(document.querySelector('#period-navigator-display')?.textContent || ''),
  undefined, { timeout: 5000 }).catch(() => {});
check('a second next (no further statement) shifts the span +1 month',
  /Feb 11 – Feb 20, 2025/.test(await dateline().innerText()), await dateline().innerText());

const urlAfterFallback = new URL(page.url());
check('url month follows the shifted span to 2025-02', urlAfterFallback.searchParams.get('month') === '2025-02', urlAfterFallback.toString());
check('url reconFrom shifts to 2025-02-11', urlAfterFallback.searchParams.get('reconFrom') === '2025-02-11', urlAfterFallback.toString());
check('url reconTo shifts to 2025-02-20', urlAfterFallback.searchParams.get('reconTo') === '2025-02-20', urlAfterFallback.toString());

check('still no page errors after the fallback step', !logs.length, logs.join('; '));

// 7. A mangled lens param in a shared/hand-edited URL degrades to "no lens" — the page still
//    loads (never a 400 eating the whole render; url-lens-date in the `page` handler) and the
//    dateline falls back to the whole month. courier-date's throw fires before any account
//    check, so no fa param is needed to exercise the parse path.
await page.goto(`${BASE}/?month=2025-01&reconFrom=garbage&reconTo=2025-01-20`, { waitUntil: 'networkidle' });
check('a mangled reconFrom degrades to no lens (page loads, whole-month dateline)',
  /January 2025/.test(await dateline().innerText()), await dateline().innerText());

await browser.close();
// Trailing reset (shared-DB convention — see e2e/README.md): the created statements + the
// account-filter/lens state this spec leaves behind never carry into the next spec.
await fetch(`${BASE}/e2e/reset`, { method: 'POST' }).catch(() => {});

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

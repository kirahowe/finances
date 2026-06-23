// Modal focus management (the `modal` island): opening a dialog moves focus INTO it, Tab is
// trapped inside, and closing restores focus to the trigger — so keyboard / screen-reader users
// can't tab behind an open modal and don't lose their place when it closes. Driven against the
// island-less transfer-review modal (opened from the toolbar button, which stays visible so the
// restore target is unambiguous).
//
//   BASE_URL=http://localhost:8099 node e2e/v2-modal-focus.ts
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

await page.request.post(`${BASE}/e2e/reset`);
await page.goto(`${BASE}/?month=2025-01`, { waitUntil: 'networkidle' });
await page.waitForTimeout(300);

const reviewBtn = page.getByRole('button', { name: 'Review transfers' });
await reviewBtn.focus(); // a keyboard user would trigger it while focused
await reviewBtn.click();

const dialog = page.locator('.transfer-modal-content[role="dialog"]');
await dialog.waitFor({ state: 'visible' });
await page.waitForTimeout(80); // let the modal island move focus in

const focusInDialog = (): Promise<boolean> =>
  page.evaluate(() => {
    const d = document.querySelector('.transfer-modal-content');
    return !!d && !!document.activeElement && d.contains(document.activeElement);
  });

check('focus moved into the dialog on open', await focusInDialog());

// Tab-trap: focus the last focusable, press Tab → focus wraps and stays inside the dialog.
await page.evaluate(() => {
  const d = document.querySelector('.transfer-modal-content')!;
  const f = [...d.querySelectorAll<HTMLElement>('button')].filter((b) => b.offsetParent !== null);
  f[f.length - 1]?.focus();
});
await page.keyboard.press('Tab');
check('Tab is trapped within the dialog', await focusInDialog());

// Esc closes the modal and restores focus to the trigger button (not <body>).
await page.keyboard.press('Escape');
await page.waitForTimeout(120);
check('Esc closed the modal', (await dialog.count()) === 0);
check('focus restored to the trigger', await reviewBtn.evaluate((el) => el === document.activeElement));

check('no uncaught page errors', !logs.length, logs.join('; '));

await browser.close();

let pass = 0;
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'}  ${r.name}${r.detail ? `  [${r.detail}]` : ''}`);
  if (r.ok) pass++;
}
console.log('-'.repeat(52));
console.log(`${pass}/${results.length} checks passed`);
process.exit(pass === results.length ? 0 : 1);

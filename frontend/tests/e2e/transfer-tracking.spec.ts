import { test, expect } from '@playwright/test';
import { resetSeed } from './helpers';

// Runs against the seeded e2e backend (see backend/env/e2e/src/.../seed.clj). The
// dataset lives in 2025-01, so every test navigates there. The seed is reset
// before each test for isolation, so these may safely confirm/unmatch.

const MONTH = '/?month=2025-01';

test.beforeEach(async ({ request }) => {
  await resetSeed(request);
});

function row(page: import('@playwright/test').Page, payee: string) {
  return page.locator('tbody tr', { hasText: payee });
}

test('Hide transfers removes matched pure transfers but keeps a categorized (mortgage) transfer', async ({
  page,
}) => {
  await page.goto(MONTH);
  await expect(page.getByRole('button', { name: 'Find transfers' })).toBeVisible();

  // Pre-matched pure transfer (credit-card payment) and the mortgage are visible.
  await expect(row(page, 'Visa Payment')).toBeVisible();
  await expect(row(page, 'Payment Received')).toBeVisible();
  await expect(row(page, 'Mortgage Payment')).toBeVisible();

  await page.locator('label.transfer-toggle input[type="checkbox"]').check();

  // Pure transfer pair is hidden; the Housing-categorized mortgage pair stays.
  await expect(row(page, 'Visa Payment')).toHaveCount(0);
  await expect(row(page, 'Payment Received')).toHaveCount(0);
  await expect(row(page, 'Mortgage Payment')).toBeVisible();
});

test('Find transfers proposes the seeded pair; confirming then hiding removes it', async ({
  page,
}) => {
  await page.goto(MONTH);

  await page.getByRole('button', { name: 'Find transfers' }).click();
  const modal = page.locator('.transfer-modal-content');
  await expect(modal).toBeVisible();

  // Exactly one suggestion: Chequing -> Savings, $500.00.
  const suggestions = modal.locator('.transfer-suggestion');
  await expect(suggestions).toHaveCount(1);
  await expect(suggestions.first()).toContainText('Chequing');
  await expect(suggestions.first()).toContainText('Savings');
  await expect(suggestions.first()).toContainText('$500.00');

  await modal.getByRole('button', { name: /Link 1 transfer/ }).click();
  await expect(modal).toBeHidden();

  // The pair is now matched; both legs are still visible until we hide transfers.
  await expect(row(page, 'Transfer to Savings')).toBeVisible();
  await page.locator('label.transfer-toggle input[type="checkbox"]').check();
  await expect(row(page, 'Transfer to Savings')).toHaveCount(0);
  await expect(row(page, 'Transfer from Chequing')).toHaveCount(0);
});

test('An unmatched transfer is flagged and can be matched manually', async ({ page }) => {
  await page.goto(MONTH);

  // The transfer-typed transaction with no in-window counterpart is flagged.
  const unmatched = row(page, 'Transfer Out');
  await expect(unmatched).toContainText(/unmatched/i);

  // Open the manual-match modal (via the row actions menu) and confirm it lists
  // the out-of-window counterpart.
  await unmatched.getByRole('button', { name: 'Transaction actions' }).click();
  await page.getByRole('menuitem', { name: 'Match as transfer' }).click();

  const modal = page.locator('.transfer-modal-content');
  await expect(modal).toBeVisible();
  const candidates = modal.locator('.transfer-candidate');
  await expect(candidates).toHaveCount(1);
  await expect(candidates.first()).toContainText('Savings');
  await expect(candidates.first()).toContainText('$750.00');
});

import { test, expect } from '@playwright/test';

// Verifies the two ergonomics enhancements in a real browser: a sign prefix
// (amounts entered as positive magnitudes) and the per-row "rest" button that
// fills a part with the remaining balance. Read-only — it cancels without saving.
test('split modal: sign prefix and fill-remaining button balance the split', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('table')).toBeVisible();

  // Find a transaction big enough to split into two non-zero parts.
  const rows = page.locator('tbody tr');
  const count = await rows.count();
  let target = null;
  for (let i = 0; i < count; i++) {
    const amountText = (await rows.nth(i).locator('td').nth(5).textContent()) ?? '';
    if (Math.abs(parseFloat(amountText.replace(/[^0-9.]/g, ''))) >= 5) {
      target = rows.nth(i);
      break;
    }
  }
  expect(target, 'expected a transaction >= $5 to split').not.toBeNull();

  await target!.hover();
  await target!.getByRole('button', { name: 'Split', exact: true }).click();

  const modal = page.locator('.split-modal-content');
  await expect(modal).toBeVisible();

  // A sign prefix is shown so the user knows magnitudes follow the txn's sign.
  const sign = (await modal.locator('.split-amount-sign').first().textContent())?.trim();
  expect(['−', '+']).toContain(sign);

  const dataRows = modal.locator('.split-row:not(.split-row-head)');
  const amountInputs = modal.locator('.split-amount-input');

  // Enter a small positive magnitude in the first part.
  await amountInputs.first().fill('1.00');

  // Click "rest" on the second part to absorb the remaining balance.
  await dataRows.nth(1).locator('.split-fill-button').click();

  await expect(amountInputs.nth(1)).not.toHaveValue('');
  await expect(modal.locator('.split-remaining.balanced')).toHaveText('Balanced');

  // Close without saving — leave the DB untouched.
  await modal.getByRole('button', { name: 'Cancel' }).click();
});

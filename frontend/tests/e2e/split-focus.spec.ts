import { test, expect } from '@playwright/test';

// Reproduces the reported bug: opening the category dropdown by *clicking* (not
// typing) inside the split modal must let arrow keys drive the menu. Read-only —
// it never saves, so it does not mutate any data.
test('split modal category dropdown navigates with arrow keys after click-to-open', async ({
  page,
}) => {
  await page.goto('/');
  await expect(page.locator('table')).toBeVisible();

  const firstRow = page.locator('tbody tr').first();
  await firstRow.hover();
  await firstRow.getByRole('button', { name: 'Split', exact: true }).click();

  const modal = page.locator('.split-modal-content');
  await expect(modal).toBeVisible();

  // Open the first part's category dropdown by clicking (the broken path).
  await modal.locator('button.category-button').first().click();

  const input = page.locator('input.category-dropdown-input');
  await expect(input).toBeVisible();
  await expect(input).toBeFocused();

  const highlighted = page.locator('.category-dropdown-item.highlighted');

  await page.keyboard.press('ArrowDown');
  await expect(highlighted).toHaveCount(1);
  await expect(input).toBeFocused();
  const firstText = await highlighted.textContent();

  await page.keyboard.press('ArrowDown');
  await expect(highlighted).toHaveCount(1);
  await expect(input).toBeFocused();
  const secondText = await highlighted.textContent();

  expect(secondText).not.toBe(firstText);

  // Close without saving — leave the DB untouched.
  await page.keyboard.press('Escape');
});

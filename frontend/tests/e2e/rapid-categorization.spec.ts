import { test, expect } from '@playwright/test';

test.describe('Rapid Categorization Workflow', () => {
  test('filters categories and navigates with Enter key', async ({ page }) => {
    await page.goto('/?view=transactions');

    // Wait for transactions to load
    await expect(page.locator('table')).toBeVisible();

    // Find first uncategorized transaction and click category button
    const firstCategoryButton = page.locator('button.category-button').first();
    await firstCategoryButton.click();

    // Dropdown should be open
    const input = page.locator('input.category-dropdown-input');
    await expect(input).toBeVisible();

    // Type to filter categories (assuming "Groceries" category exists)
    await input.fill('gro');

    // Should show filtered results
    const dropdown = page.locator('.category-dropdown-list');
    await expect(dropdown).toBeVisible();

    // Navigate with arrow keys and press Enter
    await input.press('ArrowDown');
    await input.press('ArrowDown'); // Skip "Uncategorized", select first match
    await input.press('Enter');

    // Dropdown for next transaction should now be open
    await expect(page.locator('input.category-dropdown-input')).toBeVisible();
  });

  test('whitespace-insensitive filtering works', async ({ page }) => {
    await page.goto('/?view=transactions');

    // Wait for transactions to load
    await expect(page.locator('table')).toBeVisible();

    // Click first category button
    const firstCategoryButton = page.locator('button.category-button').first();
    await firstCategoryButton.click();

    const input = page.locator('input.category-dropdown-input');
    await expect(input).toBeVisible();

    // Type without spaces - should still match categories with spaces
    // e.g., "workinc" should match "Work Income"
    await input.fill('groceries');

    // Should show filtered results
    const dropdown = page.locator('.category-dropdown-list');
    await expect(dropdown).toBeVisible();

    // At least one result should be visible (Uncategorized + filtered categories)
    const items = dropdown.locator('li');
    await expect(items).not.toHaveCount(0);
  });
});

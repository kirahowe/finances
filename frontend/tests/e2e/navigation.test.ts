import { test, expect } from '@playwright/test';

test.describe('Navigation', () => {
  test('home page loads successfully', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveTitle(/Finance Aggregator/);
    await expect(page.getByRole('heading', { name: 'Finance Aggregator' })).toBeVisible();
  });

  test('can view categories section', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: 'Categories' }).click();
    await expect(page).toHaveURL('/?view=categories');
    await expect(page.getByRole('heading', { name: 'Categories' })).toBeVisible();
  });

  test('can view accounts section', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: 'Accounts' }).click();
    await expect(page).toHaveURL('/?view=accounts');
    await expect(page.getByRole('heading', { name: 'Accounts' })).toBeVisible();
  });

  test('can view transactions section', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: 'Transactions' }).click();
    await expect(page).toHaveURL('/?view=transactions');
    await expect(page.getByRole('heading', { name: 'Transactions' })).toBeVisible();
  });

  test('can refresh stats', async ({ page }) => {
    await page.goto('/');
    const refreshButton = page.getByRole('button', { name: /refresh/i });
    await expect(refreshButton).toBeVisible();
    await refreshButton.click();
  });

  test('can navigate directly to view via URL', async ({ page }) => {
    await page.goto('/?view=categories');
    await expect(page.getByRole('heading', { name: 'Categories' })).toBeVisible();
  });
});

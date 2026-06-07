import { test, expect } from '@playwright/test';

test.describe('Navigation', () => {
  test('home page loads the transactions workspace', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveTitle(/Finance Aggregator/);
    await expect(page.getByRole('heading', { name: 'Finance Aggregator' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Transactions' })).toBeVisible();
  });

  test('can navigate to the setup section', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('link', { name: 'Setup' }).click();
    await expect(page).toHaveURL('/setup');
    await expect(page.getByRole('heading', { name: 'Accounts' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Categories' })).toBeVisible();
  });

  test('can return to transactions from setup', async ({ page }) => {
    await page.goto('/setup');
    await page.getByRole('link', { name: 'Transactions' }).click();
    await expect(page).toHaveURL('/');
    await expect(page.getByRole('heading', { name: 'Transactions' })).toBeVisible();
  });

  test('can navigate directly to setup via URL', async ({ page }) => {
    await page.goto('/setup');
    await expect(page.getByRole('heading', { name: 'Accounts' })).toBeVisible();
  });

  test('can open the plaid test page', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('link', { name: 'Plaid' }).click();
    await expect(page).toHaveURL('/plaid-test');
    await expect(page.getByRole('heading', { name: 'Plaid Integration Test' })).toBeVisible();
  });
});

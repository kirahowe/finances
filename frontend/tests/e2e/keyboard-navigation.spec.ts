import { test, expect } from '@playwright/test';
import { resetSeed } from './helpers';

// Real-browser coverage for the spreadsheet keyboard navigation — the parts jsdom
// can't faithfully exercise: actual roving-tabindex focus, real Tab, and that the
// active cell tracks its transaction across a re-sort. Some tests toggle/commit, so
// reset the shared seed before each.
test.beforeEach(async ({ request, page }) => {
  await resetSeed(request);
  await page.goto('/?month=2025-01');
  await expect(page.locator('table')).toBeVisible();
});

// The table's scroll container is the single keyboard entry point (tabindex 0 until
// a cell is active). Focusing it lets the first arrow key land on the first cell.
async function enterGrid(page: import('@playwright/test').Page) {
  await page.locator('.transactions-table-scroll').focus();
  await page.keyboard.press('ArrowDown'); // activate the first editable cell
}

test.describe('spreadsheet keyboard navigation', () => {
  test('arrow keys move a real focus ring across the editable columns', async ({ page }) => {
    await enterGrid(page);

    // The active cell is a real focusable <td> carrying the ring.
    await expect(page.locator('td.grid-cell-active.description-cell')).toBeVisible();
    await expect(page.locator('td.grid-cell-active')).toBeFocused();

    await page.keyboard.press('ArrowRight');
    await expect(page.locator('td.grid-cell-active.category-cell')).toBeVisible();

    await page.keyboard.press('ArrowRight');
    await expect(page.locator('td.grid-cell-active.reviewed-cell')).toBeVisible();
  });

  test('Enter opens the category dropdown — focusing the cell alone does not', async ({ page }) => {
    await enterGrid(page);
    await page.keyboard.press('ArrowRight'); // onto the category cell
    await expect(page.locator('td.grid-cell-active.category-cell')).toBeVisible();

    // The old onFocus hack opened the picker the moment the cell was focused.
    await expect(page.locator('input.category-dropdown-input')).toHaveCount(0);

    await page.keyboard.press('Enter');
    await expect(page.locator('input.category-dropdown-input')).toBeVisible();
  });

  test('a printable key starts a description edit seeded with that character', async ({ page }) => {
    await enterGrid(page); // first editable cell is the description
    await expect(page.locator('td.grid-cell-active.description-cell')).toBeVisible();

    await page.keyboard.press('z');
    const input = page.locator('input.description-input');
    await expect(input).toBeFocused();
    await expect(input).toHaveValue('z');

    await page.keyboard.press('Escape'); // discard, leave the seed unsaved
  });

  test('Tab commits a description edit and moves to the next editable cell', async ({ page }) => {
    await enterGrid(page);
    await page.keyboard.press('Enter'); // edit the description
    const input = page.locator('input.description-input');
    await expect(input).toBeFocused();
    await input.fill('Renamed by keyboard');

    await page.keyboard.press('Tab');

    // Edit committed (input gone) and the ring advanced to the category cell.
    await expect(page.locator('input.description-input')).toHaveCount(0);
    await expect(page.locator('td.grid-cell-active.category-cell')).toBeVisible();
  });

  test('Space toggles the reviewed checkbox on the active cell', async ({ page }) => {
    await enterGrid(page);
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press('ArrowRight'); // onto the reviewed cell
    await expect(page.locator('td.grid-cell-active.reviewed-cell')).toBeVisible();

    const checkbox = page.locator('td.grid-cell-active input.reviewed-checkbox');
    const before = await checkbox.isChecked();
    await page.keyboard.press(' ');
    await expect(checkbox).toBeChecked({ checked: !before });
  });

  test('the active cell follows its transaction across a re-sort', async ({ page }) => {
    await enterGrid(page); // row 0, description cell active
    const description = (await page.locator('td.grid-cell-active').innerText()).trim();

    // Re-sort by amount; the active cell's transaction moves in the DOM but the ring
    // must stay on it (the active cell is keyed by identity, not row index).
    await page.getByRole('columnheader', { name: /Amount/i }).click();

    await expect(page.locator('td.grid-cell-active.description-cell')).toHaveText(description);
  });
});

import { test, expect } from '@playwright/test';
import { resetSeed } from './helpers';

// Resizable + hideable columns on the transactions table. Independent of the
// transfer seed specifics — only needs the table to render some rows.
const MONTH = '/?month=2025-01';

test.beforeEach(async ({ request }) => {
  await resetSeed(request);
});

async function headerWidth(page: import('@playwright/test').Page, name: RegExp | string) {
  const box = await page.getByRole('columnheader', { name }).boundingBox();
  return box!.width;
}

test('resizing a column changes only that column, not its neighbours', async ({ page }) => {
  await page.goto(MONTH);
  await page.waitForSelector('table.table-resizable tbody tr');

  const payeeBefore = await headerWidth(page, 'Payee');
  const descBefore = await headerWidth(page, 'Description');
  const amountBefore = await headerWidth(page, 'Amount');

  const handle = page.getByRole('columnheader', { name: 'Payee' }).locator('.col-resize-handle');
  const box = (await handle.boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 + 90, box.y + box.height / 2, { steps: 8 });
  await page.mouse.up();

  const payeeAfter = await headerWidth(page, 'Payee');
  const descAfter = await headerWidth(page, 'Description');
  const amountAfter = await headerWidth(page, 'Amount');

  // Dragged column grew ~90px; neighbours unchanged (no proportional reflow).
  expect(payeeAfter).toBeGreaterThan(payeeBefore + 60);
  expect(Math.abs(descAfter - descBefore)).toBeLessThan(3);
  expect(Math.abs(amountAfter - amountBefore)).toBeLessThan(3);

  // Width is persisted to the URL.
  await expect.poll(() => new URL(page.url()).searchParams.get('colw')).toContain('payee:');
});

test('resizing does not toggle the column sort', async ({ page }) => {
  await page.goto(MONTH);
  await page.waitForSelector('table.table-resizable tbody tr');

  // Default sort is Date ascending (↑).
  const dateHeader = page.getByRole('columnheader', { name: /Date/ });
  await expect(dateHeader).toContainText('↑');

  const handle = dateHeader.locator('.col-resize-handle');
  const box = (await handle.boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 + 60, box.y + box.height / 2, { steps: 6 });
  await page.mouse.up();

  // Still ascending — the drag's trailing click was not registered as a sort.
  await expect(dateHeader).toContainText('↑');
  await expect(dateHeader).not.toContainText('↓');
  expect(new URL(page.url()).searchParams.get('sort')).not.toContain('desc');
});

test('hiding and showing a column via the Columns picker works', async ({ page }) => {
  await page.goto(MONTH);
  await page.waitForSelector('table.table-resizable tbody tr');

  await expect(page.getByRole('columnheader', { name: 'Institution' })).toBeVisible();

  await page.getByRole('button', { name: 'Columns' }).click();
  const dropdown = page.locator('.filter-dropdown');
  await dropdown.getByText('Institution', { exact: true }).click();

  await expect(page.getByRole('columnheader', { name: 'Institution' })).toHaveCount(0);
  await expect.poll(() => new URL(page.url()).searchParams.get('cols')).toContain('institution');

  // Toggle back on.
  await dropdown.getByText('Institution', { exact: true }).click();
  await expect(page.getByRole('columnheader', { name: 'Institution' })).toBeVisible();
});

test('Reset widths clears a resized column back to default', async ({ page }) => {
  await page.goto(MONTH);
  await page.waitForSelector('table.table-resizable tbody tr');

  const before = await headerWidth(page, 'Payee');

  const handle = page.getByRole('columnheader', { name: 'Payee' }).locator('.col-resize-handle');
  const box = (await handle.boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 + 90, box.y + box.height / 2, { steps: 8 });
  await page.mouse.up();
  expect(await headerWidth(page, 'Payee')).toBeGreaterThan(before + 60);

  await page.getByRole('button', { name: 'Columns' }).click();
  await page.getByRole('button', { name: 'Reset widths' }).click();

  await expect
    .poll(async () => (await page.getByRole('columnheader', { name: 'Payee' }).boundingBox())!.width)
    .toBeLessThan(before + 3);
  expect(new URL(page.url()).searchParams.get('colw')).toBeNull();
});

test('double-clicking the resize handle auto-fits the column to its content', async ({ page }) => {
  await page.goto(MONTH);
  await page.waitForSelector('table.table-resizable tbody tr');

  const handle = page.getByRole('columnheader', { name: 'Payee' }).locator('.col-resize-handle');

  // Make Payee narrow so its content clips.
  const box = (await handle.boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 - 140, box.y + box.height / 2, { steps: 8 });
  await page.mouse.up();
  const narrow = await headerWidth(page, 'Payee');

  // Double-click the handle: it expands to fit the widest cell.
  await handle.dblclick();
  const fitted = await headerWidth(page, 'Payee');
  expect(fitted).toBeGreaterThan(narrow);

  // No payee cell is clipped anymore (content width <= cell width).
  const anyClipped = await page.evaluate(() =>
    (Array.from(document.querySelectorAll('tbody tr td:nth-child(4)')) as HTMLElement[]).some(
      (c) => c.scrollWidth > c.clientWidth + 1
    )
  );
  expect(anyClipped).toBe(false);
});

test('protected columns (date, amount, category) never clip', async ({ page }) => {
  // Sort the biggest amount to the top so the amount column must fit it; check at a
  // wide and a tight viewport (where the flexible columns are forced to clip).
  for (const width of [1680, 1100]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`${MONTH}&sort=amount:asc`);
    await page.waitForSelector('table.table-resizable tbody tr');

    const clips = await page.evaluate(() => {
      const innerClipped = (td: HTMLElement, inner: Element | null) => {
        if (!inner) return false;
        const s = getComputedStyle(td);
        const avail = td.clientWidth - parseFloat(s.paddingLeft) - parseFloat(s.paddingRight);
        return inner.getBoundingClientRect().width > avail + 1;
      };
      const count = (sel: string, pick: (td: HTMLElement) => boolean) =>
        (Array.from(document.querySelectorAll(sel)) as HTMLElement[]).filter(pick).length;
      return {
        date: count('tbody tr td:first-child', (td) => innerClipped(td, td.querySelector('.numeric'))),
        amount: count('tbody tr td.amount-cell', (td) => innerClipped(td, td.querySelector('.numeric'))),
        category: count(
          'tbody tr td.category-cell .category-button',
          (b) => b.scrollWidth > b.clientWidth + 1
        ),
      };
    });
    expect(clips, `viewport ${width}`).toEqual({ date: 0, amount: 0, category: 0 });
  }
});

test('table fills the container width by default (wide viewport)', async ({ page }) => {
  // Wide enough that the default columns are narrower than the container, so the
  // spacer column has slack to absorb (the case the user reported).
  await page.setViewportSize({ width: 1680, height: 900 });
  await page.goto(MONTH);
  await page.waitForSelector('table.table-resizable tbody tr');

  const tableBox = (await page.locator('table.table-resizable').boundingBox())!;
  const scrollBox = (await page.locator('.transactions-table-scroll').boundingBox())!;
  // The trailing spacer column soaks up slack so the table spans the full width.
  expect(Math.abs(tableBox.width - scrollBox.width)).toBeLessThan(2);
});

test('page size persists across reload (no jitter)', async ({ page }) => {
  await page.goto(MONTH);
  await page.waitForSelector('table.table-resizable tbody tr');

  const pagination = page.locator('.pagination');
  await pagination.getByRole('button', { name: '50', exact: true }).click();
  await expect.poll(() => new URL(page.url()).searchParams.get('pageSize')).toBe('50');

  await page.reload();
  await page.waitForSelector('table.table-resizable tbody tr');
  await expect(pagination.getByRole('button', { name: '50', exact: true })).toHaveClass(/button-primary/);
  expect(new URL(page.url()).searchParams.get('pageSize')).toBe('50');
});

test('current page persists across reload (no jitter)', async ({ page }) => {
  await page.goto(MONTH);
  await page.waitForSelector('table.table-resizable tbody tr');

  const pagination = page.locator('.pagination');
  const page2 = pagination.getByRole('button', { name: '2', exact: true });
  test.skip((await page2.count()) === 0, 'this month has only a single page');

  await page2.click();
  await expect.poll(() => new URL(page.url()).searchParams.get('page')).toBe('2');
  const firstRow = await page.locator('tbody tr').first().innerText();

  await page.reload();
  await page.waitForSelector('table.table-resizable tbody tr');
  await expect(pagination.getByRole('button', { name: '2', exact: true })).toHaveClass(/button-primary/);
  // Same slice of rows after reload → no jitter.
  expect(await page.locator('tbody tr').first().innerText()).toBe(firstRow);
});

test('category editor opens (portaled) and is not clipped', async ({ page }) => {
  await page.goto(MONTH);
  await page.waitForSelector('table.table-resizable tbody tr');

  // Open the first row's category editor.
  await page.locator('tbody tr .category-button').first().click();
  const input = page.getByRole('combobox');
  await expect(input).toBeVisible();

  // The list is portaled to <body> (outside the scroll container) and visible.
  const list = page.locator('body > .category-dropdown-list-portal');
  await expect(list).toBeVisible();
  await expect(list.locator('.category-dropdown-item').first()).toBeVisible();
});

import { describe, it, expect } from 'vitest';
import { parseCategoryList, buildBulkRows } from './categoryParse';

describe('parseCategoryList', () => {
  it('parses a flat list, one name per line', () => {
    const result = parseCategoryList('Groceries\nGas\nSalary');
    expect(result).toEqual([
      { name: 'Groceries', parentName: null },
      { name: 'Gas', parentName: null },
      { name: 'Salary', parentName: null },
    ]);
  });

  it('treats indented lines as children of the nearest top-level line', () => {
    const text = 'Food\n  Groceries\n  Dining\nTransport\n  Gas';
    expect(parseCategoryList(text)).toEqual([
      { name: 'Food', parentName: null },
      { name: 'Groceries', parentName: 'Food' },
      { name: 'Dining', parentName: 'Food' },
      { name: 'Transport', parentName: null },
      { name: 'Gas', parentName: 'Transport' },
    ]);
  });

  it('strips markdown bullet markers (-, *, +)', () => {
    const text = '- Food\n  - Groceries\n* Transport\n  + Gas';
    expect(parseCategoryList(text)).toEqual([
      { name: 'Food', parentName: null },
      { name: 'Groceries', parentName: 'Food' },
      { name: 'Transport', parentName: null },
      { name: 'Gas', parentName: 'Transport' },
    ]);
  });

  it('handles tab indentation', () => {
    const text = 'Food\n\tGroceries';
    expect(parseCategoryList(text)).toEqual([
      { name: 'Food', parentName: null },
      { name: 'Groceries', parentName: 'Food' },
    ]);
  });

  it('skips blank lines', () => {
    const text = 'Food\n\n  Groceries\n\n';
    expect(parseCategoryList(text)).toEqual([
      { name: 'Food', parentName: null },
      { name: 'Groceries', parentName: 'Food' },
    ]);
  });

  it('normalizes a uniformly-indented block (whole paste shifted right)', () => {
    const text = '    - Food\n    - Drinks\n        - Coffee';
    expect(parseCategoryList(text)).toEqual([
      { name: 'Food', parentName: null },
      { name: 'Drinks', parentName: null },
      { name: 'Coffee', parentName: 'Drinks' },
    ]);
  });

  it('flattens deeper-than-one nesting onto the top-level parent (single level)', () => {
    const text = 'Food\n  Groceries\n    Produce';
    expect(parseCategoryList(text)).toEqual([
      { name: 'Food', parentName: null },
      { name: 'Groceries', parentName: 'Food' },
      { name: 'Produce', parentName: 'Food' },
    ]);
  });

  it('returns an empty array for empty input', () => {
    expect(parseCategoryList('')).toEqual([]);
    expect(parseCategoryList('   \n  \n')).toEqual([]);
  });

  it('treats a leading indented line with no parent as top-level', () => {
    const text = '  Groceries\nFood';
    expect(parseCategoryList(text)).toEqual([
      { name: 'Groceries', parentName: null },
      { name: 'Food', parentName: null },
    ]);
  });
});

describe('buildBulkRows', () => {
  it('assigns unique tempIds and resolves parent links to parent tempIds', () => {
    const rows = buildBulkRows('Food\n  Groceries\n  Dining\nTransport\n  Gas');
    const food = rows[0];
    const groceries = rows[1];
    const transport = rows[3];
    const gas = rows[4];

    expect(new Set(rows.map((r) => r.tempId)).size).toBe(5);
    expect(food.parentTempId).toBeNull();
    expect(groceries.parentTempId).toBe(food.tempId);
    expect(rows[2].parentTempId).toBe(food.tempId);
    expect(transport.parentTempId).toBeNull();
    expect(gas.parentTempId).toBe(transport.tempId);
  });

  it('defaults every row to the expense type', () => {
    const rows = buildBulkRows('Food\n  Groceries');
    expect(rows.every((r) => r.type === 'expense')).toBe(true);
  });
});

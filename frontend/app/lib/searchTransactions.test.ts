import { describe, it, expect } from 'vitest';
import type { Transaction } from './api';
import { searchTransactions } from './searchTransactions';

const tx = (over: Partial<Transaction> = {}): Transaction =>
  ({
    'db/id': 1,
    'transaction/amount': -1,
    'transaction/payee': 'Starbucks',
    'transaction/posted-date': '2025-01-01',
    ...over,
  }) as Transaction;

describe('searchTransactions', () => {
  const rows = [
    tx({ 'db/id': 1, 'transaction/payee': 'Starbucks' }),
    tx({ 'db/id': 2, 'transaction/payee': 'Shell', 'transaction/effective-description': 'Gas station fill-up' }),
    tx({ 'db/id': 3, 'transaction/payee': 'Amazon', 'transaction/category': { 'db/id': 9, 'category/name': 'Shopping' } }),
  ];

  it('returns all rows for a blank query', () => {
    expect(searchTransactions(rows, '')).toBe(rows);
    expect(searchTransactions(rows, '   ')).toBe(rows);
  });

  it('matches on payee, case-insensitively', () => {
    expect(searchTransactions(rows, 'star').map((t) => t['db/id'])).toEqual([1]);
  });

  it('matches on the effective description', () => {
    expect(searchTransactions(rows, 'gas').map((t) => t['db/id'])).toEqual([2]);
  });

  it('matches on the category name', () => {
    expect(searchTransactions(rows, 'shopping').map((t) => t['db/id'])).toEqual([3]);
  });

  it('matches a split part memo and category', () => {
    const splitTx = tx({
      'db/id': 4,
      'transaction/payee': 'Costco',
      'transaction/splits': [
        { 'db/id': 40, 'split/amount': -10, 'split/memo': 'birthday present' },
        { 'db/id': 41, 'split/amount': -5, 'split/category': { 'db/id': 2, 'category/name': 'Groceries' } },
      ],
    });
    expect(searchTransactions([splitTx], 'birthday')).toHaveLength(1);
    expect(searchTransactions([splitTx], 'groceries')).toHaveLength(1);
  });

  it('returns nothing when no field matches', () => {
    expect(searchTransactions(rows, 'zzz')).toHaveLength(0);
  });
});

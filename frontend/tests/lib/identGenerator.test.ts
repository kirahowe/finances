import { describe, it, expect } from 'vitest';
import { generateCategoryIdent } from '../../app/lib/identGenerator';

describe('generateCategoryIdent', () => {
  it('converts category name to lowercase identifier', () => {
    expect(generateCategoryIdent('Groceries')).toBe('category/groceries');
  });

  it('replaces spaces with hyphens', () => {
    expect(generateCategoryIdent('Dining Out')).toBe('category/dining-out');
  });

  it('handles multiple consecutive spaces', () => {
    expect(generateCategoryIdent('Home  Office')).toBe('category/home-office');
  });

  it('removes special characters', () => {
    expect(generateCategoryIdent('Gas & Transportation')).toBe('category/gas-transportation');
  });

  it('handles mixed case and spaces', () => {
    expect(generateCategoryIdent('Credit Card Payment')).toBe('category/credit-card-payment');
  });

  it('trims leading and trailing spaces', () => {
    expect(generateCategoryIdent('  Utilities  ')).toBe('category/utilities');
  });

  it('handles empty string', () => {
    expect(generateCategoryIdent('')).toBe('category/');
  });

  it('removes non-alphanumeric characters except spaces', () => {
    expect(generateCategoryIdent('Health & Wellness!')).toBe('category/health-wellness');
  });
});

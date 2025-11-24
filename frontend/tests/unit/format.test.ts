import { describe, it, expect } from 'vitest';
import { formatAmount, formatDate } from '../../app/lib/format';

describe('formatAmount', () => {
  it('formats positive amounts with currency symbol', () => {
    const result = formatAmount(1234.56);
    expect(result).toMatch(/1,234\.56/);
    expect(result).toContain('$');
  });

  it('formats negative amounts', () => {
    const result = formatAmount(-1234.56);
    expect(result).toMatch(/-.*1,234\.56/);
  });

  it('formats zero', () => {
    const result = formatAmount(0);
    expect(result).toMatch(/0\.00/);
  });

  it('handles decimal places', () => {
    const result = formatAmount(100);
    expect(result).toMatch(/100\.00/);
  });

  it('handles large numbers', () => {
    const result = formatAmount(1000000);
    expect(result).toMatch(/1,000,000\.00/);
  });
});

describe('formatDate', () => {
  it('formats ISO date string with 3-char month abbreviation', () => {
    const result = formatDate('2025-10-12');
    expect(result).toBe('Oct 12, 2025');
  });

  it('formats date with time', () => {
    const result = formatDate('2024-01-15T10:30:00Z');
    expect(result).toBe('Jan 15, 2024');
  });

  it('formats different months correctly', () => {
    expect(formatDate('2025-01-05')).toBe('Jan 5, 2025');
    expect(formatDate('2025-02-28')).toBe('Feb 28, 2025');
    expect(formatDate('2025-03-15')).toBe('Mar 15, 2025');
    expect(formatDate('2025-04-01')).toBe('Apr 1, 2025');
    expect(formatDate('2025-05-20')).toBe('May 20, 2025');
    expect(formatDate('2025-06-30')).toBe('Jun 30, 2025');
    expect(formatDate('2025-07-04')).toBe('Jul 4, 2025');
    expect(formatDate('2025-08-15')).toBe('Aug 15, 2025');
    expect(formatDate('2025-09-01')).toBe('Sep 1, 2025');
    expect(formatDate('2025-10-31')).toBe('Oct 31, 2025');
    expect(formatDate('2025-11-11')).toBe('Nov 11, 2025');
    expect(formatDate('2025-12-25')).toBe('Dec 25, 2025');
  });
});

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
  it('formats ISO date string', () => {
    const result = formatDate('2024-01-15');
    expect(result).toBeTruthy();
    expect(result.length).toBeGreaterThan(0);
  });

  it('formats date with time', () => {
    const result = formatDate('2024-01-15T10:30:00Z');
    expect(result).toBeTruthy();
    expect(result.length).toBeGreaterThan(0);
  });
});

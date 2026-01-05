import { describe, it, expect } from 'vitest';
import {
  parseMonthParam,
  serializeMonth,
  getCurrentMonth,
  nextMonth,
  prevMonth,
  formatMonthDisplay,
  isValidMonthParam,
  type MonthState,
} from './monthState';

describe('monthState', () => {
  describe('parseMonthParam', () => {
    it('parses valid YYYY-MM format', () => {
      const result = parseMonthParam('2025-01');
      expect(result).toEqual({ year: 2025, month: 1 });
    });

    it('parses month at end of year', () => {
      const result = parseMonthParam('2024-12');
      expect(result).toEqual({ year: 2024, month: 12 });
    });

    it('returns current month for null input', () => {
      const result = parseMonthParam(null);
      const now = new Date();
      expect(result.year).toBe(now.getFullYear());
      expect(result.month).toBe(now.getMonth() + 1);
    });

    it('returns current month for undefined input', () => {
      const result = parseMonthParam(undefined);
      const now = new Date();
      expect(result.year).toBe(now.getFullYear());
      expect(result.month).toBe(now.getMonth() + 1);
    });

    it('returns current month for empty string', () => {
      const result = parseMonthParam('');
      const now = new Date();
      expect(result.year).toBe(now.getFullYear());
      expect(result.month).toBe(now.getMonth() + 1);
    });

    it('returns current month for invalid format', () => {
      const result = parseMonthParam('invalid');
      const now = new Date();
      expect(result.year).toBe(now.getFullYear());
      expect(result.month).toBe(now.getMonth() + 1);
    });

    it('returns current month for out of range month', () => {
      const result = parseMonthParam('2025-13');
      const now = new Date();
      expect(result.year).toBe(now.getFullYear());
      expect(result.month).toBe(now.getMonth() + 1);
    });

    it('returns current month for month 00', () => {
      const result = parseMonthParam('2025-00');
      const now = new Date();
      expect(result.year).toBe(now.getFullYear());
      expect(result.month).toBe(now.getMonth() + 1);
    });
  });

  describe('serializeMonth', () => {
    it('serializes month state to YYYY-MM format', () => {
      expect(serializeMonth({ year: 2025, month: 1 })).toBe('2025-01');
    });

    it('pads single digit months with leading zero', () => {
      expect(serializeMonth({ year: 2025, month: 3 })).toBe('2025-03');
    });

    it('handles double digit months', () => {
      expect(serializeMonth({ year: 2025, month: 12 })).toBe('2025-12');
    });

    it('handles month 10 correctly', () => {
      expect(serializeMonth({ year: 2025, month: 10 })).toBe('2025-10');
    });
  });

  describe('getCurrentMonth', () => {
    it('returns current year and month', () => {
      const result = getCurrentMonth();
      const now = new Date();
      expect(result.year).toBe(now.getFullYear());
      expect(result.month).toBe(now.getMonth() + 1);
    });
  });

  describe('nextMonth', () => {
    it('increments month within year', () => {
      expect(nextMonth({ year: 2025, month: 1 })).toEqual({ year: 2025, month: 2 });
    });

    it('wraps to next year from December', () => {
      expect(nextMonth({ year: 2024, month: 12 })).toEqual({ year: 2025, month: 1 });
    });

    it('handles mid-year transition', () => {
      expect(nextMonth({ year: 2025, month: 6 })).toEqual({ year: 2025, month: 7 });
    });
  });

  describe('prevMonth', () => {
    it('decrements month within year', () => {
      expect(prevMonth({ year: 2025, month: 6 })).toEqual({ year: 2025, month: 5 });
    });

    it('wraps to previous year from January', () => {
      expect(prevMonth({ year: 2025, month: 1 })).toEqual({ year: 2024, month: 12 });
    });

    it('handles February', () => {
      expect(prevMonth({ year: 2025, month: 2 })).toEqual({ year: 2025, month: 1 });
    });
  });

  describe('formatMonthDisplay', () => {
    it('formats January correctly', () => {
      expect(formatMonthDisplay({ year: 2025, month: 1 })).toBe('January 2025');
    });

    it('formats December correctly', () => {
      expect(formatMonthDisplay({ year: 2024, month: 12 })).toBe('December 2024');
    });

    it('formats all months correctly', () => {
      const months = [
        'January', 'February', 'March', 'April', 'May', 'June',
        'July', 'August', 'September', 'October', 'November', 'December'
      ];
      months.forEach((name, index) => {
        expect(formatMonthDisplay({ year: 2025, month: index + 1 })).toBe(`${name} 2025`);
      });
    });
  });

  describe('isValidMonthParam', () => {
    it('returns true for valid YYYY-MM format', () => {
      expect(isValidMonthParam('2025-01')).toBe(true);
      expect(isValidMonthParam('2025-12')).toBe(true);
    });

    it('returns false for null', () => {
      expect(isValidMonthParam(null)).toBe(false);
    });

    it('returns false for undefined', () => {
      expect(isValidMonthParam(undefined)).toBe(false);
    });

    it('returns false for empty string', () => {
      expect(isValidMonthParam('')).toBe(false);
    });

    it('returns false for invalid format', () => {
      expect(isValidMonthParam('2025-1')).toBe(false);
      expect(isValidMonthParam('invalid')).toBe(false);
      expect(isValidMonthParam('2025/01')).toBe(false);
    });

    it('returns false for out of range month', () => {
      expect(isValidMonthParam('2025-00')).toBe(false);
      expect(isValidMonthParam('2025-13')).toBe(false);
    });
  });

  describe('roundtrip: parse then serialize', () => {
    it('roundtrips valid month params', () => {
      const original = '2025-06';
      const parsed = parseMonthParam(original);
      const serialized = serializeMonth(parsed);
      expect(serialized).toBe(original);
    });

    it('roundtrips edge cases', () => {
      expect(serializeMonth(parseMonthParam('2025-01'))).toBe('2025-01');
      expect(serializeMonth(parseMonthParam('2025-12'))).toBe('2025-12');
    });
  });
});

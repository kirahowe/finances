import { describe, it, expect } from 'vitest';
import type { SortingState } from '@tanstack/react-table';
import {
  serializeSortingState,
  parseSortingState,
} from '../../app/lib/sortingState';

describe('sortingState', () => {
  describe('serializeSortingState', () => {
    it('serializes empty sorting state', () => {
      const sorting: SortingState = [];
      const result = serializeSortingState(sorting);
      expect(result).toBe('');
    });

    it('serializes single column ascending sort', () => {
      const sorting: SortingState = [{ id: 'date', desc: false }];
      const result = serializeSortingState(sorting);
      expect(result).toBe('date:asc');
    });

    it('serializes single column descending sort', () => {
      const sorting: SortingState = [{ id: 'amount', desc: true }];
      const result = serializeSortingState(sorting);
      expect(result).toBe('amount:desc');
    });

    it('serializes multiple column sort', () => {
      const sorting: SortingState = [
        { id: 'date', desc: true },
        { id: 'amount', desc: false },
      ];
      const result = serializeSortingState(sorting);
      expect(result).toBe('date:desc,amount:asc');
    });
  });

  describe('parseSortingState', () => {
    it('parses empty string to empty sorting state', () => {
      const result = parseSortingState('');
      expect(result).toEqual([]);
    });

    it('parses null to empty sorting state', () => {
      const result = parseSortingState(null);
      expect(result).toEqual([]);
    });

    it('parses undefined to empty sorting state', () => {
      const result = parseSortingState(undefined);
      expect(result).toEqual([]);
    });

    it('parses single column ascending sort', () => {
      const result = parseSortingState('date:asc');
      expect(result).toEqual([{ id: 'date', desc: false }]);
    });

    it('parses single column descending sort', () => {
      const result = parseSortingState('amount:desc');
      expect(result).toEqual([{ id: 'amount', desc: true }]);
    });

    it('parses multiple column sort', () => {
      const result = parseSortingState('date:desc,amount:asc');
      expect(result).toEqual([
        { id: 'date', desc: true },
        { id: 'amount', desc: false },
      ]);
    });

    it('handles malformed input gracefully', () => {
      const result = parseSortingState('invalid');
      expect(result).toEqual([]);
    });

    it('handles partially malformed input', () => {
      const result = parseSortingState('date:asc,invalid,amount:desc');
      expect(result).toEqual([
        { id: 'date', desc: false },
        { id: 'amount', desc: true },
      ]);
    });

    it('handles invalid direction', () => {
      const result = parseSortingState('date:invalid');
      expect(result).toEqual([]);
    });
  });

  describe('round-trip serialization', () => {
    it('preserves empty state', () => {
      const original: SortingState = [];
      const serialized = serializeSortingState(original);
      const parsed = parseSortingState(serialized);
      expect(parsed).toEqual(original);
    });

    it('preserves single sort', () => {
      const original: SortingState = [{ id: 'date', desc: true }];
      const serialized = serializeSortingState(original);
      const parsed = parseSortingState(serialized);
      expect(parsed).toEqual(original);
    });

    it('preserves multiple sorts', () => {
      const original: SortingState = [
        { id: 'date', desc: true },
        { id: 'payee', desc: false },
        { id: 'amount', desc: true },
      ];
      const serialized = serializeSortingState(original);
      const parsed = parseSortingState(serialized);
      expect(parsed).toEqual(original);
    });
  });
});

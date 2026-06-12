import { describe, it, expect } from 'vitest';
import type { VisibilityState, ColumnSizingState } from '@tanstack/react-table';
import {
  serializeColumnVisibility,
  parseColumnVisibility,
  serializeColumnSizing,
  parseColumnSizing,
} from '../../app/lib/columnState';
import { MAX_COLUMN_WIDTH } from '../../app/lib/transactionColumns';

describe('columnState', () => {
  describe('column visibility', () => {
    it('serializes all-visible to an empty string', () => {
      expect(serializeColumnVisibility({})).toBe('');
    });

    it('serializes only hidden columns (visible/absent are dropped)', () => {
      const visibility: VisibilityState = { institution: false, payee: true, account: false };
      expect(serializeColumnVisibility(visibility)).toBe('institution,account');
    });

    it('parses empty / null / undefined to no hidden columns', () => {
      expect(parseColumnVisibility('')).toEqual({});
      expect(parseColumnVisibility(null)).toEqual({});
      expect(parseColumnVisibility(undefined)).toEqual({});
    });

    it('parses a comma list to a hidden map and trims whitespace', () => {
      expect(parseColumnVisibility(' institution , account ')).toEqual({
        institution: false,
        account: false,
      });
    });

    it('drops ids not in the allow-list (e.g. a hand-edited structural column)', () => {
      const allowed = ['date', 'account', 'institution', 'payee'];
      expect(parseColumnVisibility('institution,actions,bogus', allowed)).toEqual({
        institution: false,
      });
    });

    it('keeps every id when no allow-list is given', () => {
      expect(parseColumnVisibility('institution,actions')).toEqual({
        institution: false,
        actions: false,
      });
    });

    it('round-trips hidden columns', () => {
      const original: VisibilityState = { institution: false, account: false };
      const allowed = ['account', 'institution', 'payee'];
      expect(parseColumnVisibility(serializeColumnVisibility(original), allowed)).toEqual(original);
    });
  });

  describe('column sizing', () => {
    it('serializes untouched sizing to an empty string', () => {
      expect(serializeColumnSizing({})).toBe('');
    });

    it('serializes id:width pairs and rounds to whole pixels', () => {
      const sizing: ColumnSizingState = { payee: 220.6, amount: 120 };
      expect(serializeColumnSizing(sizing)).toBe('payee:221,amount:120');
    });

    it('parses id:width pairs', () => {
      expect(parseColumnSizing('payee:221,amount:120')).toEqual({ payee: 221, amount: 120 });
    });

    it('parses empty / null / undefined to no widths', () => {
      expect(parseColumnSizing('')).toEqual({});
      expect(parseColumnSizing(null)).toEqual({});
      expect(parseColumnSizing(undefined)).toEqual({});
    });

    it('ignores malformed, non-numeric, or non-positive widths', () => {
      expect(parseColumnSizing('payee:abc,amount:,:90,description:-10,institution:0')).toEqual({});
    });

    it('clamps an absurd width down to MAX_COLUMN_WIDTH', () => {
      expect(parseColumnSizing('payee:999999')).toEqual({ payee: MAX_COLUMN_WIDTH });
    });

    it('round-trips realistic widths', () => {
      const original: ColumnSizingState = { payee: 240, description: 320 };
      expect(parseColumnSizing(serializeColumnSizing(original))).toEqual(original);
    });
  });
});

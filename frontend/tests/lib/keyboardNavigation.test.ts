import { describe, it, expect } from 'vitest';
import { handleKeyboardNavigation } from '../../app/lib/keyboardNavigation';

describe('keyboardNavigation', () => {
  describe('handleKeyboardNavigation', () => {
    it('returns next action for ArrowDown key', () => {
      const result = handleKeyboardNavigation('ArrowDown', 5, 2);
      expect(result).toEqual({ action: 'next', highlightedIndex: 3 });
    });

    it('returns previous action for ArrowUp key', () => {
      const result = handleKeyboardNavigation('ArrowUp', 5, 2);
      expect(result).toEqual({ action: 'previous', highlightedIndex: 1 });
    });

    it('returns select action for Enter key', () => {
      const result = handleKeyboardNavigation('Enter', 5, 2);
      expect(result).toEqual({ action: 'select', highlightedIndex: 2 });
    });

    it('returns close action for Escape key', () => {
      const result = handleKeyboardNavigation('Escape', 5, 2);
      expect(result).toEqual({ action: 'close', highlightedIndex: 2 });
    });

    it('wraps to beginning when ArrowDown at end', () => {
      const result = handleKeyboardNavigation('ArrowDown', 5, 4);
      expect(result).toEqual({ action: 'next', highlightedIndex: 0 });
    });

    it('wraps to end when ArrowUp at beginning', () => {
      const result = handleKeyboardNavigation('ArrowUp', 5, 0);
      expect(result).toEqual({ action: 'previous', highlightedIndex: 4 });
    });

    it('returns none action for unhandled keys', () => {
      const result = handleKeyboardNavigation('a', 5, 2);
      expect(result).toEqual({ action: 'none', highlightedIndex: 2 });
    });

    it('handles empty list for ArrowDown', () => {
      const result = handleKeyboardNavigation('ArrowDown', 0, -1);
      expect(result).toEqual({ action: 'next', highlightedIndex: -1 });
    });

    it('handles empty list for ArrowUp', () => {
      const result = handleKeyboardNavigation('ArrowUp', 0, -1);
      expect(result).toEqual({ action: 'previous', highlightedIndex: -1 });
    });
  });
});

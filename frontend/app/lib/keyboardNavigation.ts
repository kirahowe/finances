import { getNextIndex, getPreviousIndex } from './categoryFiltering';

export type KeyboardAction = 'next' | 'previous' | 'select' | 'close' | 'none';

export interface KeyboardNavigationResult {
  action: KeyboardAction;
  highlightedIndex: number;
}

/**
 * Handles keyboard navigation in the dropdown
 * Returns the action to take and the new highlighted index
 */
export function handleKeyboardNavigation(
  key: string,
  listLength: number,
  currentIndex: number
): KeyboardNavigationResult {
  switch (key) {
    case 'ArrowDown':
      return {
        action: 'next',
        highlightedIndex: getNextIndex(currentIndex, listLength),
      };

    case 'ArrowUp':
      return {
        action: 'previous',
        highlightedIndex: getPreviousIndex(currentIndex, listLength),
      };

    case 'Enter':
      return {
        action: 'select',
        highlightedIndex: currentIndex,
      };

    case 'Escape':
      return {
        action: 'close',
        highlightedIndex: currentIndex,
      };

    default:
      return {
        action: 'none',
        highlightedIndex: currentIndex,
      };
  }
}

/**
 * Pure functions for managing month state in URL parameters
 * Month is represented as { year: number, month: number } where month is 1-12
 */

export interface MonthState {
  year: number;
  month: number;
}

const MONTH_NAMES = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December'
];

const MONTH_PATTERN = /^(\d{4})-(\d{2})$/;

/**
 * Get the current month as MonthState
 */
export function getCurrentMonth(): MonthState {
  const now = new Date();
  return {
    year: now.getFullYear(),
    month: now.getMonth() + 1,
  };
}

/**
 * Check if a month param string is valid YYYY-MM format with valid month
 */
export function isValidMonthParam(param: string | null | undefined): boolean {
  if (!param || param.trim() === '') {
    return false;
  }

  const match = param.match(MONTH_PATTERN);
  if (!match) {
    return false;
  }

  const month = parseInt(match[2], 10);
  return month >= 1 && month <= 12;
}

/**
 * Parse a YYYY-MM string into MonthState
 * Returns current month for invalid or empty input
 */
export function parseMonthParam(param: string | null | undefined): MonthState {
  if (!param || param.trim() === '') {
    return getCurrentMonth();
  }

  const match = param.match(MONTH_PATTERN);
  if (!match) {
    return getCurrentMonth();
  }

  const year = parseInt(match[1], 10);
  const month = parseInt(match[2], 10);

  if (month < 1 || month > 12) {
    return getCurrentMonth();
  }

  return { year, month };
}

/**
 * Serialize MonthState to YYYY-MM format for URL
 */
export function serializeMonth(state: MonthState): string {
  const monthStr = state.month.toString().padStart(2, '0');
  return `${state.year}-${monthStr}`;
}

/**
 * Get the next month
 */
export function nextMonth(state: MonthState): MonthState {
  if (state.month === 12) {
    return { year: state.year + 1, month: 1 };
  }
  return { year: state.year, month: state.month + 1 };
}

/**
 * Get the previous month
 */
export function prevMonth(state: MonthState): MonthState {
  if (state.month === 1) {
    return { year: state.year - 1, month: 12 };
  }
  return { year: state.year, month: state.month - 1 };
}

/**
 * Format month for display (e.g., "January 2025")
 */
export function formatMonthDisplay(state: MonthState): string {
  return `${MONTH_NAMES[state.month - 1]} ${state.year}`;
}

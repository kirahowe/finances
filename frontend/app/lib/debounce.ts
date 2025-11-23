/**
 * Debounce utility - delays function execution until after a wait period.
 * If called again during the wait period, the timer resets.
 *
 * Pure utility following Unix philosophy:
 * - Does one thing well
 * - Works with any function
 * - No dependencies on application logic
 *
 * @param fn - The function to debounce
 * @param wait - The wait time in milliseconds
 * @returns Debounced function with cancel and flush methods
 */
export function debounce<T extends unknown[]>(
  fn: (...args: T) => void,
  wait: number
): {
  (...args: T): void;
  cancel: () => void;
  flush: () => void;
} {
  let timeoutId: ReturnType<typeof setTimeout> | null = null;
  let lastArgs: T | null = null;
  let lastThis: unknown = null;

  function debounced(this: unknown, ...args: T): void {
    lastArgs = args;
    lastThis = this;

    if (timeoutId !== null) {
      clearTimeout(timeoutId);
    }

    timeoutId = setTimeout(() => {
      if (lastArgs !== null) {
        fn.apply(lastThis, lastArgs);
      }
      timeoutId = null;
      lastArgs = null;
      lastThis = null;
    }, wait);
  }

  debounced.cancel = function () {
    if (timeoutId !== null) {
      clearTimeout(timeoutId);
      timeoutId = null;
      lastArgs = null;
      lastThis = null;
    }
  };

  debounced.flush = function () {
    if (timeoutId !== null && lastArgs !== null) {
      clearTimeout(timeoutId);
      fn.apply(lastThis, lastArgs);
      timeoutId = null;
      lastArgs = null;
      lastThis = null;
    }
  };

  return debounced;
}

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { debounce } from '../../app/lib/debounce';

describe('debounce', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('delays function execution until after wait period', () => {
    const fn = vi.fn();
    const debounced = debounce(fn, 100);

    debounced();
    expect(fn).not.toHaveBeenCalled();

    vi.advanceTimersByTime(99);
    expect(fn).not.toHaveBeenCalled();

    vi.advanceTimersByTime(1);
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('resets timer on subsequent calls', () => {
    const fn = vi.fn();
    const debounced = debounce(fn, 100);

    debounced();
    vi.advanceTimersByTime(50);
    debounced(); // Reset timer
    vi.advanceTimersByTime(50);
    expect(fn).not.toHaveBeenCalled();

    vi.advanceTimersByTime(50);
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('only executes once for multiple rapid calls', () => {
    const fn = vi.fn();
    const debounced = debounce(fn, 100);

    debounced();
    debounced();
    debounced();
    debounced();

    vi.advanceTimersByTime(100);
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('passes arguments to the debounced function', () => {
    const fn = vi.fn();
    const debounced = debounce(fn, 100);

    debounced('arg1', 'arg2', 42);
    vi.advanceTimersByTime(100);

    expect(fn).toHaveBeenCalledWith('arg1', 'arg2', 42);
  });

  it('uses latest arguments when called multiple times', () => {
    const fn = vi.fn();
    const debounced = debounce(fn, 100);

    debounced('first');
    debounced('second');
    debounced('third');

    vi.advanceTimersByTime(100);

    expect(fn).toHaveBeenCalledTimes(1);
    expect(fn).toHaveBeenCalledWith('third');
  });

  it('handles zero wait time', () => {
    const fn = vi.fn();
    const debounced = debounce(fn, 0);

    debounced();
    expect(fn).not.toHaveBeenCalled();

    vi.advanceTimersByTime(0);
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('can be called multiple times after settling', () => {
    const fn = vi.fn();
    const debounced = debounce(fn, 100);

    debounced();
    vi.advanceTimersByTime(100);
    expect(fn).toHaveBeenCalledTimes(1);

    debounced();
    vi.advanceTimersByTime(100);
    expect(fn).toHaveBeenCalledTimes(2);

    debounced();
    vi.advanceTimersByTime(100);
    expect(fn).toHaveBeenCalledTimes(3);
  });

  it('handles async functions', async () => {
    const fn = vi.fn(async () => 'result');
    const debounced = debounce(fn, 100);

    debounced();
    vi.advanceTimersByTime(100);

    await vi.runAllTimersAsync();
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('maintains correct this context', () => {
    const obj = {
      value: 42,
      fn: vi.fn(function (this: { value: number }) {
        return this.value;
      }),
    };

    const debounced = debounce(obj.fn, 100);
    debounced.call(obj);

    vi.advanceTimersByTime(100);

    expect(obj.fn).toHaveBeenCalledTimes(1);
  });

  it('handles very long wait times', () => {
    const fn = vi.fn();
    const debounced = debounce(fn, 60000); // 1 minute

    debounced();
    vi.advanceTimersByTime(59999);
    expect(fn).not.toHaveBeenCalled();

    vi.advanceTimersByTime(1);
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('can cancel pending execution via cancel method', () => {
    const fn = vi.fn();
    const debounced = debounce(fn, 100);

    debounced();
    debounced.cancel();

    vi.advanceTimersByTime(100);
    expect(fn).not.toHaveBeenCalled();
  });

  it('can flush pending execution immediately via flush method', () => {
    const fn = vi.fn();
    const debounced = debounce(fn, 100);

    debounced('test');
    expect(fn).not.toHaveBeenCalled();

    debounced.flush();
    expect(fn).toHaveBeenCalledWith('test');
    expect(fn).toHaveBeenCalledTimes(1);

    // Should not execute again after timer
    vi.advanceTimersByTime(100);
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('flush does nothing when no pending call', () => {
    const fn = vi.fn();
    const debounced = debounce(fn, 100);

    debounced.flush();
    expect(fn).not.toHaveBeenCalled();
  });

  it('handles multiple debounced functions independently', () => {
    const fn1 = vi.fn();
    const fn2 = vi.fn();
    const debounced1 = debounce(fn1, 100);
    const debounced2 = debounce(fn2, 200);

    debounced1();
    debounced2();

    vi.advanceTimersByTime(100);
    expect(fn1).toHaveBeenCalledTimes(1);
    expect(fn2).not.toHaveBeenCalled();

    vi.advanceTimersByTime(100);
    expect(fn2).toHaveBeenCalledTimes(1);
  });

  it('handles functions that throw errors', () => {
    const fn = vi.fn(() => {
      throw new Error('Test error');
    });
    const debounced = debounce(fn, 100);

    debounced();

    expect(() => {
      vi.advanceTimersByTime(100);
    }).toThrow('Test error');
  });
});

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HeaderFilterControl } from '../../app/components/HeaderFilterControl';
import type { FilterOption } from '../../app/lib/filterOptions';
import type { FilterValue } from '../../app/lib/filterState';

describe('HeaderFilterControl', () => {
  const options: FilterOption[] = [
    { value: 1, label: 'Chase', count: 3 },
    { value: 2, label: 'Amex', count: 1 },
  ];

  const setup = (selectedValues: FilterValue[] = []) => {
    const onToggle = vi.fn();
    const onClear = vi.fn();
    render(
      <HeaderFilterControl
        label="Account"
        options={options}
        selectedValues={selectedValues}
        onToggle={onToggle}
        onClear={onClear}
      />
    );
    return { onToggle, onClear };
  };

  const funnel = () => screen.getByRole('button', { name: /filter account/i });
  // The popover only renders the shared FilterDropdown, whose search input is a reliable
  // "is it open?" marker.
  const isOpen = () => screen.queryByPlaceholderText('Search account...') !== null;

  it('opens the popover on the first click', async () => {
    const user = userEvent.setup();
    setup();
    expect(isOpen()).toBe(false);
    await user.click(funnel());
    expect(isOpen()).toBe(true);
  });

  // Regression: the funnel is its own toggle, but FilterDropdown closes on an outside
  // mousedown. The button's mousedown must not count as "outside" (ignoreRef), or the
  // popover would close then immediately re-open and never dismiss from its own button.
  it('closes the popover when its own button is clicked again', async () => {
    const user = userEvent.setup();
    setup();
    await user.click(funnel());
    expect(isOpen()).toBe(true);
    await user.click(funnel());
    expect(isOpen()).toBe(false);
  });

  it('still closes the popover on an outside click', async () => {
    const user = userEvent.setup();
    setup();
    await user.click(funnel());
    expect(isOpen()).toBe(true);
    await user.click(document.body);
    expect(isOpen()).toBe(false);
  });
});

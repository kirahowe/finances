import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MonthNavigator } from '../../app/components/MonthNavigator';
import type { MonthState } from '../../app/lib/monthState';

describe('MonthNavigator', () => {
  const january2025: MonthState = { year: 2025, month: 1 };
  const december2024: MonthState = { year: 2024, month: 12 };
  const june2025: MonthState = { year: 2025, month: 6 };

  describe('rendering', () => {
    it('displays the current month formatted correctly', () => {
      render(
        <MonthNavigator
          currentMonth={january2025}
          onMonthChange={() => {}}
        />
      );

      expect(screen.getByText('January 2025')).toBeInTheDocument();
    });

    it('displays December correctly', () => {
      render(
        <MonthNavigator
          currentMonth={december2024}
          onMonthChange={() => {}}
        />
      );

      expect(screen.getByText('December 2024')).toBeInTheDocument();
    });
  });

  describe('navigation', () => {
    it('calls onMonthChange with previous month when prev button clicked', () => {
      const onMonthChange = vi.fn();
      render(
        <MonthNavigator
          currentMonth={june2025}
          onMonthChange={onMonthChange}
        />
      );

      const prevButton = screen.getByTitle('Previous month');
      fireEvent.click(prevButton);

      expect(onMonthChange).toHaveBeenCalledWith({ year: 2025, month: 5 });
    });

    it('calls onMonthChange with next month when next button clicked', () => {
      const onMonthChange = vi.fn();
      render(
        <MonthNavigator
          currentMonth={june2025}
          onMonthChange={onMonthChange}
        />
      );

      const nextButton = screen.getByTitle('Next month');
      fireEvent.click(nextButton);

      expect(onMonthChange).toHaveBeenCalledWith({ year: 2025, month: 7 });
    });

    it('wraps to previous year from January', () => {
      const onMonthChange = vi.fn();
      render(
        <MonthNavigator
          currentMonth={january2025}
          onMonthChange={onMonthChange}
        />
      );

      const prevButton = screen.getByTitle('Previous month');
      fireEvent.click(prevButton);

      expect(onMonthChange).toHaveBeenCalledWith({ year: 2024, month: 12 });
    });

    it('wraps to next year from December', () => {
      const onMonthChange = vi.fn();
      render(
        <MonthNavigator
          currentMonth={december2024}
          onMonthChange={onMonthChange}
        />
      );

      const nextButton = screen.getByTitle('Next month');
      fireEvent.click(nextButton);

      expect(onMonthChange).toHaveBeenCalledWith({ year: 2025, month: 1 });
    });
  });
});

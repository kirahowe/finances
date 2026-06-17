import {
  formatMonthDisplay,
  nextMonth,
  prevMonth,
  type MonthState,
} from '../lib/monthState';

interface MonthNavigatorProps {
  currentMonth: MonthState;
  onMonthChange: (month: MonthState) => void;
}

export function MonthNavigator({
  currentMonth,
  onMonthChange,
}: MonthNavigatorProps) {
  const handlePrev = () => {
    onMonthChange(prevMonth(currentMonth));
  };

  const handleNext = () => {
    onMonthChange(nextMonth(currentMonth));
  };

  return (
    <div className="month-navigator">
      <div className="month-navigator-controls">
        <button
          className="button button-secondary month-nav-button"
          onClick={handlePrev}
          title="Previous month"
        >
          &lsaquo;
        </button>
        <span className="month-navigator-display">
          {formatMonthDisplay(currentMonth)}
        </span>
        <button
          className="button button-secondary month-nav-button"
          onClick={handleNext}
          title="Next month"
        >
          &rsaquo;
        </button>
      </div>
    </div>
  );
}

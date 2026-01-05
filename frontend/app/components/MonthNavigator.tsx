import {
  formatMonthDisplay,
  nextMonth,
  prevMonth,
  type MonthState,
} from '../lib/monthState';

interface MonthNavigatorProps {
  currentMonth: MonthState;
  onMonthChange: (month: MonthState) => void;
  onSync: () => void;
  isSyncing: boolean;
}

export function MonthNavigator({
  currentMonth,
  onMonthChange,
  onSync,
  isSyncing,
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
      <button
        className="button button-secondary month-sync-button"
        onClick={onSync}
        disabled={isSyncing}
        title={isSyncing ? 'Syncing...' : 'Sync transactions for this month'}
      >
        {isSyncing ? 'Syncing...' : 'Sync Month'}
      </button>
    </div>
  );
}

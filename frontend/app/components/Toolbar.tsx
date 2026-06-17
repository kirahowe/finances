import type { ReactNode } from 'react';

interface ToolbarProps {
  // The primary control cluster, laid out left-to-right (search, review-scope toggle,
  // count chips). Grows to fill the row.
  leadingControls?: ReactNode;
  // Secondary actions, aligned to the right (column picker, find transfers).
  trailingControls?: ReactNode;
}

// The transactions toolbar: a flex row of view controls above the table. The attribute
// filters that used to live here now sit in the column headers (see HeaderFilterControl)
// and the applied-filter summary lives in ActiveFilterChips, so this is purely a layout
// shell for the search box, the work-queue toggles, and the right-aligned actions.
export function Toolbar({ leadingControls, trailingControls }: ToolbarProps) {
  return (
    <div className="toolbar">
      <div className="toolbar-controls">{leadingControls}</div>
      {trailingControls && <div className="toolbar-actions">{trailingControls}</div>}
    </div>
  );
}

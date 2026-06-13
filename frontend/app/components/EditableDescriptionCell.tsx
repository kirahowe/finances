import { useEffect, useRef, useState } from 'react';

interface EditableDescriptionCellProps {
  initialValue: string;
  // Commit and advance the editor to the next row (Enter).
  onSaveAndNext: (text: string) => void;
  // Commit and close (blur / click away).
  onSave: (text: string) => void;
  // Discard and close (Escape).
  onCancel: () => void;
}

// The in-place text editor for a transaction's description, swapped into the cell while
// editing (the spreadsheet-style flow mirrors the category dropdown). Enter saves and
// advances to the next row, Escape cancels, blur saves. A blank value clears the
// override upstream, reverting to the imported description.
export function EditableDescriptionCell({
  initialValue,
  onSaveAndNext,
  onSave,
  onCancel,
}: EditableDescriptionCellProps) {
  const [value, setValue] = useState(initialValue);
  const inputRef = useRef<HTMLInputElement>(null);
  // Enter and Escape both unmount this input, which fires a trailing blur; latch so the
  // blur handler doesn't double-fire a second (stale) commit after we've already acted.
  const committedRef = useRef(false);

  useEffect(() => {
    inputRef.current?.focus();
    inputRef.current?.select();
  }, []);

  const commit = (action: () => void) => {
    if (committedRef.current) return;
    committedRef.current = true;
    action();
  };

  return (
    <input
      ref={inputRef}
      type="text"
      className="description-input"
      value={value}
      onChange={(e) => setValue(e.target.value)}
      onKeyDown={(e) => {
        if (e.key === 'Enter') {
          e.preventDefault();
          commit(() => onSaveAndNext(value));
        } else if (e.key === 'Escape') {
          e.preventDefault();
          commit(onCancel);
        }
      }}
      onBlur={() => commit(() => onSave(value))}
      aria-label="Edit description"
    />
  );
}

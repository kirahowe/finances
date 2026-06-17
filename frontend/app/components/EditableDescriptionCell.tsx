import { useEffect, useRef, useState } from 'react';

interface EditableDescriptionCellProps {
  initialValue: string;
  // When the editor was opened by type-to-edit, the character typed to open it.
  // It overwrites the cell's value (Excel-style) and the cursor sits after it,
  // rather than the whole value being selected for replacement.
  seedChar?: string | null;
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
  seedChar,
  onSaveAndNext,
  onSave,
  onCancel,
}: EditableDescriptionCellProps) {
  const [value, setValue] = useState(seedChar || initialValue);
  const inputRef = useRef<HTMLInputElement>(null);
  // Enter and Escape both unmount this input, which fires a trailing blur; latch so the
  // blur handler doesn't double-fire a second (stale) commit after we've already acted.
  const committedRef = useRef(false);

  useEffect(() => {
    const input = inputRef.current;
    if (!input) return;
    input.focus();
    if (seedChar) {
      // Type-to-edit: keep the typed character and place the cursor after it.
      const end = input.value.length;
      input.setSelectionRange(end, end);
    } else {
      input.select();
    }
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

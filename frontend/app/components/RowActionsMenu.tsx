import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import '../styles/components/row-actions.css';

export interface RowAction {
  label: string;
  onSelect: () => void;
  danger?: boolean;
}

interface RowActionsMenuProps {
  actions: RowAction[];
  label?: string;
}

// A per-row "⌄" trigger that opens a small menu of row actions (split, match,
// etc.), keeping those actions out of the cells so the table stays uncluttered.
// The menu is portaled to <body> with fixed positioning so it escapes the table's
// stacking context / overflow and never hides behind the sticky header.
export function RowActionsMenu({ actions, label = 'Row actions' }: RowActionsMenuProps) {
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState<{ top: number; right: number } | null>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLUListElement>(null);

  useEffect(() => {
    if (!open) return;
    const onMouseDown = (e: MouseEvent) => {
      const target = e.target as Node;
      if (triggerRef.current?.contains(target) || menuRef.current?.contains(target)) return;
      setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onMouseDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onMouseDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  if (actions.length === 0) return null;

  const toggle = () => {
    if (open) {
      setOpen(false);
      return;
    }
    const rect = triggerRef.current?.getBoundingClientRect();
    if (rect) setPos({ top: rect.bottom + 4, right: Math.max(8, window.innerWidth - rect.right) });
    setOpen(true);
  };

  return (
    <div className="row-actions">
      <button
        ref={triggerRef}
        type="button"
        className="row-actions-trigger"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={label}
        onClick={toggle}
      >
        <svg
          width="16"
          height="16"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <polyline points="9 18 15 12 9 6" />
        </svg>
      </button>
      {open &&
        pos &&
        createPortal(
          <ul
            ref={menuRef}
            className="row-actions-menu"
            role="menu"
            style={{ top: pos.top, right: pos.right }}
          >
            {actions.map((action) => (
              <li key={action.label} role="none">
                <button
                  type="button"
                  role="menuitem"
                  className={`row-actions-item ${action.danger ? 'is-danger' : ''}`}
                  onClick={() => {
                    setOpen(false);
                    action.onSelect();
                  }}
                >
                  {action.label}
                </button>
              </li>
            ))}
          </ul>,
          document.body
        )}
    </div>
  );
}

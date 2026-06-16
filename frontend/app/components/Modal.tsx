import { useEffect, type ReactNode } from "react";
import { useBodyScrollLock } from "../lib/useBodyScrollLock";

interface ModalProps {
  /** Called on backdrop click and (when enabled) Escape. */
  onClose: () => void;
  /** Accessible name for the dialog. */
  label: string;
  /** Extra class on the content box, e.g. a width modifier. */
  className?: string;
  /**
   * Whether Escape closes the modal. Pass `false` while a nested control
   * (e.g. an open dropdown) should own the first Escape instead.
   */
  closeOnEscape?: boolean;
  children: ReactNode;
}

/**
 * Shared dialog shell: a click-to-dismiss backdrop over a centered content
 * box, with the page scroll locked, Escape-to-close, and the dialog ARIA
 * role. Every modal in the app renders through this so the behaviour is
 * uniform; per-modal layout lives in the children and the `className`.
 */
export function Modal({
  onClose,
  label,
  className,
  closeOnEscape = true,
  children,
}: ModalProps) {
  useBodyScrollLock();

  useEffect(() => {
    if (!closeOnEscape) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose, closeOnEscape]);

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className={className ? `modal-content ${className}` : "modal-content"}
        role="dialog"
        aria-modal="true"
        aria-label={label}
        onClick={(e) => e.stopPropagation()}
      >
        {children}
      </div>
    </div>
  );
}

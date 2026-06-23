// Shared modal focus manager for every #modal-root dialog (the split editor, transfer match,
// transfer review). Datastar @get/@put patch a [role="dialog"] into #modal-root and empty it on
// close, but nothing otherwise (a) moves focus INTO the dialog on open, (b) traps Tab inside it,
// or (c) RESTORES focus to the trigger on close — so keyboard / screen-reader users can tab behind
// an open modal and lose their place when it closes. This island wires those three behaviours
// generically for all modals, with no per-modal code.
//
// It manages only the focus boundary: the split-editor island still owns its content + initial
// field focus (this no-ops moving focus in when the dialog already holds focus), and each modal
// keeps its own Esc / backdrop close. The Tab-trap listener lives on the dialog, so while the Zag
// category combobox owns focus — its floating input is a <body> child, OUTSIDE the dialog — this
// never fires and never fights it.

export {};

const modalRoot = document.getElementById('modal-root');
const scroll = document.querySelector<HTMLElement>('.transactions-table-scroll');

const FOCUSABLE =
  'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),' +
  'textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

// offsetParent is null for display:none subtrees, so this skips hidden controls (e.g. the
// type=hidden split courier) and a hidden trigger (a row-actions menu item whose menu closed).
const visible = (el: HTMLElement): boolean => el.offsetParent !== null;

if (modalRoot) {
  let dialog: HTMLElement | null = null;
  let trigger: HTMLElement | null = null;

  const focusables = (): HTMLElement[] =>
    dialog ? [...dialog.querySelectorAll<HTMLElement>(FOCUSABLE)].filter(visible) : [];

  const onKeydown = (e: KeyboardEvent): void => {
    if (e.key !== 'Tab' || !dialog) return;
    const f = focusables();
    if (!f.length) return;
    const first = f[0];
    const last = f[f.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  };

  const open = (d: HTMLElement): void => {
    if (d === dialog) return; // already managing this dialog (a subtree mutation re-fired us)
    dialog = d;
    trigger = document.activeElement as HTMLElement | null;
    dialog.addEventListener('keydown', onKeydown);
    // Move focus in only if a more specific island hasn't already placed it inside (the split
    // editor focuses its first amount field) — don't fight that.
    if (!dialog.contains(document.activeElement)) {
      (focusables()[0] ?? dialog).focus();
    }
  };

  const close = (): void => {
    if (dialog) dialog.removeEventListener('keydown', onKeydown);
    dialog = null;
    // Restore focus to the trigger if it's still around and visible; otherwise land on the table
    // (a sane neutral home) rather than dropping focus to <body>.
    if (trigger && trigger.isConnected && visible(trigger)) trigger.focus();
    else scroll?.focus();
    trigger = null;
  };

  new MutationObserver(() => {
    const d = modalRoot.querySelector<HTMLElement>('[role="dialog"]');
    if (d) open(d);
    else if (dialog) close();
  }).observe(modalRoot, { childList: true, subtree: true });
}

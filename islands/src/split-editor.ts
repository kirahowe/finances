// Split-editor island — the rich modal for dividing one transaction into parts.
//
// Server seam (transactions.clj `split-editor-modal`): GET /transactions/:id/split-editor
// patches the modal into #modal-root. The dialog ([data-split-editor]) carries the parent
// amount, a JSON seed of the existing parts, and the already-split flag. This island fills
// .split-rows-container, runs the live balance math (lib/splitMath — the same pure module the
// server validates with), and on Save writes the signed-amount payload into #split-courier,
// whose `change` @put's /transactions/:id/splits. That PUT goes through the command log
// (undo/redo + lingering) and closes the modal by re-patching #modal-root empty. Cancel / Esc
// / backdrop click close here (we wipe #modal-root) — no round-trip.

import {
  remainingCents,
  fillRemainingCents,
  canConfirm,
  rowSignedCents,
  centsToAmountString,
  type SplitRowInput,
} from './lib/splitMath';
// The category picker reuses the grid's Zag combobox via the window hook combobox.ts exposes
// (window.__openCombobox) — NOT an import, so esbuild doesn't inline a second copy of Zag here.

interface Row extends SplitRowInput {
  memo: string;
  // The existing live part this row edits (its transaction db id), or null for a
  // fresh row. Rides the save payload so set-splits! diffs by id — an id'd row
  // updates its part in place (preserving reconciled/transfer-pair/external-id).
  id: number | null;
}

// One seed part as the server serializes it (charred → kebab-case keyword keys).
interface SeedRow {
  id: number | null;
  amount: string;
  'category-id': number | null;
  memo: string | null;
  'seed-cents': number | null;
}

// Map category id → display name from the hidden #category-options list the page renders
// (the same DOM-carried model the combobox island reads). Used to label a row's category
// button (its seeded categoryId, and after a pick the combobox hands us the chosen label).
function readCategoryNames(): Map<number, string> {
  const names = new Map<number, string>();
  for (const li of document.querySelectorAll<HTMLLIElement>('#category-options li')) {
    names.set(Number(li.dataset.id), (li.textContent ?? '').trim());
  }
  return names;
}

const closeModal = () => {
  const root = document.getElementById('modal-root');
  if (root) root.innerHTML = '';
};

// Disposers for the current modal's document/backdrop listeners. Every close path tears them
// down — the document-level keydown (Esc) listener otherwise leaked one per reopen (it used to
// remove itself only on the Esc path, not Cancel/backdrop/Save/Un-split). Save and Un-split
// close server-side (the PUT empties #modal-root); the observer at the bottom runs cleanup then.
let activeCleanup: (() => void) | null = null;

function mount(root: HTMLElement): void {
  if (root.dataset.mounted === '1') return;
  root.dataset.mounted = '1';

  const parentAmount = Number(root.dataset.amount);
  const seed: SeedRow[] = JSON.parse(root.dataset.seed || '[]');
  const categoryNames = readCategoryNames();
  const labelFor = (id: number | null): string =>
    id == null ? 'Select category…' : categoryNames.get(id) ?? 'Select category…';

  // Blank parts when the transaction isn't split yet (a split needs at least two).
  const blank = (): Row => ({ id: null, amount: '', categoryId: null, memo: '', seedCents: null });
  const rows: Row[] =
    seed.length > 0
      ? seed.map((s) => ({
          id: s.id ?? null,
          amount: s.amount,
          categoryId: s['category-id'],
          memo: s.memo ?? '',
          seedCents: s['seed-cents'],
        }))
      : [blank(), blank()];

  const container = root.querySelector<HTMLElement>('.split-rows-container')!;
  const remainingEl = root.querySelector<HTMLElement>('.split-remaining')!;
  const saveBtn = root.querySelector<HTMLButtonElement>('.split-save')!;
  const sign = parentAmount < 0 ? '−' : '+'; // − or +

  // Build one category control: a button styled like a form field (see split-modal.css)
  // that opens the SAME Zag combobox/typeahead as the grid cell. The split isn't persisted
  // until "Save split", so the pick updates LOCAL row state (no @put) — the row keeps its
  // categoryId and the button text, and recompute() re-evaluates the balance/save gate.
  // The combobox lists Uncategorized; a pick of it maps to categoryId null — a valid,
  // saveable state (the part lands in the Uncategorized bucket, categorize later); the
  // button keeps its placeholder look for null.
  const buildCategoryButton = (row: Row): HTMLButtonElement => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'category-button';
    // A pending (unset) category reads muted via the placeholder modifier.
    btn.classList.toggle('is-placeholder', row.categoryId == null);
    btn.setAttribute('aria-haspopup', 'listbox');
    btn.setAttribute('aria-label', 'Split category');
    btn.textContent = labelFor(row.categoryId);
    btn.addEventListener('click', () => {
      window.__openCombobox?.({
        anchor: btn,
        placeholder: labelFor(row.categoryId),
        onCommit(categoryId, label) {
          row.categoryId = categoryId;
          btn.textContent = categoryId == null ? 'Select category…' : label;
          btn.classList.toggle('is-placeholder', categoryId == null);
          recompute();
        },
      });
    });
    return btn;
  };

  // Recompute the live balance, the Save gate, and the per-row Fill/Remove enabled states.
  const recompute = (): void => {
    const remaining = remainingCents(parentAmount, rows);
    remainingEl.classList.toggle('balanced', remaining === 0 && rows.length >= 2);
    remainingEl.textContent =
      remaining === 0
        ? 'Balanced'
        : remaining > 0
          ? `${centsToAmountString(remaining)} left to allocate`
          : `Over by ${centsToAmountString(-remaining)}`;
    saveBtn.disabled = !canConfirm(parentAmount, rows);

    const fillBtns = container.querySelectorAll<HTMLButtonElement>('.split-fill-button');
    rows.forEach((_, i) => {
      if (fillBtns[i]) fillBtns[i].disabled = fillRemainingCents(parentAmount, rows, i) <= 0;
    });
    container.querySelectorAll<HTMLButtonElement>('.split-remove-button').forEach((b) => {
      b.disabled = rows.length <= 2;
    });
  };

  const renderRows = (): void => {
    container.innerHTML = '';
    rows.forEach((row, i) => {
      const el = document.createElement('div');
      el.className = 'split-row';

      // Amount: sign prefix + magnitude input + "fill remaining" shortcut.
      const amountCell = document.createElement('div');
      amountCell.className = 'split-amount-cell';
      const signEl = document.createElement('span');
      signEl.className = 'split-amount-sign';
      signEl.textContent = sign;
      const amountInput = document.createElement('input');
      amountInput.className = 'split-amount-input form-input';
      amountInput.setAttribute('inputmode', 'decimal');
      amountInput.setAttribute('aria-label', 'Split amount');
      amountInput.value = row.amount;
      amountInput.addEventListener('input', () => {
        row.amount = amountInput.value;
        row.seedCents = null; // editing the magnitude drops the stored signed seed
        recompute();
      });
      const fillBtn = document.createElement('button');
      fillBtn.type = 'button';
      fillBtn.className = 'split-fill-button';
      fillBtn.textContent = 'Fill';
      fillBtn.title = 'Fill with the remaining amount';
      fillBtn.addEventListener('click', () => {
        const cents = fillRemainingCents(parentAmount, rows, i);
        if (cents <= 0) return;
        row.amount = centsToAmountString(cents);
        row.seedCents = null;
        amountInput.value = row.amount;
        recompute();
      });
      amountCell.append(signEl, amountInput, fillBtn);

      // Category: the same Zag combobox/typeahead as the grid cell (opened from a button
      // styled as a form field). Picks update local row state only — no @put until Save.
      const categoryCell = document.createElement('div');
      categoryCell.className = 'split-category-cell';
      categoryCell.appendChild(buildCategoryButton(row));

      // Description (memo).
      const memoInput = document.createElement('input');
      memoInput.className = 'split-memo-input form-input';
      memoInput.placeholder = 'Description';
      memoInput.setAttribute('aria-label', 'Split description');
      memoInput.value = row.memo;
      memoInput.addEventListener('input', () => {
        row.memo = memoInput.value;
      });

      // Remove (disabled at the 2-part floor).
      const removeBtn = document.createElement('button');
      removeBtn.type = 'button';
      removeBtn.className = 'split-remove-button';
      removeBtn.setAttribute('aria-label', 'Remove part');
      removeBtn.textContent = '×';
      removeBtn.addEventListener('click', () => {
        rows.splice(i, 1);
        renderRows();
        recompute();
      });

      el.append(amountCell, categoryCell, memoInput, removeBtn);
      container.appendChild(el);
    });
  };

  // Save: build the signed-amount payload and hand it to the courier (whose change @put's).
  // A row's `id` (its existing live part) rides along so set-splits! diffs by id.
  const save = (): void => {
    if (!canConfirm(parentAmount, rows)) return;
    const payload = rows.map((r) => {
      const signed = rowSignedCents(parentAmount, r)!;
      return {
        id: r.id ?? undefined,
        amount: centsToAmountString(signed),
        categoryId: r.categoryId,
        memo: r.memo.trim() || undefined,
      };
    });
    submit(JSON.stringify(payload));
  };

  // Un-split: an empty payload clears the parts server-side.
  const unsplit = (): void => submit('[]');

  const submit = (value: string): void => {
    const courier = document.getElementById('split-courier') as HTMLInputElement | null;
    if (!courier) return;
    courier.value = value;
    courier.dispatchEvent(new Event('change', { bubbles: true }));
  };

  root.querySelector<HTMLButtonElement>('.split-add-button')!.addEventListener('click', () => {
    rows.push(blank());
    renderRows();
    recompute();
    // Focus the new row's amount field.
    container.querySelector<HTMLInputElement>('.split-row:last-child .split-amount-input')?.focus();
  });
  // Close wiring. Cancel / Esc / backdrop close here; Save / Un-split close server-side (the PUT
  // empties #modal-root) — the observer at the bottom runs cleanup() when that removal lands.
  const backdrop = root.closest('.modal-backdrop') ?? root.parentElement;
  const onBackdrop = (e: Event): void => { if (e.target === backdrop) close(); };
  const onKey = (e: KeyboardEvent): void => {
    if (e.key !== 'Escape') return;
    // While the category combobox is open, Escape closes only it (Zag handles it), not the modal.
    if (document.querySelector('.category-dropdown.is-floating')) return;
    close();
  };
  const cleanup = (): void => {
    document.removeEventListener('keydown', onKey);
    backdrop?.removeEventListener('click', onBackdrop);
    activeCleanup = null;
  };
  const close = (): void => { cleanup(); closeModal(); };
  activeCleanup = cleanup;

  root.querySelector<HTMLButtonElement>('.split-cancel')!.addEventListener('click', close);
  root.querySelector<HTMLButtonElement>('.split-unsplit')?.addEventListener('click', unsplit);
  saveBtn.addEventListener('click', save);
  backdrop?.addEventListener('click', onBackdrop);
  document.addEventListener('keydown', onKey);

  renderRows();
  recompute();
  container.querySelector<HTMLInputElement>('.split-amount-input')?.focus();
}

// The modal is patched into #modal-root after page load, so watch for the editor appearing.
// When it's removed (a server-side close from Save/Un-split), run the current modal's cleanup so
// its document/backdrop listeners don't outlive it.
const observer = new MutationObserver(() => {
  const root = document.querySelector<HTMLElement>('[data-split-editor]');
  if (root) mount(root);
  else if (activeCleanup) activeCleanup();
});
const modalRoot = document.getElementById('modal-root');
if (modalRoot) observer.observe(modalRoot, { childList: true, subtree: true });

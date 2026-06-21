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
import { orderCategoriesHierarchically } from './lib/categoryHierarchy';
import type { Category } from './lib/types';

interface Row extends SplitRowInput {
  memo: string;
}

// One seed part as the server serializes it (charred → kebab-case keyword keys).
interface SeedRow {
  amount: string;
  'category-id': number | null;
  memo: string | null;
  'seed-cents': number | null;
}

// Reconstruct Category[] from the hidden #category-options list the page renders (shared with
// the combobox island; the model travels in the DOM, renderer-agnostic).
function readCategories(): Category[] {
  return [...document.querySelectorAll<HTMLLIElement>('#category-options li')].map((li) => {
    const cat: Category = {
      'db/id': Number(li.dataset.id),
      'category/name': (li.textContent ?? '').trim(),
      'category/type': (li.dataset.type as Category['category/type']) ?? 'expense',
    };
    if (li.dataset.parent) cat['category/parent'] = { 'db/id': Number(li.dataset.parent) };
    if (li.dataset.sort) cat['category/sort-order'] = Number(li.dataset.sort);
    return cat;
  });
}

const closeModal = () => {
  const root = document.getElementById('modal-root');
  if (root) root.innerHTML = '';
};

function mount(root: HTMLElement): void {
  if (root.dataset.mounted === '1') return;
  root.dataset.mounted = '1';

  const parentAmount = Number(root.dataset.amount);
  const seed: SeedRow[] = JSON.parse(root.dataset.seed || '[]');
  const categories = readCategories();
  const ordered = orderCategoriesHierarchically(categories);

  // Blank parts when the transaction isn't split yet (a split needs at least two).
  const blank = (): Row => ({ amount: '', categoryId: null, memo: '', seedCents: null });
  const rows: Row[] =
    seed.length > 0
      ? seed.map((s) => ({
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

  // Build one category <select>. Splits require a category, so the placeholder is disabled
  // once a real one is chosen; children are indented under their parent.
  const buildSelect = (selected: number | null): HTMLSelectElement => {
    const sel = document.createElement('select');
    sel.className = 'split-category-select form-select';
    sel.setAttribute('aria-label', 'Split category');
    const placeholder = document.createElement('option');
    placeholder.value = '';
    placeholder.textContent = 'Select category…';
    placeholder.disabled = selected != null;
    sel.appendChild(placeholder);
    for (const { category, depth } of ordered) {
      const opt = document.createElement('option');
      const id = category['db/id'];
      opt.value = String(id);
      opt.textContent = depth > 0 ? `— ${category['category/name']}` : category['category/name'];
      if (id === selected) opt.selected = true;
      sel.appendChild(opt);
    }
    if (selected == null) sel.value = '';
    return sel;
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

      // Category: a hierarchical native select (no second Zag instance).
      const categoryCell = document.createElement('div');
      categoryCell.className = 'split-category-cell';
      const select = buildSelect(row.categoryId);
      select.addEventListener('change', () => {
        row.categoryId = select.value ? Number(select.value) : null;
        recompute();
      });
      categoryCell.appendChild(select);

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
  const save = (): void => {
    if (!canConfirm(parentAmount, rows)) return;
    const payload = rows.map((r) => {
      const signed = rowSignedCents(parentAmount, r)!;
      return {
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
  root.querySelector<HTMLButtonElement>('.split-cancel')!.addEventListener('click', closeModal);
  root.querySelector<HTMLButtonElement>('.split-unsplit')?.addEventListener('click', unsplit);
  saveBtn.addEventListener('click', save);

  // Backdrop click (outside the dialog) closes; Esc closes (removed when the modal is gone).
  const backdrop = root.closest('.modal-backdrop') ?? root.parentElement;
  backdrop?.addEventListener('click', (e) => {
    if (e.target === backdrop) closeModal();
  });
  const onKey = (e: KeyboardEvent): void => {
    if (e.key === 'Escape') {
      closeModal();
      document.removeEventListener('keydown', onKey);
    }
  };
  document.addEventListener('keydown', onKey);

  renderRows();
  recompute();
  container.querySelector<HTMLInputElement>('.split-amount-input')?.focus();
}

// The modal is patched into #modal-root after page load, so watch for the editor appearing.
const observer = new MutationObserver(() => {
  const root = document.querySelector<HTMLElement>('[data-split-editor]');
  if (root) mount(root);
});
const modalRoot = document.getElementById('modal-root');
if (modalRoot) observer.observe(modalRoot, { childList: true, subtree: true });

import { useState, useEffect } from 'react';
import { api, type Transaction, type Category } from '../lib/api';
import { CategoryDropdown } from './CategoryDropdown';
import { formatAmount } from '../lib/format';
import {
  remainingCents,
  canConfirm,
  fillRemainingCents,
  parseMagnitudeCents,
  centsToAmountString,
} from '../lib/splitMath';
import '../styles/components/split-modal.css';

interface SplitTransactionModalProps {
  transaction: Transaction;
  categories: Category[];
  onClose: () => void;
  onSaved: () => void;
}

interface SplitRow {
  key: string;
  amount: string;
  categoryId: number | null;
  memo: string;
}

let rowSeq = 0;
const newRow = (patch: Partial<SplitRow> = {}): SplitRow => ({
  key: `split-row-${rowSeq++}`,
  amount: '',
  categoryId: null,
  memo: '',
  ...patch,
});

function seedRows(transaction: Transaction): SplitRow[] {
  const splits = transaction['transaction/splits'];
  if (splits && splits.length > 0) {
    return [...splits]
      .sort((a, b) => (a['split/order'] ?? 0) - (b['split/order'] ?? 0))
      .map((s) =>
        newRow({
          // Stored amounts are signed; the editor shows positive magnitudes.
          amount: String(Math.abs(s['split/amount'])),
          categoryId: s['split/category']?.['db/id'] ?? null,
          memo: s['split/memo'] ?? '',
        })
      );
  }
  return [newRow(), newRow()];
}

export function SplitTransactionModal({
  transaction,
  categories,
  onClose,
  onSaved,
}: SplitTransactionModalProps) {
  const [rows, setRows] = useState<SplitRow[]>(() => seedRows(transaction));
  const [editingKey, setEditingKey] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Keep the page behind the modal from scrolling while it's open.
  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, []);

  const parentAmount = transaction['transaction/amount'];
  const sign = parentAmount < 0 ? -1 : 1;
  const signChar = sign < 0 ? '−' : '+';
  const alreadySplit = (transaction['transaction/splits']?.length ?? 0) > 0;
  const remaining = remainingCents(parentAmount, rows);
  const balanced = remaining === 0;
  const confirmable = canConfirm(parentAmount, rows) && !submitting;

  const categoryName = (id: number | null) =>
    (id !== null && categories.find((c) => c['db/id'] === id)?.['category/name']) || 'Uncategorized';

  const updateRow = (key: string, patch: Partial<SplitRow>) =>
    setRows((prev) => prev.map((r) => (r.key === key ? { ...r, ...patch } : r)));

  const removeRow = (key: string) => setRows((prev) => prev.filter((r) => r.key !== key));

  const fillRow = (key: string, index: number) => {
    const target = fillRemainingCents(parentAmount, rows, index);
    if (target > 0) updateRow(key, { amount: centsToAmountString(target) });
  };

  const save = async (splits: Array<{ amount: string; categoryId: number | null; memo?: string }>) => {
    setSubmitting(true);
    setError(null);
    try {
      await api.setTransactionSplits(transaction['db/id'], splits);
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save splits');
      setSubmitting(false);
    }
  };

  const handleSave = () => {
    if (!canConfirm(parentAmount, rows)) return;
    save(
      rows.map((r) => ({
        // Apply the transaction's sign to the entered magnitude.
        amount: centsToAmountString(sign * (parseMagnitudeCents(r.amount) ?? 0)),
        categoryId: r.categoryId,
        memo: r.memo.trim() || undefined,
      }))
    );
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-content split-modal-content" onClick={(e) => e.stopPropagation()}>
        <h2>Split transaction</h2>
        <p className="split-modal-sub">
          <span>{transaction['transaction/payee']}</span>
          <span className={`numeric ${parentAmount >= 0 ? 'positive' : 'negative'}`}>
            {formatAmount(parentAmount)}
          </span>
        </p>
        <p className="split-modal-hint">
          Enter each part as a positive amount; it’s recorded as{' '}
          {sign < 0 ? 'an expense' : 'income'} ({signChar}) to match the transaction.
        </p>

        <div className="split-row split-row-head">
          <span>Amount</span>
          <span>Category</span>
          <span>Memo</span>
          <span className="sr-only">Remove</span>
        </div>

        {rows.map((row, index) => (
          <div className="split-row" key={row.key}>
            <div className="split-amount-cell">
              <span className="split-amount-sign" aria-hidden="true">
                {signChar}
              </span>
              <input
                className="form-input split-amount-input numeric"
                type="text"
                inputMode="decimal"
                aria-label="Split amount"
                placeholder="0.00"
                autoFocus={index === 0}
                value={row.amount}
                onChange={(e) => updateRow(row.key, { amount: e.target.value })}
              />
              <button
                type="button"
                className="split-fill-button"
                title="Fill with the remaining balance"
                disabled={fillRemainingCents(parentAmount, rows, index) <= 0}
                onClick={() => fillRow(row.key, index)}
              >
                rest
              </button>
            </div>
            <div className="split-category-cell">
              {editingKey === row.key ? (
                <CategoryDropdown
                  categories={categories}
                  selectedCategoryId={row.categoryId}
                  onSelect={(categoryId) => {
                    updateRow(row.key, { categoryId });
                    setEditingKey(null);
                  }}
                  onClose={() => setEditingKey(null)}
                />
              ) : (
                <button
                  type="button"
                  className="category-button"
                  onClick={() => setEditingKey(row.key)}
                >
                  {categoryName(row.categoryId)}
                </button>
              )}
            </div>
            <input
              className="form-input"
              type="text"
              aria-label="Split memo"
              placeholder="Optional"
              value={row.memo}
              onChange={(e) => updateRow(row.key, { memo: e.target.value })}
            />
            <button
              type="button"
              className="bulk-remove-button"
              aria-label="Remove part"
              title="Remove part"
              onClick={() => removeRow(row.key)}
            >
              ×
            </button>
          </div>
        ))}

        <button
          type="button"
          className="split-add-button"
          onClick={() => setRows((prev) => [...prev, newRow()])}
        >
          + Add part
        </button>

        <div className={`split-remaining ${balanced ? 'balanced' : ''}`}>
          {balanced ? (
            <span>Balanced</span>
          ) : (
            <>
              <span>{remaining > 0 ? 'Remaining' : 'Over by'}</span>
              <span className={`numeric ${remaining < 0 ? 'negative' : ''}`}>
                {formatAmount(Math.abs(remaining) / 100)}
              </span>
            </>
          )}
        </div>

        {error && <div className="error-banner">{error}</div>}

        <div className="split-modal-actions">
          <div>
            {alreadySplit && (
              <button
                type="button"
                className="button button-secondary"
                onClick={() => save([])}
                disabled={submitting}
              >
                Un-split
              </button>
            )}
          </div>
          <div className="button-group">
            <button
              type="button"
              className="button button-secondary"
              onClick={onClose}
              disabled={submitting}
            >
              Cancel
            </button>
            <button type="button" className="button" onClick={handleSave} disabled={!confirmable}>
              {submitting ? 'Saving…' : 'Save split'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

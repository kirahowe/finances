import { useState } from 'react';
import { api, type Transaction, type Category } from '../lib/api';
import { CategoryDropdown } from './CategoryDropdown';
import { Modal } from './Modal';
import { formatAmount } from '../lib/format';
import {
  remainingCents,
  canConfirm,
  fillRemainingCents,
  centsToAmountString,
  toCents,
  rowSignedCents,
  sortSplits,
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
  // Signed cents from a stored split, retained until the magnitude is edited so a
  // mixed-sign part is not silently normalized to the parent's sign on re-save.
  seedCents: number | null;
}

let rowSeq = 0;
const newRow = (patch: Partial<SplitRow> = {}): SplitRow => ({
  key: `split-row-${rowSeq++}`,
  amount: '',
  categoryId: null,
  memo: '',
  seedCents: null,
  ...patch,
});

function seedRows(transaction: Transaction): SplitRow[] {
  const splits = transaction['transaction/splits'];
  if (splits && splits.length > 0) {
    return sortSplits(splits).map((s) =>
        newRow({
          // Stored amounts are signed; the editor shows a 2-decimal positive
          // magnitude (toFixed avoids float artifacts that fail the amount regex)
          // and keeps the original signed cents so the sign survives a round-trip.
          amount: Math.abs(s['split/amount']).toFixed(2),
          seedCents: toCents(s['split/amount']),
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

  const parentAmount = transaction['transaction/amount'];
  const sign = parentAmount < 0 ? -1 : 1;
  const signChar = sign < 0 ? '−' : '+';
  // A seeded part can oppose the parent's sign (mixed-sign split); show its own.
  const rowSignChar = (row: SplitRow) =>
    row.seedCents != null ? (row.seedCents < 0 ? '−' : '+') : signChar;
  const alreadySplit = (transaction['transaction/splits']?.length ?? 0) > 0;
  const remaining = remainingCents(parentAmount, rows);
  const balanced = remaining === 0;
  const confirmable = canConfirm(parentAmount, rows) && !submitting;
  // Each row's fill target, computed once per render rather than re-scanning all
  // rows inside every row's disabled={} (which was O(rows²) per render).
  const fillTargets = rows.map((_, i) => fillRemainingCents(parentAmount, rows, i));

  const categoryName = (id: number | null) =>
    (id !== null && categories.find((c) => c['db/id'] === id)?.['category/name']) || 'Uncategorized';

  const updateRow = (key: string, patch: Partial<SplitRow>) =>
    setRows((prev) => prev.map((r) => (r.key === key ? { ...r, ...patch } : r)));

  const removeRow = (key: string) => setRows((prev) => prev.filter((r) => r.key !== key));

  const fillRow = (key: string, target: number) => {
    if (target > 0) updateRow(key, { amount: centsToAmountString(target), seedCents: null });
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
        // Signed cents: a seeded part keeps its stored sign; an entered magnitude
        // takes the parent's sign.
        amount: centsToAmountString(rowSignedCents(parentAmount, r) ?? 0),
        categoryId: r.categoryId,
        memo: r.memo.trim() || undefined,
      }))
    );
  };

  return (
    <Modal
      onClose={onClose}
      label="Split transaction"
      className="split-modal-content"
      closeOnEscape={editingKey === null}
    >
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
          <span>Description</span>
          <span className="sr-only">Remove</span>
        </div>

        {rows.map((row, index) => (
          <div className="split-row" key={row.key}>
            <div className="split-amount-cell">
              <span className="split-amount-sign" aria-hidden="true">
                {rowSignChar(row)}
              </span>
              <input
                className="form-input split-amount-input numeric"
                type="text"
                inputMode="decimal"
                aria-label="Split amount"
                placeholder="0.00"
                autoFocus={index === 0}
                value={row.amount}
                onChange={(e) => updateRow(row.key, { amount: e.target.value, seedCents: null })}
              />
              <button
                type="button"
                className="split-fill-button"
                title="Fill with the remaining balance"
                disabled={fillTargets[index] <= 0}
                onClick={() => fillRow(row.key, fillTargets[index])}
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
              aria-label="Split description"
              placeholder="Optional"
              value={row.memo}
              onChange={(e) => updateRow(row.key, { memo: e.target.value })}
            />
            <button
              type="button"
              className="split-remove-button"
              aria-label="Remove part"
              title={rows.length <= 2 ? 'A split needs at least 2 parts' : 'Remove part'}
              disabled={rows.length <= 2}
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
    </Modal>
  );
}

import { useMemo, useState } from 'react';
import { api, type Category } from '../lib/api';
import { CATEGORY_TYPE_OPTIONS, type CategoryType } from '../lib/categoryTypes';
import { buildBulkRows, type BulkCategoryRow } from '../lib/categoryParse';
import { generateCategoryIdent } from '../lib/identGenerator';

interface BulkCategoryModalProps {
  existingCategories: Category[];
  onClose: () => void;
  onCreated: () => void;
}

type RowError = 'empty' | 'duplicate' | 'exists' | null;

const PLACEHOLDER = `- Food
  - Groceries
  - Dining Out
- Transport
  - Gas
  - Transit
- Salary`;

export function BulkCategoryModal({ existingCategories, onClose, onCreated }: BulkCategoryModalProps) {
  const [text, setText] = useState('');
  const [defaultType, setDefaultType] = useState<CategoryType>('expense');
  const [rows, setRows] = useState<BulkCategoryRow[] | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const existingIdents = useMemo(
    () => new Set(existingCategories.map((c) => c['category/ident']).filter(Boolean) as string[]),
    [existingCategories]
  );

  // Per-row validation keyed by tempId, plus whether the batch is submittable.
  const { rowErrors, hasErrors } = useMemo(() => {
    const errors: Record<string, RowError> = {};
    if (!rows) return { rowErrors: errors, hasErrors: false };

    const identCounts = new Map<string, number>();
    for (const row of rows) {
      const name = row.name.trim();
      if (name === '') continue;
      const ident = generateCategoryIdent(name);
      identCounts.set(ident, (identCounts.get(ident) ?? 0) + 1);
    }

    let any = false;
    for (const row of rows) {
      const name = row.name.trim();
      const ident = generateCategoryIdent(name);
      let err: RowError = null;
      if (name === '') err = 'empty';
      else if ((identCounts.get(ident) ?? 0) > 1) err = 'duplicate';
      else if (existingIdents.has(ident)) err = 'exists';
      errors[row.tempId] = err;
      if (err) any = true;
    }
    return { rowErrors: errors, hasErrors: any };
  }, [rows, existingIdents]);

  const handlePreview = () => {
    setSubmitError(null);
    setRows(buildBulkRows(text, defaultType));
  };

  const updateRow = (tempId: string, patch: Partial<BulkCategoryRow>) => {
    setRows((prev) => prev?.map((r) => (r.tempId === tempId ? { ...r, ...patch } : r)) ?? null);
  };

  const removeRow = (tempId: string) => {
    setRows((prev) => prev?.filter((r) => r.tempId !== tempId) ?? null);
  };

  const handleConfirm = async () => {
    if (!rows || rows.length === 0 || hasErrors) return;
    setSubmitting(true);
    setSubmitError(null);

    const presentTempIds = new Set(rows.map((r) => r.tempId));
    const payload = rows.map((r) => {
      const name = r.name.trim();
      const linkParent = r.parentTempId !== null && presentTempIds.has(r.parentTempId);
      return {
        name,
        type: r.type,
        ident: generateCategoryIdent(name),
        tempId: r.tempId,
        ...(linkParent ? { parentTempId: r.parentTempId } : {}),
      };
    });

    try {
      await api.bulkCreateCategories(payload);
      onCreated();
    } catch (err) {
      setSubmitError(err instanceof Error ? err.message : 'Failed to create categories');
      setSubmitting(false);
    }
  };

  const nameByTempId = useMemo(() => {
    const map = new Map<string, string>();
    rows?.forEach((r) => map.set(r.tempId, r.name.trim() || '(unnamed)'));
    return map;
  }, [rows]);

  const inPreview = rows !== null;

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-content bulk-modal-content" onClick={(e) => e.stopPropagation()}>
        <h2>Bulk Add Categories</h2>

        {!inPreview && (
          <>
            <textarea
              className="form-input bulk-modal-textarea"
              aria-label="Categories to paste"
              placeholder={PLACEHOLDER}
              value={text}
              onChange={(e) => setText(e.target.value)}
            />
            <p className="bulk-modal-hint">
              One category per line. Indent (or use a nested markdown list) to put a
              sub-category under the line above it. Leading bullets like “-” are ignored.
            </p>
            <div className="bulk-modal-type">
              <label className="form-label" htmlFor="bulk-default-type">
                Default type
              </label>
              <select
                className="form-select"
                id="bulk-default-type"
                value={defaultType}
                onChange={(e) => setDefaultType(e.target.value as CategoryType)}
              >
                {CATEGORY_TYPE_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </div>
            <div className="form-actions">
              <button type="button" className="button button-secondary" onClick={onClose}>
                Cancel
              </button>
              <button
                type="button"
                className="button"
                onClick={handlePreview}
                disabled={text.trim() === ''}
              >
                Preview
              </button>
            </div>
          </>
        )}

        {inPreview && rows.length === 0 && (
          <>
            <p className="bulk-preview-empty">Nothing to import — no categories were found.</p>
            <div className="form-actions">
              <button type="button" className="button button-secondary" onClick={() => setRows(null)}>
                Back
              </button>
            </div>
          </>
        )}

        {inPreview && rows.length > 0 && (
          <>
            <p className="bulk-modal-hint">
              Review and edit before creating. Duplicate or empty names must be fixed or removed.
            </p>
            <div className="bulk-preview-scroll">
              <table className="table">
                <thead>
                  <tr>
                    <th>Name</th>
                    <th>Type</th>
                    <th>Parent</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => {
                    const err = rowErrors[row.tempId];
                    const parentName =
                      row.parentTempId !== null ? nameByTempId.get(row.parentTempId) ?? '—' : '—';
                    return (
                      <tr key={row.tempId} className={err ? 'bulk-preview-row--error' : undefined}>
                        <td>
                          <input
                            type="text"
                            className={`form-input${row.parentTempId !== null ? ' bulk-preview-name--child' : ''}`}
                            aria-label="Name"
                            value={row.name}
                            onChange={(e) => updateRow(row.tempId, { name: e.target.value })}
                          />
                          {err === 'empty' && <div className="bulk-preview-error">Name required</div>}
                          {err === 'duplicate' && (
                            <div className="bulk-preview-error">Duplicate in list</div>
                          )}
                          {err === 'exists' && (
                            <div className="bulk-preview-error">Category already exists</div>
                          )}
                        </td>
                        <td>
                          <select
                            className="form-select"
                            aria-label="Type"
                            value={row.type}
                            onChange={(e) =>
                              updateRow(row.tempId, { type: e.target.value as CategoryType })
                            }
                          >
                            {CATEGORY_TYPE_OPTIONS.map((option) => (
                              <option key={option.value} value={option.value}>
                                {option.label}
                              </option>
                            ))}
                          </select>
                        </td>
                        <td className="category-parent-cell">{parentName}</td>
                        <td>
                          <button
                            type="button"
                            className="bulk-remove-button"
                            aria-label="Remove"
                            title="Remove"
                            onClick={() => removeRow(row.tempId)}
                          >
                            ×
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            {submitError && <div className="error-banner">{submitError}</div>}

            <div className="form-actions">
              <button
                type="button"
                className="button button-secondary"
                onClick={() => setRows(null)}
                disabled={submitting}
              >
                Back
              </button>
              <button
                type="button"
                className="button"
                onClick={handleConfirm}
                disabled={hasErrors || submitting}
              >
                {submitting ? 'Creating…' : `Create ${rows.length} categor${rows.length === 1 ? 'y' : 'ies'}`}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

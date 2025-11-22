import { useState, type KeyboardEvent, useEffect, useRef } from 'react';
import type { Category } from '../lib/api';
import {
  createDraftCategory,
  updateDraftCategory,
  validateCategory,
  isDraftEmpty,
  type CategoryDraft,
} from '../lib/categoryDraft';

interface CategoryTableProps {
  categories: Category[];
  newCategoryIds?: number[];
  onEdit: (category: Category) => void;
  onDelete: (category: Category) => void;
  onCreate: (draft: CategoryDraft) => void;
}

export function CategoryTable({
  categories,
  newCategoryIds = [],
  onEdit,
  onDelete,
  onCreate,
}: CategoryTableProps) {
  const [draft, setDraft] = useState<CategoryDraft | null>(null);
  const [validationError, setValidationError] = useState<string | null>(null);
  const nameInputRef = useRef<HTMLInputElement>(null);
  const lastNewCategoryRef = useRef<HTMLTableRowElement>(null);
  const prevCategoryCountRef = useRef(categories.length);

  // Split categories into existing and new
  const newCategoryIdSet = new Set(newCategoryIds);
  const existingCategories = categories.filter(
    (cat) => !newCategoryIdSet.has(cat['db/id'])
  );
  const newCategories = newCategoryIds
    .map((id) => categories.find((cat) => cat['db/id'] === id))
    .filter((cat): cat is Category => cat !== undefined);
  const orderedCategories = [...existingCategories, ...newCategories];

  // Get existing category names for duplicate checking
  const existingCategoryNames = categories.map((cat) => cat['category/name']);

  // Auto-focus and scroll to name input when draft is created
  useEffect(() => {
    if (draft !== null && nameInputRef.current) {
      nameInputRef.current.focus();
      // Scroll the input to center when draft is created
      // This prevents the browser from doing its own scroll-into-view
      if (nameInputRef.current.scrollIntoView) {
        nameInputRef.current.scrollIntoView({
          behavior: 'smooth',
          block: 'center',
        });
      }
    }
  }, [draft]);

  // Auto-scroll to the last new category only when NOT in draft mode
  useEffect(() => {
    if (categories.length > prevCategoryCountRef.current && draft === null) {
      // A new category was added and we're not in auto-continuation mode
      if (lastNewCategoryRef.current && lastNewCategoryRef.current.scrollIntoView) {
        lastNewCategoryRef.current.scrollIntoView({
          behavior: 'smooth',
          block: 'center',
        });
      }
    }
    prevCategoryCountRef.current = categories.length;
  }, [categories.length, draft]);

  const handleAddClick = () => {
    if (draft === null) {
      // Show draft row
      setDraft(createDraftCategory());
      setValidationError(null);
    } else {
      // Submit if valid
      handleSubmit();
    }
  };

  const handleClose = () => {
    setDraft(null);
    setValidationError(null);
  };

  const handleSubmit = () => {
    if (draft === null) return;

    const validation = validateCategory(draft, existingCategoryNames);
    if (validation.valid) {
      onCreate(draft);
      // Open a new draft row automatically
      setDraft(createDraftCategory());
      setValidationError(null);
    } else {
      setValidationError(validation.errors.name || null);
    }
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      handleSubmit();
    } else if (e.key === 'Escape') {
      e.preventDefault();
      handleClose();
    }
  };

  const updateField = <K extends keyof CategoryDraft>(
    field: K,
    value: CategoryDraft[K]
  ) => {
    if (draft === null) return;
    const updatedDraft = updateDraftCategory(draft, field, value);
    setDraft(updatedDraft);

    // Clear validation error when user changes the name
    if (field === 'name') {
      const validation = validateCategory(updatedDraft, existingCategoryNames);
      setValidationError(validation.errors.name || null);
    }
  };

  return (
    <div>
      <table className="table">
        <thead>
          <tr>
            <th>Name</th>
            <th>Type</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {orderedCategories.map((category, index) => {
            const isLastNewCategory =
              newCategoryIds.length > 0 &&
              index === orderedCategories.length - 1 &&
              newCategoryIdSet.has(category['db/id']);

            return (
              <tr
                key={category['db/id']}
                ref={isLastNewCategory ? lastNewCategoryRef : null}
              >
                <td>{category['category/name']}</td>
                <td>{category['category/type']}</td>
                <td>
                  <div className="button-group">
                    <button
                      className="button button-secondary"
                      onClick={() => onEdit(category)}
                    >
                      Edit
                    </button>
                    <button
                      className="button button-secondary"
                      onClick={() => onDelete(category)}
                    >
                      Delete
                    </button>
                  </div>
                </td>
              </tr>
            );
          })}
          {draft !== null && (
            <tr>
              <td>
                <div>
                  <input
                    ref={nameInputRef}
                    type="text"
                    className="form-input"
                    aria-label="Name"
                    value={draft.name}
                    onChange={(e) => updateField('name', e.target.value)}
                    onKeyDown={handleKeyDown}
                    style={{
                      outline: validationError ? '2px solid red' : undefined,
                    }}
                  />
                  {validationError && (
                    <div
                      style={{
                        color: 'red',
                        fontSize: '0.85rem',
                        marginTop: '0.25rem',
                      }}
                    >
                      {validationError}
                    </div>
                  )}
                </div>
              </td>
              <td>
                <select
                  className="form-select"
                  aria-label="Type"
                  value={draft.type}
                  onChange={(e) =>
                    updateField('type', e.target.value as 'expense' | 'income')
                  }
                  onKeyDown={(e) => {
                    if (e.key === 'Escape') {
                      e.preventDefault();
                      handleClose();
                    }
                  }}
                >
                  <option value="expense">expense</option>
                  <option value="income">income</option>
                </select>
              </td>
              <td>
                <button
                  className="button button-secondary"
                  onClick={handleClose}
                  aria-label="Close"
                  style={{ padding: '0.25rem 0.5rem' }}
                >
                  ×
                </button>
              </td>
            </tr>
          )}
        </tbody>
      </table>

      <div style={{ marginTop: '1rem' }}>
        <button className="button" onClick={handleAddClick}>
          + Add Category
        </button>
      </div>
    </div>
  );
}

import { useState, type KeyboardEvent, useEffect, useRef, useCallback } from 'react';
import type { Category } from '../lib/api';
import {
  createDraftCategory,
  updateDraftCategory,
  validateCategory,
  isDraftEmpty,
  type CategoryDraft,
} from '../lib/categoryDraft';
import { createDragDropManager } from '../lib/dragAndDrop';
import { EditIcon } from './icons/EditIcon';
import { DeleteIcon } from './icons/DeleteIcon';
import { DragIcon } from './icons/DragIcon';

interface CategoryTableProps {
  categories: Category[];
  newCategoryIds?: number[];
  onEdit: (category: Category) => void;
  onDelete: (category: Category) => void;
  onCreate: (draft: CategoryDraft) => void;
  onReorder?: (reorderedCategories: Category[]) => void;
}

export function CategoryTable({
  categories,
  newCategoryIds = [],
  onEdit,
  onDelete,
  onCreate,
  onReorder,
}: CategoryTableProps) {
  const [draft, setDraft] = useState<CategoryDraft | null>(null);
  const [validationError, setValidationError] = useState<string | null>(null);
  const nameInputRef = useRef<HTMLInputElement>(null);
  const lastNewCategoryRef = useRef<HTMLTableRowElement>(null);
  const prevCategoryCountRef = useRef(categories.length);

  // Drag-drop manager
  const dragDropManager = useRef(createDragDropManager<Category>()).current;
  const [dragState, setDragState] = useState(dragDropManager.getState());

  // Subscribe to drag-drop state changes
  useEffect(() => {
    return dragDropManager.subscribe(setDragState);
  }, [dragDropManager]);

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

  const handleDragStart = useCallback((index: number) => {
    dragDropManager.startDrag(index);
  }, [dragDropManager]);

  const handleDragOver = useCallback((e: React.DragEvent, index: number) => {
    e.preventDefault();
    dragDropManager.dragOver(index, orderedCategories, (reordered) => {
      if (onReorder) {
        onReorder(reordered);
      }
    });
  }, [dragDropManager, orderedCategories, onReorder]);

  const handleDragEnd = useCallback(() => {
    dragDropManager.endDrag();
  }, [dragDropManager]);

  return (
    <div>
      <table className="table">
        <thead>
          <tr>
            <th>Name</th>
            <th>Type</th>
            <th>Actions</th>
            <th style={{ width: '40px' }}></th>
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
                draggable
                onDragStart={() => handleDragStart(index)}
                onDragOver={(e) => handleDragOver(e, index)}
                onDragEnd={handleDragEnd}
                style={{
                  opacity: dragState.draggedIndex === index ? 0.5 : 1,
                  transition: 'opacity 0.2s ease',
                }}
              >
                <td>{category['category/name']}</td>
                <td>{category['category/type']}</td>
                <td>
                  <div className="button-group">
                    <button
                      className="button button-secondary"
                      onClick={() => onEdit(category)}
                      style={{ padding: '6px 12px', display: 'inline-flex', alignItems: 'center' }}
                      title="Edit"
                    >
                      <EditIcon size={16} />
                    </button>
                    <button
                      className="button button-secondary"
                      onClick={() => onDelete(category)}
                      style={{ padding: '6px 12px', display: 'inline-flex', alignItems: 'center' }}
                      title="Delete"
                    >
                      <DeleteIcon size={16} />
                    </button>
                  </div>
                </td>
                <td style={{ textAlign: 'center', cursor: 'move', color: '#999' }}>
                  <DragIcon size={20} />
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

import { useState, type KeyboardEvent, useEffect, useRef, useCallback } from 'react';
import type { Category } from '../lib/api';
import {
  createDraftCategory,
  updateDraftCategory,
  validateCategory,
  type CategoryDraft,
} from '../lib/categoryDraft';
import { CATEGORY_TYPE_OPTIONS, getCategoryTypeLabel, type CategoryType } from '../lib/categoryTypes';
import { orderCategoriesHierarchically } from '../lib/categoryHierarchy';
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
  onBulkAdd?: () => void;
}

export function CategoryTable({
  categories,
  newCategoryIds = [],
  onEdit,
  onDelete,
  onCreate,
  onReorder,
  onBulkAdd,
}: CategoryTableProps) {
  const [draft, setDraft] = useState<CategoryDraft | null>(null);
  const [validationError, setValidationError] = useState<string | null>(null);
  const nameInputRef = useRef<HTMLInputElement>(null);
  const lastNewCategoryRef = useRef<HTMLTableRowElement>(null);
  const prevCategoryCountRef = useRef(categories.length);

  // Drag-drop manager
  const dragDropManager = useRef(createDragDropManager<Category>()).current;
  const [dragState, setDragState] = useState(dragDropManager.getState());

  useEffect(() => {
    return dragDropManager.subscribe(setDragState);
  }, [dragDropManager]);

  // Render order: top-level categories with their children indented beneath.
  const displayNodes = orderCategoriesHierarchically(categories);
  const orderedCategories = displayNodes.map((node) => node.category);

  // Only persisted top-level categories can be parents: single-level hierarchy,
  // and an unsaved category (negative temp id) has no real id to reference yet.
  const parentOptions = displayNodes
    .filter((node) => node.depth === 0 && node.category['db/id'] > 0)
    .map((node) => node.category);

  const categoryById = new Map(categories.map((cat) => [cat['db/id'], cat]));
  const existingCategoryNames = categories.map((cat) => cat['category/name']);
  const lastNewId = newCategoryIds.length > 0 ? newCategoryIds[newCategoryIds.length - 1] : null;

  // Auto-focus and center the name input when a draft row appears.
  useEffect(() => {
    if (draft !== null && nameInputRef.current) {
      nameInputRef.current.focus();
      nameInputRef.current.scrollIntoView?.({ behavior: 'smooth', block: 'center' });
    }
  }, [draft]);

  // Scroll to the most recently added category when not mid-draft.
  useEffect(() => {
    if (categories.length > prevCategoryCountRef.current && draft === null) {
      lastNewCategoryRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'center' });
    }
    prevCategoryCountRef.current = categories.length;
  }, [categories.length, draft]);

  const handleAddClick = () => {
    if (draft === null) {
      setDraft(createDraftCategory());
      setValidationError(null);
    } else {
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
      // Reopen a draft carrying over type + parent so adding siblings is quick.
      setDraft({ ...createDraftCategory(), type: draft.type, parentId: draft.parentId });
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

  const updateField = <K extends keyof CategoryDraft>(field: K, value: CategoryDraft[K]) => {
    if (draft === null) return;
    const updatedDraft = updateDraftCategory(draft, field, value);
    setDraft(updatedDraft);

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
      onReorder?.(reordered);
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
            <th>Parent</th>
            <th>Actions</th>
            <th className="category-drag-col"></th>
          </tr>
        </thead>
        <tbody>
          {displayNodes.map(({ category, depth }, index) => {
            const parentRef = category['category/parent'];
            const parentName = parentRef
              ? categoryById.get(parentRef['db/id'])?.['category/name'] ?? '—'
              : '—';

            return (
              <tr
                key={category['db/id']}
                ref={category['db/id'] === lastNewId ? lastNewCategoryRef : null}
                className={dragState.draggedIndex === index ? 'category-row category-row--dragging' : 'category-row'}
                draggable
                onDragStart={() => handleDragStart(index)}
                onDragOver={(e) => handleDragOver(e, index)}
                onDragEnd={handleDragEnd}
              >
                <td>
                  <span className={depth > 0 ? 'category-name category-name--child' : 'category-name'}>
                    {category['category/name']}
                  </span>
                </td>
                <td>{getCategoryTypeLabel(category['category/type'])}</td>
                <td className="category-parent-cell">{parentName}</td>
                <td>
                  <div className="button-group">
                    <button
                      className="button button-secondary"
                      onClick={() => onEdit(category)}
                      title="Edit"
                    >
                      <EditIcon size={16} />
                    </button>
                    <button
                      className="button button-secondary"
                      onClick={() => onDelete(category)}
                      title="Delete"
                    >
                      <DeleteIcon size={16} />
                    </button>
                  </div>
                </td>
                <td className="category-drag-cell">
                  <DragIcon size={20} />
                </td>
              </tr>
            );
          })}
          {draft !== null && (
            <tr>
              <td>
                <input
                  ref={nameInputRef}
                  type="text"
                  className={`form-input${validationError ? ' category-draft-input--invalid' : ''}`}
                  aria-label="Name"
                  value={draft.name}
                  onChange={(e) => updateField('name', e.target.value)}
                  onKeyDown={handleKeyDown}
                />
                {validationError && <div className="category-draft-error">{validationError}</div>}
              </td>
              <td>
                <select
                  className="form-select"
                  aria-label="Type"
                  value={draft.type}
                  onChange={(e) => updateField('type', e.target.value as CategoryType)}
                  onKeyDown={(e) => {
                    if (e.key === 'Escape') {
                      e.preventDefault();
                      handleClose();
                    }
                  }}
                >
                  {CATEGORY_TYPE_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </td>
              <td>
                <select
                  className="form-select"
                  aria-label="Parent"
                  value={draft.parentId ?? ''}
                  onChange={(e) =>
                    updateField('parentId', e.target.value === '' ? null : Number(e.target.value))
                  }
                  onKeyDown={(e) => {
                    if (e.key === 'Escape') {
                      e.preventDefault();
                      handleClose();
                    }
                  }}
                >
                  <option value="">No parent</option>
                  {parentOptions.map((option) => (
                    <option key={option['db/id']} value={option['db/id']}>
                      {option['category/name']}
                    </option>
                  ))}
                </select>
              </td>
              <td>
                <button
                  className="button button-secondary"
                  onClick={handleClose}
                  aria-label="Close"
                >
                  ×
                </button>
              </td>
              <td></td>
            </tr>
          )}
        </tbody>
      </table>

      <div className="category-table-actions">
        <button className="button" onClick={handleAddClick}>
          + Add Category
        </button>
        {onBulkAdd && (
          <button className="button button-secondary" onClick={onBulkAdd}>
            Bulk Add
          </button>
        )}
      </div>
    </div>
  );
}

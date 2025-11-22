import { describe, it, expect } from 'vitest';
import {
  createDraftCategory,
  updateDraftCategory,
  validateCategory,
  isDraftEmpty,
  type CategoryDraft,
} from '../../app/lib/categoryDraft';

describe('Category Draft Logic', () => {
  describe('createDraftCategory', () => {
    it('creates an empty draft with default values', () => {
      const draft = createDraftCategory();

      expect(draft).toEqual({
        name: '',
        type: 'expense',
      });
    });
  });

  describe('updateDraftCategory', () => {
    it('updates the name field', () => {
      const draft = createDraftCategory();
      const updated = updateDraftCategory(draft, 'name', 'Groceries');

      expect(updated.name).toBe('Groceries');
      expect(updated.type).toBe('expense');
    });

    it('updates the type field', () => {
      const draft = createDraftCategory();
      const updated = updateDraftCategory(draft, 'type', 'income');

      expect(updated.name).toBe('');
      expect(updated.type).toBe('income');
    });

    it('does not mutate the original draft', () => {
      const draft = createDraftCategory();
      const updated = updateDraftCategory(draft, 'name', 'Groceries');

      expect(draft.name).toBe('');
      expect(updated.name).toBe('Groceries');
    });

    it('allows chaining updates', () => {
      const draft = createDraftCategory();
      const updated = updateDraftCategory(
        updateDraftCategory(draft, 'name', 'Salary'),
        'type',
        'income'
      );

      expect(updated.name).toBe('Salary');
      expect(updated.type).toBe('income');
    });
  });

  describe('isDraftEmpty', () => {
    it('returns true for a new draft', () => {
      const draft = createDraftCategory();
      expect(isDraftEmpty(draft)).toBe(true);
    });

    it('returns true for a draft with only whitespace name', () => {
      const draft = updateDraftCategory(createDraftCategory(), 'name', '   ');
      expect(isDraftEmpty(draft)).toBe(true);
    });

    it('returns false for a draft with a name', () => {
      const draft = updateDraftCategory(createDraftCategory(), 'name', 'Groceries');
      expect(isDraftEmpty(draft)).toBe(false);
    });

    it('returns false even if type changed', () => {
      const draft = updateDraftCategory(
        updateDraftCategory(createDraftCategory(), 'name', 'Salary'),
        'type',
        'income'
      );
      expect(isDraftEmpty(draft)).toBe(false);
    });
  });

  describe('validateCategory', () => {
    it('returns valid for a draft with a name', () => {
      const draft = updateDraftCategory(createDraftCategory(), 'name', 'Groceries');
      const result = validateCategory(draft);

      expect(result.valid).toBe(true);
      expect(result.errors).toEqual({});
    });

    it('returns invalid for an empty draft', () => {
      const draft = createDraftCategory();
      const result = validateCategory(draft);

      expect(result.valid).toBe(false);
      expect(result.errors.name).toBe('Name is required');
    });

    it('returns invalid for a draft with only whitespace', () => {
      const draft = updateDraftCategory(createDraftCategory(), 'name', '   ');
      const result = validateCategory(draft);

      expect(result.valid).toBe(false);
      expect(result.errors.name).toBe('Name is required');
    });

    it('returns valid for a draft with name and income type', () => {
      const draft = updateDraftCategory(
        updateDraftCategory(createDraftCategory(), 'name', 'Salary'),
        'type',
        'income'
      );
      const result = validateCategory(draft);

      expect(result.valid).toBe(true);
      expect(result.errors).toEqual({});
    });

    it('returns invalid for a duplicate name', () => {
      const draft = updateDraftCategory(createDraftCategory(), 'name', 'Groceries');
      const existingNames = ['Groceries', 'Salary'];
      const result = validateCategory(draft, existingNames);

      expect(result.valid).toBe(false);
      expect(result.errors.name).toBe('Category already exists');
    });

    it('returns invalid for a duplicate name with different case', () => {
      const draft = updateDraftCategory(createDraftCategory(), 'name', 'GROCERIES');
      const existingNames = ['Groceries', 'Salary'];
      const result = validateCategory(draft, existingNames);

      expect(result.valid).toBe(false);
      expect(result.errors.name).toBe('Category already exists');
    });

    it('returns valid for a unique name', () => {
      const draft = updateDraftCategory(createDraftCategory(), 'name', 'Utilities');
      const existingNames = ['Groceries', 'Salary'];
      const result = validateCategory(draft, existingNames);

      expect(result.valid).toBe(true);
      expect(result.errors).toEqual({});
    });
  });
});

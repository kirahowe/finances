export interface CategoryDraft {
  name: string;
  type: 'expense' | 'income';
}

export interface ValidationResult {
  valid: boolean;
  errors: Record<string, string>;
}

/**
 * Creates a new empty category draft with default values
 */
export function createDraftCategory(): CategoryDraft {
  return {
    name: '',
    type: 'expense',
  };
}

/**
 * Updates a field in the category draft without mutating the original
 */
export function updateDraftCategory<K extends keyof CategoryDraft>(
  draft: CategoryDraft,
  field: K,
  value: CategoryDraft[K]
): CategoryDraft {
  return {
    ...draft,
    [field]: value,
  };
}

/**
 * Checks if a category draft is empty (no meaningful data entered)
 */
export function isDraftEmpty(draft: CategoryDraft): boolean {
  return draft.name.trim() === '';
}

/**
 * Validates a category draft and returns validation result
 */
export function validateCategory(
  draft: CategoryDraft,
  existingNames: string[] = []
): ValidationResult {
  const errors: Record<string, string> = {};

  if (draft.name.trim() === '') {
    errors.name = 'Name is required';
  } else if (isDuplicateName(draft.name, existingNames)) {
    errors.name = 'Category already exists';
  }

  return {
    valid: Object.keys(errors).length === 0,
    errors,
  };
}

/**
 * Checks if a name is a duplicate (case-insensitive)
 */
export function isDuplicateName(name: string, existingNames: string[]): boolean {
  const normalizedName = name.trim().toLowerCase();
  return existingNames.some(
    (existing) => existing.trim().toLowerCase() === normalizedName
  );
}

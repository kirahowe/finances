/**
 * Category type definitions and utilities
 */

export const CATEGORY_TYPES = ['expense', 'income', 'transfer'] as const;

export type CategoryType = typeof CATEGORY_TYPES[number];

export interface CategoryTypeOption {
  value: CategoryType;
  label: string;
}

export const CATEGORY_TYPE_LABELS: Record<CategoryType, string> = {
  expense: 'Expense',
  income: 'Income',
  transfer: 'Transfer'
};

export const CATEGORY_TYPE_OPTIONS: CategoryTypeOption[] = [
  { value: 'expense', label: 'Expense' },
  { value: 'income', label: 'Income' },
  { value: 'transfer', label: 'Transfer' }
];

export function getCategoryTypeLabel(type: CategoryType): string {
  return CATEGORY_TYPE_LABELS[type] ?? type;
}

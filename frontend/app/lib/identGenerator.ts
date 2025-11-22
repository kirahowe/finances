/**
 * Generates a category identifier from a category name.
 * Converts name to lowercase, replaces spaces with hyphens, removes special characters.
 * Example: "Dining Out" -> "category/dining-out"
 */
export function generateCategoryIdent(name: string): string {
  const normalized = name
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9\s]/g, '') // Remove special characters
    .replace(/\s+/g, '-'); // Replace spaces (including multiple) with hyphens

  return `category/${normalized}`;
}

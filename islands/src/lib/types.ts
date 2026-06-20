// Minimal, zod-free domain types for the islands.
//
// Mirrors the shapes the backend pulls (see backend data/schema.clj). Kept as
// plain interfaces so the islands carry no validation runtime — the server is
// authoritative and the island only reads what the SSR markup hands it.

export type CategoryType = 'expense' | 'income' | 'transfer';

export interface Category {
  'db/id': number;
  'category/name': string;
  'category/type': CategoryType;
  'category/ident'?: string;
  'category/sort-order'?: number;
  // Pulled refs come back as a bare entity reference: { 'db/id': number }.
  'category/parent'?: { 'db/id': number } | null;
}

import { z } from 'zod';

// API Base URL - defaulting to localhost:8080
const API_BASE = typeof window !== 'undefined'
  ? window.location.protocol + '//' + window.location.hostname + ':8080'
  : 'http://localhost:8080';

// Zod Schemas
const CategorySchema = z.object({
  'db/id': z.number(),
  'category/name': z.string(),
  'category/type': z.enum(['expense', 'income']),
  'category/ident': z.string().optional(),
});

const TransactionSchema = z.object({
  'db/id': z.number(),
  'transaction/amount': z.number(),
  'transaction/payee': z.string(),
  'transaction/description': z.string().nullable().optional(),
  'transaction/posted-date': z.string(),
  'transaction/category': CategorySchema.nullable().optional(),
});

const AccountSchema = z.object({
  'db/id': z.number(),
  'account/name': z.string(),
  'account/type': z.string(),
  'account/currency': z.string(),
  'account/external-id': z.string().optional(),
});

const StatsSchema = z.object({
  institutions: z.number(),
  accounts: z.number(),
  transactions: z.number(),
});

// Type exports
export type Category = z.infer<typeof CategorySchema>;
export type Transaction = z.infer<typeof TransactionSchema>;
export type Account = z.infer<typeof AccountSchema>;
export type Stats = z.infer<typeof StatsSchema>;

// API Response wrapper
const ApiResponseSchema = <T extends z.ZodTypeAny>(dataSchema: T) =>
  z.object({
    success: z.boolean(),
    data: dataSchema,
  });

// API client
export const api = {
  async getStats(): Promise<Stats> {
    const response = await fetch(`${API_BASE}/api/stats`);
    const json = await response.json();
    const result = ApiResponseSchema(StatsSchema).parse(json);
    return result.data;
  },

  async getCategories(): Promise<Category[]> {
    const response = await fetch(`${API_BASE}/api/categories`);
    const json = await response.json();
    const result = ApiResponseSchema(z.array(CategorySchema)).parse(json);
    return result.data;
  },

  async createCategory(data: { name: string; type: 'expense' | 'income'; ident?: string }): Promise<Category> {
    const response = await fetch(`${API_BASE}/api/categories`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    });
    const json = await response.json();
    const result = ApiResponseSchema(CategorySchema).parse(json);
    return result.data;
  },

  async updateCategory(id: number, data: { name: string; type: 'expense' | 'income'; ident?: string }): Promise<Category> {
    const response = await fetch(`${API_BASE}/api/categories/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    });
    const json = await response.json();
    const result = ApiResponseSchema(CategorySchema).parse(json);
    return result.data;
  },

  async deleteCategory(id: number): Promise<void> {
    const response = await fetch(`${API_BASE}/api/categories/${id}`, {
      method: 'DELETE',
    });
    if (!response.ok) {
      throw new Error('Failed to delete category');
    }
  },

  async getAccounts(): Promise<Account[]> {
    const response = await fetch(`${API_BASE}/api/accounts`);
    const json = await response.json();
    const result = ApiResponseSchema(z.array(AccountSchema)).parse(json);
    return result.data;
  },

  async getTransactions(): Promise<Transaction[]> {
    const response = await fetch(`${API_BASE}/api/transactions`);
    const json = await response.json();
    const result = ApiResponseSchema(z.array(TransactionSchema)).parse(json);
    return result.data;
  },

  async updateTransactionCategory(transactionId: number, categoryId: number | null): Promise<Transaction> {
    const response = await fetch(`${API_BASE}/api/transactions/${transactionId}/category`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ categoryId }),
    });
    const json = await response.json();
    const result = ApiResponseSchema(TransactionSchema).parse(json);
    return result.data;
  },
};

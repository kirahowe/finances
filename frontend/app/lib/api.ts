import { z } from 'zod';
import { CATEGORY_TYPES, type CategoryType } from './categoryTypes';

// API Base URL - defaulting to localhost:8080
const API_BASE = typeof window !== 'undefined'
  ? window.location.protocol + '//' + window.location.hostname + ':8080'
  : 'http://localhost:8080';

// Zod Schemas

// Full category schema (from /api/categories)
const CategorySchema = z.object({
  'db/id': z.number(),
  'category/name': z.string(),
  'category/type': z.enum(CATEGORY_TYPES),
  'category/ident': z.string().optional(),
  'category/sort-order': z.number().optional(),
});

// Minimal category reference (nested in transactions)
const CategoryRefSchema = z.object({
  'db/id': z.number(),
  'category/name': z.string(),
});

// Full account schema (from /api/accounts)
const AccountSchema = z.object({
  'db/id': z.number(),
  'account/external-name': z.string(),
  'account/type': z.string().optional(),
  'account/currency': z.string(),
  'account/external-id': z.string().optional(),
});

// Minimal account reference (nested in transactions)
const AccountRefSchema = z.object({
  'db/id': z.number(),
  'account/external-name': z.string(),
});

const TransactionSchema = z.object({
  'db/id': z.number(),
  'transaction/amount': z.number(),
  'transaction/payee': z.string(),
  'transaction/description': z.string().nullable().optional(),
  'transaction/posted-date': z.string(),
  'transaction/category': CategoryRefSchema.nullable().optional(),
  'transaction/account': AccountRefSchema.nullable().optional(),
});

const StatsSchema = z.object({
  institutions: z.number(),
  accounts: z.number(),
  transactions: z.number(),
});

// Plaid schemas
const PlaidLinkTokenSchema = z.object({
  linkToken: z.string(),
});

const PlaidExchangeResponseSchema = z.object({
  access_token: z.string(),
  item_id: z.string(),
});

const PlaidAccountSchema = z.unknown(); // Keep it flexible for raw Plaid response
const PlaidTransactionSchema = z.unknown(); // Keep it flexible for raw Plaid response

// Type exports
export type Category = z.infer<typeof CategorySchema>;
export type Transaction = z.infer<typeof TransactionSchema>;
export type Account = z.infer<typeof AccountSchema>;
export type Stats = z.infer<typeof StatsSchema>;
export type PlaidLinkToken = z.infer<typeof PlaidLinkTokenSchema>;
export type PlaidExchangeResponse = z.infer<typeof PlaidExchangeResponseSchema>;

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

  async createCategory(data: { name: string; type: CategoryType; ident?: string }): Promise<Category> {
    const response = await fetch(`${API_BASE}/api/categories`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    });
    const json = await response.json();
    const result = ApiResponseSchema(CategorySchema).parse(json);
    return result.data;
  },

  async updateCategory(id: number, data: { name: string; type: CategoryType; ident?: string }): Promise<Category> {
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

  async batchUpdateCategorySortOrders(updates: Array<{ id: number; sortOrder: number }>): Promise<Category[]> {
    const response = await fetch(`${API_BASE}/api/categories/batch-sort`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ updates }),
    });
    const json = await response.json();
    const result = ApiResponseSchema(z.array(CategorySchema)).parse(json);
    return result.data;
  },

  // Plaid API functions
  async createPlaidLinkToken(): Promise<PlaidLinkToken> {
    const response = await fetch(`${API_BASE}/api/plaid/create-link-token`, {
      method: 'POST',
    });
    const json = await response.json();

    // Check for error response
    if (!response.ok || !json.success) {
      throw new Error(json.error || `HTTP ${response.status}: ${response.statusText}`);
    }

    const result = ApiResponseSchema(PlaidLinkTokenSchema).parse(json);
    return result.data;
  },

  async exchangePlaidToken(publicToken: string): Promise<PlaidExchangeResponse> {
    const response = await fetch(`${API_BASE}/api/plaid/exchange-token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ publicToken }),
    });
    const json = await response.json();

    // Check for error response
    if (!response.ok || !json.success) {
      throw new Error(json.error || `HTTP ${response.status}: ${response.statusText}`);
    }

    const result = ApiResponseSchema(PlaidExchangeResponseSchema).parse(json);
    return result.data;
  },

  async getPlaidAccounts(): Promise<unknown> {
    const response = await fetch(`${API_BASE}/api/plaid/accounts`);
    const json = await response.json();

    // Check for error response
    if (!response.ok || !json.success) {
      throw new Error(json.error || `HTTP ${response.status}: ${response.statusText}`);
    }

    const result = ApiResponseSchema(z.array(PlaidAccountSchema)).parse(json);
    return result.data;
  },

  async getPlaidTransactions(startDate: string, endDate: string): Promise<unknown> {
    const response = await fetch(`${API_BASE}/api/plaid/transactions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ startDate, endDate }),
    });
    const json = await response.json();

    // Check for error response
    if (!response.ok || !json.success) {
      throw new Error(json.error || `HTTP ${response.status}: ${response.statusText}`);
    }

    const result = ApiResponseSchema(z.array(PlaidTransactionSchema)).parse(json);
    return result.data;
  },
};

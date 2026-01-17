import { z } from 'zod';
import { CATEGORY_TYPES, type CategoryType } from './categoryTypes';
import { routes } from './routes';

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

// Institution reference (nested in accounts)
const InstitutionRefSchema = z.object({
  'db/id': z.number(),
  'institution/name': z.string(),
});

// Full account schema (from /api/accounts)
const AccountSchema = z.object({
  'db/id': z.number(),
  'account/external-name': z.string(),
  'account/type': z.string().optional(),
  'account/currency': z.string(),
  'account/external-id': z.string().optional(),
  'account/plaid-type': z.string().optional(),
  'account/plaid-subtype': z.string().optional(),
  'account/mask': z.string().optional(),
  'account/item-id': z.string().optional(),
  'account/institution': InstitutionRefSchema.optional(),
  'account/source': z.enum(['plaid', 'manual']).optional(),
  'account/csv-mapping': z.string().optional(),
  'account/invert-amount': z.boolean().optional(),
});

// Minimal account reference (nested in transactions)
const AccountRefSchema = z.object({
  'db/id': z.number(),
  'account/external-name': z.string(),
  'account/institution': InstitutionRefSchema.optional(),
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
  institution_name: z.string().optional(),
});

const PlaidAccountSchema = z.unknown(); // Keep it flexible for raw Plaid response
const PlaidTransactionSchema = z.unknown(); // Keep it flexible for raw Plaid response

const PlaidSyncAccountsResponseSchema = z.object({
  success: z.object({
    institutions: z.number(),
    accounts: z.number(),
  }),
  failed: z.object({
    institutions: z.number(),
    accounts: z.number(),
  }),
  errors: z.array(z.object({
    'account-id': z.string().optional(),
    message: z.string(),
  })),
});

const PlaidSyncTransactionsResponseSchema = z.object({
  success: z.object({
    transactions: z.number(),
  }),
  failed: z.object({
    transactions: z.number(),
  }),
  errors: z.array(z.object({
    'transaction-id': z.string().optional(),
    message: z.string(),
  })),
});

// Plaid Item schema (for list-items endpoint)
const PlaidItemSchema = z.object({
  'item-id': z.string(),
  'institution-name': z.string(),
  'created-at': z.string().optional(),
});

// Plaid sync status schema (for polling after linking)
const PlaidSyncStatusSchema = z.object({
  'item-id': z.string(),
  'institution-name': z.string(),
  'sync-status': z.enum(['pending', 'syncing', 'synced', 'failed']),
  'has-cursor': z.boolean(),
  'transaction-count': z.number(),
  'last-sync-at': z.string().nullable().optional(),
  'ready-for-display': z.boolean(),
  'error': z.string().nullable().optional(),
});

// CSV Import schemas
const CsvMappingSchema = z.object({
  columns: z.object({
    date: z.string(),
    amount: z.string(),
    payee: z.string(),
    description: z.string().optional(),
  }),
  'date-format': z.string(),
});

const CsvPreviewSchema = z.object({
  headers: z.array(z.string()),
  'sample-rows': z.array(z.record(z.string(), z.string())),
  'total-rows': z.number(),
  'detected-mapping': z.object({
    date: z.string().optional(),
    amount: z.string().optional(),
    payee: z.string().optional(),
    description: z.string().optional(),
  }).optional(),
  'suggested-date-format': z.string().optional(),
  'stored-mapping': CsvMappingSchema.optional(),
});

const CsvImportResultSchema = z.object({
  imported: z.number(),
  'skipped-duplicates': z.number(),
  errors: z.array(z.string()),
});

// Type exports
export type Category = z.infer<typeof CategorySchema>;
export type Transaction = z.infer<typeof TransactionSchema>;
export type Account = z.infer<typeof AccountSchema>;
export type Stats = z.infer<typeof StatsSchema>;
export type PlaidLinkToken = z.infer<typeof PlaidLinkTokenSchema>;
export type PlaidExchangeResponse = z.infer<typeof PlaidExchangeResponseSchema>;
export type PlaidSyncAccountsResponse = z.infer<typeof PlaidSyncAccountsResponseSchema>;
export type PlaidSyncTransactionsResponse = z.infer<typeof PlaidSyncTransactionsResponseSchema>;
export type PlaidItem = z.infer<typeof PlaidItemSchema>;
export type PlaidSyncStatus = z.infer<typeof PlaidSyncStatusSchema>;
export type InstitutionRef = z.infer<typeof InstitutionRefSchema>;
export type CsvMapping = z.infer<typeof CsvMappingSchema>;
export type CsvPreview = z.infer<typeof CsvPreviewSchema>;
export type CsvImportResult = z.infer<typeof CsvImportResultSchema>;

// API Response wrapper
const ApiResponseSchema = <T extends z.ZodTypeAny>(dataSchema: T) =>
  z.object({
    success: z.boolean(),
    data: dataSchema,
  });

// API client
export const api = {
  async getStats(): Promise<Stats> {
    const response = await fetch(routes.stats());
    const json = await response.json();
    const result = ApiResponseSchema(StatsSchema).parse(json);
    return result.data;
  },

  async getCategories(): Promise<Category[]> {
    const response = await fetch(routes.categories.list());
    const json = await response.json();
    const result = ApiResponseSchema(z.array(CategorySchema)).parse(json);
    return result.data;
  },

  async createCategory(data: { name: string; type: CategoryType; ident?: string }): Promise<Category> {
    const response = await fetch(routes.categories.create(), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    });
    const json = await response.json();
    const result = ApiResponseSchema(CategorySchema).parse(json);
    return result.data;
  },

  async updateCategory(id: number, data: { name: string; type: CategoryType; ident?: string }): Promise<Category> {
    const response = await fetch(routes.categories.update(id), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    });
    const json = await response.json();
    const result = ApiResponseSchema(CategorySchema).parse(json);
    return result.data;
  },

  async deleteCategory(id: number): Promise<void> {
    const response = await fetch(routes.categories.delete(id), {
      method: 'DELETE',
    });
    if (!response.ok) {
      throw new Error('Failed to delete category');
    }
  },

  async getAccounts(): Promise<Account[]> {
    const response = await fetch(routes.accounts.list());
    const json = await response.json();
    const result = ApiResponseSchema(z.array(AccountSchema)).parse(json);
    return result.data;
  },

  async createAccount(data: { name: string; currency?: string; institutionName: string }): Promise<Account> {
    const response = await fetch(routes.accounts.create(), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    });
    const json = await response.json();

    if (!response.ok || !json.success) {
      throw new Error(json.error || `HTTP ${response.status}: ${response.statusText}`);
    }

    const result = ApiResponseSchema(AccountSchema).parse(json);
    return result.data;
  },

  async getAccount(id: number): Promise<Account> {
    const response = await fetch(routes.accounts.get(id));
    const json = await response.json();

    if (!response.ok || !json.success) {
      throw new Error(json.error || `HTTP ${response.status}: ${response.statusText}`);
    }

    const result = ApiResponseSchema(AccountSchema).parse(json);
    return result.data;
  },

  async deleteAccount(id: number): Promise<void> {
    const response = await fetch(routes.accounts.delete(id), {
      method: 'DELETE',
    });

    if (!response.ok) {
      const json = await response.json();
      throw new Error(json.error || 'Failed to delete account');
    }
  },

  async updateAccountSettings(id: number, settings: { invertAmount?: boolean }): Promise<Account> {
    const response = await fetch(routes.accounts.settings(id), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(settings),
    });
    const json = await response.json();

    if (!response.ok || !json.success) {
      throw new Error(json.error || `HTTP ${response.status}: ${response.statusText}`);
    }

    const result = ApiResponseSchema(AccountSchema).parse(json);
    return result.data;
  },

  // CSV Import API functions
  async getCsvMapping(accountId: number): Promise<CsvMapping | null> {
    const response = await fetch(routes.csv.mapping(accountId));
    const json = await response.json();

    if (!response.ok || !json.success) {
      throw new Error(json.error || `HTTP ${response.status}: ${response.statusText}`);
    }

    const result = ApiResponseSchema(CsvMappingSchema.nullable()).parse(json);
    return result.data;
  },

  async saveCsvMapping(accountId: number, mapping: CsvMapping): Promise<{ success: boolean }> {
    const response = await fetch(routes.csv.mapping(accountId), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        columns: mapping.columns,
        dateFormat: mapping['date-format'],
      }),
    });
    const json = await response.json();

    if (!response.ok || !json.success) {
      throw new Error(json.error || `HTTP ${response.status}: ${response.statusText}`);
    }

    return json.data;
  },

  async previewCsv(accountId: number, csvContent: string): Promise<CsvPreview> {
    const response = await fetch(routes.csv.preview(accountId), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ csvContent }),
    });
    const json = await response.json();

    if (!response.ok || !json.success) {
      throw new Error(json.error || `HTTP ${response.status}: ${response.statusText}`);
    }

    const result = ApiResponseSchema(CsvPreviewSchema).parse(json);
    return result.data;
  },

  async importCsv(accountId: number, csvContent: string, mapping: CsvMapping): Promise<CsvImportResult> {
    const response = await fetch(routes.csv.import(accountId), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        csvContent,
        mapping: {
          columns: mapping.columns,
          'date-format': mapping['date-format'],
        },
      }),
    });
    const json = await response.json();

    if (!response.ok || !json.success) {
      throw new Error(json.error || `HTTP ${response.status}: ${response.statusText}`);
    }

    const result = ApiResponseSchema(CsvImportResultSchema).parse(json);
    return result.data;
  },

  async getTransactions(opts?: { month?: string }): Promise<Transaction[]> {
    const response = await fetch(routes.transactions.list(opts));
    const json = await response.json();
    const result = ApiResponseSchema(z.array(TransactionSchema)).parse(json);
    return result.data;
  },

  async updateTransactionCategory(transactionId: number, categoryId: number | null): Promise<Transaction> {
    const response = await fetch(routes.transactions.updateCategory(transactionId), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ categoryId }),
    });
    const json = await response.json();
    const result = ApiResponseSchema(TransactionSchema).parse(json);
    return result.data;
  },

  async batchUpdateCategorySortOrders(updates: Array<{ id: number; sortOrder: number }>): Promise<Category[]> {
    const response = await fetch(routes.categories.batchSort(), {
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
    const response = await fetch(routes.plaid.createLinkToken(), {
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

  async exchangePlaidToken(publicToken: string, accountIds?: string[]): Promise<PlaidExchangeResponse> {
    const response = await fetch(routes.plaid.exchangeToken(), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ publicToken, accountIds }),
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
    const response = await fetch(routes.plaid.accounts());
    const json = await response.json();

    // Check for error response
    if (!response.ok || !json.success) {
      throw new Error(json.error || `HTTP ${response.status}: ${response.statusText}`);
    }

    const result = ApiResponseSchema(z.array(PlaidAccountSchema)).parse(json);
    return result.data;
  },

  async getPlaidTransactions(startDate: string, endDate: string): Promise<unknown> {
    const response = await fetch(routes.plaid.transactions(), {
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

  async syncPlaidAccounts(): Promise<PlaidSyncAccountsResponse> {
    const response = await fetch(routes.plaid.syncAccounts(), {
      method: 'POST',
    });
    const json = await response.json();

    // Check for error response
    if (!response.ok || !json.success) {
      throw new Error(json.error || `HTTP ${response.status}: ${response.statusText}`);
    }

    const result = ApiResponseSchema(PlaidSyncAccountsResponseSchema).parse(json);
    return result.data;
  },

  async syncPlaidTransactions(opts?: { months?: number }): Promise<PlaidSyncTransactionsResponse> {
    const response = await fetch(routes.plaid.syncTransactions(), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ months: opts?.months || 6 }),
    });
    const json = await response.json();

    // Check for error response
    if (!response.ok || !json.success) {
      throw new Error(json.error || `HTTP ${response.status}: ${response.statusText}`);
    }

    const result = ApiResponseSchema(PlaidSyncTransactionsResponseSchema).parse(json);
    return result.data;
  },

  async syncPlaidMonthTransactions(month: string): Promise<PlaidSyncTransactionsResponse> {
    const response = await fetch(routes.plaid.syncMonthTransactions(), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ month }),
    });
    const json = await response.json();

    // Check for error response
    if (!response.ok || !json.success) {
      throw new Error(json.error || `HTTP ${response.status}: ${response.statusText}`);
    }

    const result = ApiResponseSchema(PlaidSyncTransactionsResponseSchema).parse(json);
    return result.data;
  },

  async listPlaidItems(): Promise<PlaidItem[]> {
    const response = await fetch(routes.plaid.items.list());
    const json = await response.json();

    // Check for error response
    if (!response.ok || !json.success) {
      throw new Error(json.error || `HTTP ${response.status}: ${response.statusText}`);
    }

    const result = ApiResponseSchema(z.array(PlaidItemSchema)).parse(json);
    return result.data;
  },

  async deletePlaidItem(itemId: string): Promise<{ deleted: boolean }> {
    const response = await fetch(routes.plaid.items.delete(itemId), {
      method: 'DELETE',
    });
    const json = await response.json();

    // Check for error response
    if (!response.ok || !json.success) {
      throw new Error(json.error || `HTTP ${response.status}: ${response.statusText}`);
    }

    return json.data;
  },

  async getSyncStatus(itemId: string): Promise<PlaidSyncStatus> {
    const response = await fetch(routes.plaid.items.syncStatus(itemId));
    const json = await response.json();

    // Check for error response
    if (!response.ok || !json.success) {
      throw new Error(json.error || `HTTP ${response.status}: ${response.statusText}`);
    }

    const result = ApiResponseSchema(PlaidSyncStatusSchema).parse(json);
    return result.data;
  },

  async triggerSync(itemId: string): Promise<PlaidSyncTransactionsResponse> {
    const response = await fetch(routes.plaid.items.triggerSync(itemId), {
      method: 'POST',
    });
    const json = await response.json();

    // Check for error response
    if (!response.ok || !json.success) {
      throw new Error(json.error || `HTTP ${response.status}: ${response.statusText}`);
    }

    const result = ApiResponseSchema(PlaidSyncTransactionsResponseSchema).parse(json);
    return result.data;
  },

  async resetSync(itemId: string): Promise<{ reset: boolean; itemId: string; message: string }> {
    const response = await fetch(routes.plaid.items.resetSync(itemId), {
      method: 'POST',
    });
    const json = await response.json();

    // Check for error response
    if (!response.ok || !json.success) {
      throw new Error(json.error || `HTTP ${response.status}: ${response.statusText}`);
    }

    return json.data;
  },
};

/**
 * API Route Helpers
 *
 * Centralized route definitions for the backend API.
 * Provides type-safe URL generation with proper encoding.
 */

/**
 * Get the API base URL.
 *
 * - Browser: Uses same hostname as current page with port 8080
 * - SSR: Uses localhost:8080 (assumes backend runs on same machine)
 */
function getApiBase(): string {
  if (typeof window !== 'undefined') {
    const { protocol, hostname, port } = window.location;
    // The e2e suite serves the frontend on :5174 against the seeded backend on
    // :8081; normal dev/prod talk to the backend on :8080.
    const apiPort = port === '5174' ? '8081' : '8080';
    return `${protocol}//${hostname}:${apiPort}`;
  }
  // SSR (Node): honor an explicit override (used by the e2e dev server).
  if (typeof process !== 'undefined' && process.env.VITE_API_BASE) {
    return process.env.VITE_API_BASE;
  }
  return 'http://localhost:8080';
}

/**
 * Build a URL with the API base and path segments.
 * Automatically encodes path segments.
 */
function buildUrl(...segments: (string | number)[]): string {
  const path = segments
    .map(s => encodeURIComponent(String(s)))
    .join('/');
  return `${getApiBase()}/${path}`;
}

/**
 * Build a URL with query parameters.
 */
function buildUrlWithParams(
  segments: (string | number)[],
  params?: Record<string, string | number | boolean | undefined>
): string {
  const base = buildUrl(...segments);
  if (!params) return base;

  const url = new URL(base);
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) {
      url.searchParams.set(key, String(value));
    }
  }
  return url.toString();
}

// ============================================================================
// Route Definitions
// ============================================================================

export const routes = {
  // Stats
  stats: () => buildUrl('api', 'stats'),

  // Categories
  categories: {
    list: () => buildUrl('api', 'categories'),
    create: () => buildUrl('api', 'categories'),
    update: (id: number) => buildUrl('api', 'categories', id),
    delete: (id: number) => buildUrl('api', 'categories', id),
    batchSort: () => buildUrl('api', 'categories', 'batch-sort'),
    bulk: () => buildUrl('api', 'categories', 'bulk'),
  },

  // Accounts
  accounts: {
    list: () => buildUrl('api', 'accounts'),
    create: () => buildUrl('api', 'accounts'),
    get: (id: number) => buildUrl('api', 'accounts', id),
    delete: (id: number) => buildUrl('api', 'accounts', id),
    settings: (id: number) => buildUrl('api', 'accounts', id, 'settings'),
  },

  // CSV Import
  csv: {
    mapping: (accountId: number) => buildUrl('api', 'csv', 'mapping', accountId),
    preview: (accountId: number) => buildUrl('api', 'csv', 'preview', accountId),
    import: (accountId: number) => buildUrl('api', 'csv', 'import', accountId),
  },

  // Transactions
  transactions: {
    list: (params?: { month?: string }) =>
      buildUrlWithParams(['api', 'transactions'], params),
    updateCategory: (transactionId: number) =>
      buildUrl('api', 'transactions', transactionId, 'category'),
    setSplits: (transactionId: number) =>
      buildUrl('api', 'transactions', transactionId, 'splits'),
  },

  // Transfer matching
  transfers: {
    suggestions: () => buildUrl('api', 'transfers', 'suggestions'),
    confirm: () => buildUrl('api', 'transfers'),
    unmatch: (transactionId: number) => buildUrl('api', 'transfers', transactionId),
    reject: () => buildUrl('api', 'transfers', 'reject'),
    candidates: (transactionId: number) =>
      buildUrlWithParams(['api', 'transfers', 'candidates'], { transactionId }),
  },

  // Generic providers (secrets-based, e.g. Lunchflow)
  providers: {
    sync: (provider: string) => buildUrl('api', 'providers', provider, 'sync'),
    availableAccounts: (provider: string) =>
      buildUrl('api', 'providers', provider, 'available-accounts'),
  },

  // Plaid
  plaid: {
    createLinkToken: () => buildUrl('api', 'plaid', 'create-link-token'),
    exchangeToken: () => buildUrl('api', 'plaid', 'exchange-token'),
    accounts: () => buildUrl('api', 'plaid', 'accounts'),
    transactions: () => buildUrl('api', 'plaid', 'transactions'),
    syncAccounts: () => buildUrl('api', 'plaid', 'sync-accounts'),
    syncTransactions: () => buildUrl('api', 'plaid', 'sync-transactions'),
    syncMonthTransactions: () => buildUrl('api', 'plaid', 'sync-month-transactions'),

    // Items (bank connections)
    items: {
      list: () => buildUrl('api', 'plaid', 'items'),
      delete: (itemId: string) => buildUrl('api', 'plaid', 'items', itemId),
      syncStatus: (itemId: string) => buildUrl('api', 'plaid', 'items', itemId, 'sync-status'),
      triggerSync: (itemId: string) => buildUrl('api', 'plaid', 'items', itemId, 'sync'),
      resetSync: (itemId: string) => buildUrl('api', 'plaid', 'items', itemId, 'reset-sync'),
    },
  },
} as const;

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { api } from './api';

describe('Plaid API Client', () => {
  // Save original fetch
  const originalFetch = global.fetch;

  beforeEach(() => {
    // Reset mocks before each test
    vi.resetAllMocks();
  });

  afterEach(() => {
    // Restore original fetch
    global.fetch = originalFetch;
  });

  describe('createPlaidLinkToken', () => {
    it('should return link token on success', async () => {
      const mockLinkToken = 'link-sandbox-test-token';
      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({
          success: true,
          data: { linkToken: mockLinkToken },
        }),
      });

      const result = await api.createPlaidLinkToken();

      expect(result.linkToken).toBe(mockLinkToken);
      expect(global.fetch).toHaveBeenCalledWith(
        expect.stringContaining('/api/plaid/create-link-token'),
        expect.objectContaining({ method: 'POST' })
      );
    });

    it('should throw error on failure', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
        json: async () => ({
          success: false,
          error: 'Failed to create link token',
        }),
      });

      await expect(api.createPlaidLinkToken()).rejects.toThrow(
        'Failed to create link token'
      );
    });
  });

  describe('exchangePlaidToken', () => {
    it('should exchange public token successfully', async () => {
      const mockPublicToken = 'public-sandbox-test';
      const mockResponse = {
        access_token: 'access-sandbox-test',
        item_id: 'item-test',
      };

      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({
          success: true,
          data: mockResponse,
        }),
      });

      const result = await api.exchangePlaidToken(mockPublicToken);

      expect(result).toEqual(mockResponse);
      expect(global.fetch).toHaveBeenCalledWith(
        expect.stringContaining('/api/plaid/exchange-token'),
        expect.objectContaining({
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ publicToken: mockPublicToken }),
        })
      );
    });

    it('should throw error when response is not ok', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 400,
        statusText: 'Bad Request',
        json: async () => ({
          success: false,
          error: 'publicToken is required',
        }),
      });

      await expect(api.exchangePlaidToken('')).rejects.toThrow(
        'publicToken is required'
      );
    });

    it('should throw error when success is false', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({
          success: false,
          error: 'Invalid token',
        }),
      });

      await expect(api.exchangePlaidToken('invalid')).rejects.toThrow(
        'Invalid token'
      );
    });
  });

  describe('getPlaidAccounts', () => {
    it('should fetch accounts successfully', async () => {
      const mockAccounts = [
        { account_id: 'acc1', name: 'Checking' },
        { account_id: 'acc2', name: 'Savings' },
      ];

      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({
          success: true,
          data: mockAccounts,
        }),
      });

      const result = await api.getPlaidAccounts();

      expect(result).toEqual(mockAccounts);
      expect(global.fetch).toHaveBeenCalledWith(
        expect.stringContaining('/api/plaid/accounts')
      );
    });

    it('should throw error when no credential exists', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 404,
        statusText: 'Not Found',
        json: async () => ({
          success: false,
          error: 'No Plaid credential found',
        }),
      });

      await expect(api.getPlaidAccounts()).rejects.toThrow(
        'No Plaid credential found'
      );
    });
  });

  describe('getPlaidTransactions', () => {
    it('should fetch transactions successfully', async () => {
      const mockTransactions = [
        { transaction_id: 'tx1', amount: 10.5, name: 'Coffee' },
        { transaction_id: 'tx2', amount: 50.0, name: 'Groceries' },
      ];

      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({
          success: true,
          data: mockTransactions,
        }),
      });

      const result = await api.getPlaidTransactions('2025-01-01', '2025-01-31');

      expect(result).toEqual(mockTransactions);
      expect(global.fetch).toHaveBeenCalledWith(
        expect.stringContaining('/api/plaid/transactions'),
        expect.objectContaining({
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            startDate: '2025-01-01',
            endDate: '2025-01-31',
          }),
        })
      );
    });

    it('should throw error when dates are missing', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 400,
        statusText: 'Bad Request',
        json: async () => ({
          success: false,
          error: 'startDate and endDate are required',
        }),
      });

      await expect(api.getPlaidTransactions('', '')).rejects.toThrow(
        'startDate and endDate are required'
      );
    });
  });

  describe('Error handling', () => {
    it('should provide fallback error message when error field is missing', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
        json: async () => ({
          success: false,
          error: undefined,
        }),
      });

      await expect(api.createPlaidLinkToken()).rejects.toThrow(
        'HTTP 500: Internal Server Error'
      );
    });

    it('should handle network errors', async () => {
      global.fetch = vi.fn().mockRejectedValue(new Error('Network error'));

      await expect(api.createPlaidLinkToken()).rejects.toThrow('Network error');
    });
  });
});

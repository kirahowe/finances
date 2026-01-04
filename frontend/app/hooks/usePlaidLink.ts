/**
 * usePlaidLink Hook
 *
 * Manages Plaid Link integration using the official react-plaid-link library.
 * Designed to work with React Router's action pattern for automatic data revalidation.
 *
 * Flow:
 * 1. Fetches link token from backend
 * 2. openPlaidLink() opens the Plaid Link modal
 * 3. On success, submits to React Router action via useFetcher
 * 4. Action handles exchange + sync, React Router revalidates loaders
 */

import { useState, useEffect, useCallback } from 'react';
import { useFetcher } from 'react-router';
import { usePlaidLink as usePlaidLinkOfficial } from 'react-plaid-link';
import type { PlaidLinkOnSuccess, PlaidLinkOnExit } from 'react-plaid-link';
import { api } from '../lib/api';

export interface UsePlaidLinkOptions {
  /**
   * Callback fired after successful link and sync.
   * Note: Data revalidation happens automatically via React Router.
   */
  onSuccess?: () => void;

  /**
   * Callback fired when Plaid Link is closed without completing.
   */
  onExit?: () => void;
}

export interface UsePlaidLinkReturn {
  /** Open the Plaid Link modal */
  openPlaidLink: () => void;
  /** Whether the Plaid SDK is loaded and ready */
  isReady: boolean;
  /** Whether a link/sync operation is in progress */
  isLinking: boolean;
  /** Current status message */
  status: string;
  /** Error message, if any */
  error: string | null;
  /** Clear the current error */
  clearError: () => void;
}

/**
 * Hook for managing Plaid Link integration.
 *
 * @example
 * ```tsx
 * function AccountsSection() {
 *   const { openPlaidLink, isReady, isLinking, status, error } = usePlaidLink({
 *     onSuccess: () => console.log('Account linked!'),
 *   });
 *
 *   return (
 *     <button onClick={openPlaidLink} disabled={!isReady || isLinking}>
 *       Manage Accounts
 *     </button>
 *   );
 * }
 * ```
 */
export function usePlaidLink(options: UsePlaidLinkOptions = {}): UsePlaidLinkReturn {
  const { onSuccess, onExit } = options;

  const [linkToken, setLinkToken] = useState<string | null>(null);
  const [isLinking, setIsLinking] = useState(false);
  const [status, setStatus] = useState('');
  const [error, setError] = useState<string | null>(null);

  const fetcher = useFetcher();

  // Fetch link token on mount
  useEffect(() => {
    let cancelled = false;

    async function fetchLinkToken() {
      try {
        const data = await api.createPlaidLinkToken();
        if (!cancelled) {
          setLinkToken(data.linkToken);
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Failed to create link token');
        }
      }
    }

    fetchLinkToken();

    return () => {
      cancelled = true;
    };
  }, []);

  // Handle fetcher state changes for the link-account action
  useEffect(() => {
    if (fetcher.state === 'submitting') {
      setStatus('Linking account and syncing data...');
    } else if (fetcher.state === 'loading') {
      // Action completed, React Router is revalidating loaders
      setStatus('Refreshing account data...');
    } else if (fetcher.state === 'idle' && fetcher.data) {
      // Completed
      if (fetcher.data.success) {
        setStatus(fetcher.data.message || 'Account linked successfully!');
        setIsLinking(false);
        onSuccess?.();
        // Clear status after a delay
        setTimeout(() => setStatus(''), 3000);
      } else if (fetcher.data.error) {
        setError(fetcher.data.error);
        setIsLinking(false);
        setStatus('');
      }
    }
  }, [fetcher.state, fetcher.data, onSuccess]);

  // Handle Plaid Link success callback
  const handlePlaidSuccess = useCallback<PlaidLinkOnSuccess>((publicToken, metadata) => {
    console.log('Plaid Link success', { publicToken, metadata });
    setIsLinking(true);
    setError(null);

    // Submit to React Router action
    // The action will handle exchange + sync, and React Router will revalidate loaders
    fetcher.submit(
      {
        intent: 'link-plaid-account',
        publicToken,
      },
      { method: 'post' }
    );
  }, [fetcher]);

  // Handle Plaid Link exit callback
  const handlePlaidExit = useCallback<PlaidLinkOnExit>((err, metadata) => {
    console.log('Plaid Link exit', { err, metadata });
    if (err) {
      setError('Plaid Link was closed with an error');
    }
    onExit?.();
  }, [onExit]);

  // Use the official Plaid Link hook
  const { open, ready } = usePlaidLinkOfficial({
    token: linkToken,
    onSuccess: handlePlaidSuccess,
    onExit: handlePlaidExit,
  });

  const clearError = useCallback(() => setError(null), []);

  return {
    openPlaidLink: open,
    isReady: ready,
    isLinking,
    status,
    error,
    clearError,
  };
}

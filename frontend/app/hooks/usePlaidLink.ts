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
 * 4. Action handles exchange, returns item_id
 * 5. Hook subscribes to WebSocket for real-time sync status updates
 * 6. React Router revalidates loaders automatically when sync completes
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { useFetcher, useRevalidator } from 'react-router';
import { usePlaidLink as usePlaidLinkOfficial } from 'react-plaid-link';
import type { PlaidLinkOnSuccess, PlaidLinkOnExit } from 'react-plaid-link';
import { api } from '../lib/api';
import { useSyncSocket } from './useSyncSocket';

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

  /**
   * Timeout for sync operation in milliseconds (default: 5 minutes)
   * If sync doesn't complete within this time, we consider it successful
   * and let the user continue (transactions will appear eventually).
   */
  syncTimeout?: number;
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
  /** Whether WebSocket is connected */
  isWebSocketConnected: boolean;
}

/**
 * Hook for managing Plaid Link integration.
 *
 * Uses WebSocket for real-time sync status updates (inspired by Phoenix LiveView).
 * No polling - server pushes state changes instantly.
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
  const {
    onSuccess,
    onExit,
    syncTimeout = 5 * 60 * 1000, // 5 minutes
  } = options;

  const [linkToken, setLinkToken] = useState<string | null>(null);
  const [isLinking, setIsLinking] = useState(false);
  const [status, setStatus] = useState('');
  const [error, setError] = useState<string | null>(null);

  // Track current sync item
  const currentItemId = useRef<string | null>(null);
  const currentInstitutionName = useRef<string>('');
  const syncStartTime = useRef<number | null>(null);
  const timeoutRef = useRef<NodeJS.Timeout | null>(null);
  const retryTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const startSyncRef = useRef<((itemId: string, institutionName: string) => Promise<void>) | null>(null);

  const fetcher = useFetcher();
  const revalidator = useRevalidator();

  // WebSocket for real-time sync updates
  const { syncStates, subscribe, unsubscribe, isConnected: isWebSocketConnected } = useSyncSocket();

  // Retry interval for when Plaid is still fetching (starts at 5s, max 30s)
  const retryInterval = useRef(5000);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }
      if (retryTimeoutRef.current) {
        clearTimeout(retryTimeoutRef.current);
      }
      if (currentItemId.current) {
        unsubscribe(currentItemId.current);
      }
    };
  }, [unsubscribe]);

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

  // Handle sync completion
  const handleSyncComplete = useCallback((transactionCount: number) => {
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }

    const institutionName = currentInstitutionName.current;
    setStatus(`Synced ${transactionCount} transaction${transactionCount !== 1 ? 's' : ''} from ${institutionName}`);
    setIsLinking(false);
    revalidator.revalidate();
    onSuccess?.();

    // Clean up
    if (currentItemId.current) {
      unsubscribe(currentItemId.current);
      currentItemId.current = null;
    }

    // Clear status after a delay
    setTimeout(() => setStatus(''), 5000);
  }, [revalidator, onSuccess, unsubscribe]);

  // Handle sync failure
  const handleSyncFailed = useCallback((errorMessage?: string) => {
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }

    setError(errorMessage || 'Sync failed');
    setIsLinking(false);
    setStatus('');

    // Clean up
    if (currentItemId.current) {
      unsubscribe(currentItemId.current);
      currentItemId.current = null;
    }
  }, [unsubscribe]);

  // Retry sync when Plaid is still fetching
  const retrySync = useCallback(async () => {
    if (!currentItemId.current) return;

    try {
      await api.triggerSync(currentItemId.current);
    } catch (err) {
      console.error('Retry sync failed:', err);
    }

    // Increase retry interval with backoff (max 30s)
    retryInterval.current = Math.min(retryInterval.current * 1.5, 30000);
  }, []);

  // Watch for WebSocket sync state changes
  useEffect(() => {
    if (!currentItemId.current || !isLinking) return;

    const syncState = syncStates[currentItemId.current];
    if (!syncState) return;

    const institutionName = currentInstitutionName.current;

    switch (syncState.status) {
      case 'syncing': {
        const count = syncState.transactionCount;
        if (count > 0) {
          setStatus(`Syncing ${institutionName}... (${count} transaction${count !== 1 ? 's' : ''} so far)`);
        } else {
          setStatus(`Syncing ${institutionName} transactions...`);
        }
        break;
      }

      case 'pending':
        // Plaid is still fetching data - schedule a retry
        setStatus(`Waiting for ${institutionName} data from Plaid...`);
        if (retryTimeoutRef.current) {
          clearTimeout(retryTimeoutRef.current);
        }
        retryTimeoutRef.current = setTimeout(retrySync, retryInterval.current);
        break;

      case 'synced':
        // Clear any pending retries
        if (retryTimeoutRef.current) {
          clearTimeout(retryTimeoutRef.current);
          retryTimeoutRef.current = null;
        }
        handleSyncComplete(syncState.transactionCount);
        break;

      case 'failed':
        // Clear any pending retries
        if (retryTimeoutRef.current) {
          clearTimeout(retryTimeoutRef.current);
          retryTimeoutRef.current = null;
        }
        handleSyncFailed(syncState.error);
        break;
    }
  }, [syncStates, isLinking, handleSyncComplete, handleSyncFailed, retrySync]);

  // Start sync process via WebSocket subscription
  const startSync = useCallback(async (itemId: string, institutionName: string) => {
    currentItemId.current = itemId;
    currentInstitutionName.current = institutionName;
    syncStartTime.current = Date.now();
    retryInterval.current = 5000; // Reset retry interval

    setStatus(`Syncing ${institutionName} transactions...`);

    // Subscribe to WebSocket updates for this item
    subscribe(itemId);

    // Trigger the sync on the backend
    try {
      await api.triggerSync(itemId);
    } catch (err) {
      console.error('Failed to trigger sync:', err);
      // Continue anyway - sync may have been triggered before
    }

    // Set up timeout fallback
    timeoutRef.current = setTimeout(() => {
      console.log('Sync timeout reached, treating as successful');
      setStatus('Sync is taking longer than expected. Transactions will appear shortly.');
      setIsLinking(false);
      revalidator.revalidate();
      onSuccess?.();

      if (currentItemId.current) {
        unsubscribe(currentItemId.current);
        currentItemId.current = null;
      }
    }, syncTimeout);
  }, [subscribe, unsubscribe, syncTimeout, revalidator, onSuccess]);

  // Keep startSyncRef up to date
  useEffect(() => {
    startSyncRef.current = startSync;
  }, [startSync]);

  // Handle fetcher state changes for the link-account action
  useEffect(() => {
    if (fetcher.state === 'submitting') {
      setStatus('Exchanging token and syncing accounts...');
    } else if (fetcher.state === 'idle' && fetcher.data) {
      // Action completed
      if (fetcher.data.success && fetcher.data.itemId) {
        // Start WebSocket subscription for transaction sync
        const institutionName = fetcher.data.institutionName || 'your bank';
        startSyncRef.current?.(fetcher.data.itemId, institutionName);
      } else if (fetcher.data.error) {
        setError(fetcher.data.error);
        setIsLinking(false);
        setStatus('');
      }
    }
  }, [fetcher.state, fetcher.data]);

  // Handle Plaid Link success callback
  const handlePlaidSuccess = useCallback<PlaidLinkOnSuccess>((publicToken, metadata) => {
    console.log('Plaid Link success', { publicToken, metadata });
    setIsLinking(true);
    setError(null);

    // Extract selected account IDs from metadata
    const accountIds = metadata.accounts.map(account => account.id);
    console.log('Selected account IDs:', accountIds);

    // Submit to React Router action with account IDs
    // The action will handle exchange + sync accounts, and return item_id for syncing
    fetcher.submit(
      {
        intent: 'link-plaid-account',
        publicToken,
        accountIds: JSON.stringify(accountIds),
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

  // Wrap open function to satisfy TypeScript (react-plaid-link types it as Function)
  const openPlaidLink = useCallback(() => open(), [open]);

  return {
    openPlaidLink,
    isReady: ready,
    isLinking,
    status,
    error,
    clearError,
    isWebSocketConnected,
  };
}

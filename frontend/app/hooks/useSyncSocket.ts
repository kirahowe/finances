/**
 * WebSocket hook for real-time sync status updates.
 *
 * Inspired by Phoenix LiveView - server pushes state, client just renders.
 * The frontend is a thin client that naively displays whatever the server sends.
 *
 * Connection is lazy - only connects when subscribe() is called.
 */

import { useState, useCallback, useRef, useEffect } from 'react';

export interface SyncState {
  status: 'pending' | 'syncing' | 'synced' | 'failed';
  institutionName?: string;
  transactionCount: number;
  progress?: {
    added: number;
    modified: number;
    removed: number;
  };
  error?: string;
}

export interface UseSyncSocketReturn {
  syncStates: Record<string, SyncState>;
  subscribe: (itemId: string) => void;
  unsubscribe: (itemId: string) => void;
  isConnected: boolean;
}

/**
 * Hook for real-time sync status via WebSocket.
 * Lazy connection - only connects when you subscribe to an item.
 */
export function useSyncSocket(): UseSyncSocketReturn {
  const [syncStates, setSyncStates] = useState<Record<string, SyncState>>({});
  const [isConnected, setIsConnected] = useState(false);

  const wsRef = useRef<WebSocket | null>(null);
  const subscriptions = useRef<Set<string>>(new Set());
  const handleMessageRef = useRef<((event: MessageEvent) => void) | null>(null);

  // Parse server message (kebab-case) to client state (camelCase)
  const parseState = (serverState: Record<string, unknown>): SyncState => ({
    status: (serverState?.['status'] as SyncState['status']) ?? 'pending',
    institutionName: serverState?.['institution-name'] as string | undefined,
    transactionCount: (serverState?.['transaction-count'] as number) ?? 0,
    progress: serverState?.['progress'] as SyncState['progress'],
    error: serverState?.['error'] as string | undefined,
  });

  // Handle incoming messages
  const handleMessage = useCallback((event: MessageEvent) => {
    try {
      const msg = JSON.parse(event.data);

      if (msg.type === 'connected') {
        // Re-send subscriptions after connect
        subscriptions.current.forEach(itemId => {
          wsRef.current?.send(JSON.stringify({ type: 'subscribe', 'item-id': itemId }));
        });
      } else if (msg.type === 'sync-status' && msg['item-id']) {
        setSyncStates(prev => ({
          ...prev,
          [msg['item-id']]: parseState(msg.state || {}),
        }));
      }
    } catch (err) {
      console.error('WebSocket message parse error:', err);
    }
  }, []);

  // Keep handleMessageRef up to date
  useEffect(() => {
    handleMessageRef.current = handleMessage;
  }, [handleMessage]);

  // Connect WebSocket
  const connect = useCallback(() => {
    if (wsRef.current) return;

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const host = import.meta.env.DEV ? 'localhost:8080' : window.location.host;
    const ws = new WebSocket(`${protocol}//${host}/ws`);

    ws.onopen = () => setIsConnected(true);
    ws.onclose = () => {
      setIsConnected(false);
      wsRef.current = null;
    };
    ws.onmessage = (event) => handleMessageRef.current?.(event);
    wsRef.current = ws;
  }, []);

  // Disconnect WebSocket
  const disconnect = useCallback(() => {
    wsRef.current?.close(1000);
    wsRef.current = null;
    setIsConnected(false);
  }, []);

  // Subscribe to an item (connects if needed)
  const subscribe = useCallback((itemId: string) => {
    subscriptions.current.add(itemId);
    if (!wsRef.current) {
      connect();
    } else if (wsRef.current.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify({ type: 'subscribe', 'item-id': itemId }));
    }
  }, [connect]);

  // Unsubscribe from an item (disconnects if no more subscriptions)
  const unsubscribe = useCallback((itemId: string) => {
    subscriptions.current.delete(itemId);
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify({ type: 'unsubscribe', 'item-id': itemId }));
    }
    setSyncStates(prev => {
      const next = { ...prev };
      delete next[itemId];
      return next;
    });
    if (subscriptions.current.size === 0) {
      disconnect();
    }
  }, [disconnect]);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      wsRef.current?.close(1000);
    };
  }, []);

  return { syncStates, subscribe, unsubscribe, isConnected };
}

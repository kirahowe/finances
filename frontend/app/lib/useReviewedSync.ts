import { useEffect, useMemo, useRef } from 'react';
import { debounce } from './debounce';

// Debounced write-behind queue for reviewed toggles. The optimistic overlay (see
// reviewedOverlay) updates the UI on every click; this defers the actual PUTs so a
// burst of toggles fires one round of requests instead of one-per-click. Writes are
// keyed by entity, so toggling the same checkbox repeatedly collapses to its final
// value (last write wins). A pending burst is flushed on unmount — month change and
// route navigation both unmount the section — so nothing is lost in transit.

type PersistFn = () => Promise<unknown>;

export function useReviewedSync(delayMs = 600) {
  const pending = useRef(new Map<string, PersistFn>());

  const scheduleSend = useMemo(
    () =>
      debounce(() => {
        const writes = Array.from(pending.current.values());
        pending.current.clear();
        // Independent per-entity writes; a failure leaves the overlay value in place and
        // reconciles on the next natural reload rather than yanking the checkbox back.
        for (const write of writes) write().catch(() => {});
      }, delayMs),
    [delayMs]
  );

  const enqueue = (key: string, persist: PersistFn) => {
    pending.current.set(key, persist);
    scheduleSend();
  };

  useEffect(() => () => scheduleSend.flush(), [scheduleSend]);

  return { enqueue };
}

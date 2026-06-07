import { useEffect } from 'react';

/** Prevent the page behind a modal from scrolling while it is mounted. */
export function useBodyScrollLock(): void {
  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, []);
}

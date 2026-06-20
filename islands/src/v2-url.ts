// URL view-state reflector for the server-authoritative /v2 page.
//
// Persistent view state (search / scope / chips / sort / page / column visibility / header
// funnels) lives in Datastar signals; this island reflects them into the query string so a
// reload or shared link restores the view (URL, never localStorage). The READ side is
// server-side (web/pages/transactions2 query->view-state seeds the signals on load). All the
// serialization rules live here in one place; a Datastar `data-on-signal-patch` (scoped to
// the persistent signals) calls window.__v2syncUrl with the current values.

export {};

interface ViewState {
  q?: string;
  scope?: string;
  ht?: boolean;
  uncat?: boolean;
  sortCol?: string;
  sortDir?: string;
  page?: number;
  pageSize?: number;
  cols?: Record<string, boolean>;
  fa?: unknown[];
  fi?: unknown[];
  fc?: unknown[];
}

const csv = (a: unknown[] | undefined): string => (a ?? []).filter(Boolean).join(',');

(window as unknown as { __v2syncUrl?: (s: ViewState) => void }).__v2syncUrl = (s) => {
  const u = new URL(location.href);
  const p = u.searchParams;
  const set = (k: string, v: string) => (v ? p.set(k, v) : p.delete(k));

  set('q', s.q ?? '');
  set('scope', s.scope === 'needs-review' ? 'needs-review' : ''); // 'all' is the default → omit
  set('ht', s.ht ? '1' : '');
  set('uncat', s.uncat ? '1' : '');
  // Sort: only meaningful with a column; dir defaults to asc.
  set('sortCol', s.sortCol ?? '');
  set('sortDir', s.sortCol ? (s.sortDir ?? 'asc') : '');
  // Page is 0-indexed in the signal; omit page 0 and the default size for a clean URL.
  set('page', s.page && s.page > 0 ? String(s.page) : '');
  set('pageSize', s.pageSize && s.pageSize !== 25 ? String(s.pageSize) : '');
  // Persist the HIDDEN columns (the exception), so an all-visible table stays a clean URL.
  const hidden = Object.entries(s.cols ?? {})
    .filter(([, visible]) => !visible)
    .map(([id]) => id);
  set('hidecols', hidden.join(','));
  // Header funnels.
  set('fa', csv(s.fa));
  set('fi', csv(s.fi));
  set('fc', csv(s.fc));

  history.replaceState(null, '', `${u.pathname}?${p.toString()}`);
};

// URL view-state writer.
//
// View state (search / scope / chips / funnels / column visibility) lives in Datastar
// signals; this island reflects them into the query string so a reload, a shared link, or
// month navigation restores the same view ([[feedback_url_view_state]] — URL, never
// localStorage). The READ side is server-side: web/pages/transactions seeds the initial
// signals from the same params on load. Sort + column widths persist from their own
// islands (sort.ts owns the `sort` param); this writer leaves params it doesn't own — like
// `sort` and `month` (when unchanged) — untouched, so the two writers don't clobber.
//
// A single Datastar `data-on-signal-patch` (scoped by its filter regex to the persisted
// signals) calls window.__syncUrl with the current values, so all the serialization rules
// live here in one place rather than in a giant inline expression.

export {};

interface ViewState {
  month?: string;
  q?: string;
  scope?: string;
  ht?: boolean;
  uncat?: boolean;
  fa?: unknown[];
  fi?: unknown[];
  fc?: unknown[];
  cols?: Record<string, boolean>;
  page?: number;
  pageSize?: number;
}

const csv = (a: unknown[] | undefined): string => (a ?? []).filter(Boolean).join(',');

(window as unknown as { __syncUrl?: (s: ViewState) => void }).__syncUrl = (s) => {
  const u = new URL(location.href);
  const p = u.searchParams;
  const setOrDel = (k: string, v: string) => (v ? p.set(k, v) : p.delete(k));

  if (s.month) p.set('month', s.month);
  setOrDel('q', s.q ?? '');
  setOrDel('scope', s.scope === 'all' ? '' : s.scope ?? ''); // 'all' is the default → omit
  setOrDel('ht', s.ht ? '1' : '');
  setOrDel('uncat', s.uncat ? '1' : '');
  setOrDel('fa', csv(s.fa));
  setOrDel('fi', csv(s.fi));
  setOrDel('fc', csv(s.fc));
  // Persist the HIDDEN columns (the exception), so an all-visible table stays a clean URL.
  const hidden = Object.entries(s.cols ?? {})
    .filter(([, visible]) => !visible)
    .map(([id]) => id);
  setOrDel('hidecols', hidden.join(','));

  // Pagination: page is 1-indexed in the URL (omit page 1); pageSize omits the 25 default.
  const page = Number(s.page) || 0;
  setOrDel('page', page > 0 ? String(page + 1) : '');
  setOrDel('pageSize', Number(s.pageSize) === 25 ? '' : String(s.pageSize ?? ''));

  history.replaceState(null, '', `${u.pathname}?${p.toString()}`);
};

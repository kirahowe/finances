// Client-side pagination for the transactions table.
//
// Filtering is instant (Datastar `data-show` toggling inline display); pagination is the
// next layer down. It slices the FILTER-VISIBLE row-groups to the current page by adding a
// `.page-hidden` class — `data-show` REMOVES the inline display when a row matches, so the
// class composes (matched-but-paged-out → class hides it; matched-and-on-page → shown). It
// paginates by TRANSACTION: a split's leader + child rows move as one group, matching React
// paginating the filtered transaction list.
//
// The page-size + nav controls are Datastar ($page / $pageSize). This island computes the
// page COUNT and clamps an out-of-range page, handing both back to Datastar via a
// `paginate-clamp` window event (numbers — not a bound input, which would stringify $page).

export {};

const scroll = document.querySelector<HTMLElement>('.transactions-table-scroll');
const tbody = scroll?.querySelector<HTMLTableSectionElement>('tbody');

if (scroll && tbody) {
  type Group = HTMLTableRowElement[];

  // A group = a transaction's leader row (carries data-sort-date) + any following rows
  // without one (its split children).
  const groupRows = (): Group[] => {
    const gs: Group[] = [];
    for (const tr of Array.from(tbody.rows)) {
      if (tr.dataset.sortDate !== undefined || gs.length === 0) gs.push([tr]);
      else gs[gs.length - 1].push(tr);
    }
    return gs;
  };

  // Filtered-visible ⟺ data-show hasn't hidden the leader. data-show sets inline
  // display:none; pagination uses a class — so the inline display distinguishes the two.
  const filterVisible = (g: Group) => g[0].style.display !== 'none';

  let page = 0;
  let pageSize = 25;
  let raf = 0;

  function run() {
    const all = groupRows();
    const visibleCount = all.filter(filterVisible).length;
    const pageCount = Math.max(1, Math.ceil(visibleCount / pageSize));
    const clamped = Math.min(Math.max(0, page), pageCount - 1);
    const start = clamped * pageSize;
    const end = start + pageSize;

    let i = 0; // running index among filter-visible groups
    for (const g of all) {
      const visible = filterVisible(g);
      const onPage = visible && i >= start && i < end;
      for (const tr of g) tr.classList.toggle('page-hidden', visible && !onPage);
      if (visible) i++;
    }

    // Hand the count + clamped page back to Datastar as numbers.
    window.dispatchEvent(new CustomEvent('paginate-clamp', { detail: { page: clamped, count: pageCount } }));
  }

  // Defer to rAF so data-show (same signal-patch batch) has applied its inline display
  // before we read it.
  function schedule() {
    if (raf) cancelAnimationFrame(raf);
    raf = requestAnimationFrame(run);
  }

  (window as unknown as { __paginate?: (p: number, s: number) => void }).__paginate = (p, s) => {
    page = p;
    pageSize = s > 0 ? s : 25;
    schedule();
  };

  // Re-slice after a sort reorders the rows.
  scroll.addEventListener('grid-refresh', schedule);

  // Initial run from the URL-seeded values (the signal-patch trigger may not fire on load).
  const params = new URLSearchParams(location.search);
  const ps = Number(params.get('pageSize'));
  const pg = Number(params.get('page'));
  pageSize = ps > 0 ? ps : 25;
  page = Number.isInteger(pg) && pg > 1 ? pg - 1 : 0;
  schedule();

  console.log('pagination island ready');
}

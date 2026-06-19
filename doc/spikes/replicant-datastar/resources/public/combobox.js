// Category combobox island — the downshift replacement.
//
// Proves we can drop downshift: type-ahead filtering, arrow-key highlight, Enter
// to select, Escape/click-outside to close, and body-anchored ("portal")
// positioning so the dropdown escapes the table's scroll/overflow clipping —
// the exact concerns downshift handles. ~110 lines, no dependencies.
//
// Persists the chosen category through the JSON API (PUT /tx/:id/category), NOT
// Datastar — demonstrating the hypermedia page and a JSON API coexisting on the
// same server.

const categories = [...document.querySelectorAll('#cat-options li')].map((li) => li.textContent);

let dd = null;     // the floating dropdown element
let cellTd = null; // the category <td> being edited
let filtered = [];
let hi = 0;

const clamp = (n, lo, hi) => Math.max(lo, Math.min(hi, n));

function renderList(query) {
  const q = query.toLowerCase();
  filtered = categories.filter((c) => c.toLowerCase().includes(q));
  hi = clamp(hi, 0, Math.max(0, filtered.length - 1));
  const ul = dd.querySelector('.combo-list');
  ul.innerHTML = '';
  filtered.forEach((c, i) => {
    const li = document.createElement('li');
    li.textContent = c;
    li.className = 'combo-opt' + (i === hi ? ' hl' : '');
    li.addEventListener('mousedown', (e) => { e.preventDefault(); select(c); });
    ul.appendChild(li);
  });
}

function moveHi(d) {
  if (!filtered.length) return;
  hi = clamp(hi + d, 0, filtered.length - 1);
  [...dd.querySelectorAll('.combo-opt')].forEach((li, i) => li.classList.toggle('hl', i === hi));
  dd.querySelectorAll('.combo-opt')[hi]?.scrollIntoView({ block: 'nearest' });
}

function open(td) {
  close();
  cellTd = td;
  const r = td.getBoundingClientRect();
  dd = document.createElement('div');
  dd.className = 'combo-dropdown';
  // position: fixed against the viewport — escapes any ancestor overflow (portal).
  dd.style.left = r.left + 'px';
  dd.style.top = r.bottom + 'px';
  dd.style.minWidth = r.width + 'px';
  dd.innerHTML = '<input class="combo-input" placeholder="Filter category…"><ul class="combo-list"></ul>';
  document.body.appendChild(dd);
  hi = 0;
  renderList('');
  const input = dd.querySelector('.combo-input');
  input.addEventListener('input', () => { hi = 0; renderList(input.value); });
  input.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); moveHi(+1); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); moveHi(-1); }
    else if (e.key === 'Enter') { e.preventDefault(); if (filtered[hi]) select(filtered[hi]); }
    else if (e.key === 'Escape') { e.preventDefault(); close(true); }
    else if (e.key === 'Tab') {
      // Tab/Shift+Tab closes without committing and hands focus back to the grid,
      // which moves to the next/previous cell — never trap focus in the dropdown.
      e.preventDefault();
      const td = cellTd;
      close(false);
      td.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: e.shiftKey, bubbles: true }));
    }
  });
  input.focus();
}

function select(cat) {
  cellTd.querySelector('.cell-view').textContent = cat; // optimistic
  const txId = cellTd.dataset.cell.split(':')[0];
  fetch(`/tx/${txId}/category`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ category: cat }),
  });
  close(true);
}

function close(refocus) {
  if (!dd) return;
  const td = cellTd;
  dd.remove(); dd = null; cellTd = null;
  if (refocus && td) td.focus();
}

// Open on click of a combobox category cell.
document.getElementById('grid').addEventListener('click', (e) => {
  const td = e.target.closest('.combo-cell');
  if (td) open(td);
});

// Open on Enter / type-to-edit from the keyboard grid (grid-nav dispatches this).
document.addEventListener('open-combo', (e) => open(e.detail.td));

// Click outside closes.
document.addEventListener('mousedown', (e) => {
  if (dd && !dd.contains(e.target) && e.target !== cellTd) close();
});

window.__comboOpen = () => !!dd; // for the Playwright probe
console.log('combobox island ready: ' + categories.length + ' categories');

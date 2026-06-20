// Phase-1 scaffold island. Its only job is to prove the island pipeline runs
// end to end: esbuild bundles this entry together with a pure lib module, the
// bundle is served from /js/islands/hello.js with the right MIME type, loaded
// as an ES module, and executes in the browser. The Playwright scaffold check
// asserts on the data-island-ready flag it sets.
//
// Real islands (grid-nav, combobox, …) replace this in Phase 3.

import { centsToAmountString } from './lib/splitMath';

const el = document.getElementById('island-demo');
if (el) {
  // Call a pure lib fn to prove cross-module bundling actually happened.
  const formatted = centsToAmountString(123456); // -> "1234.56"
  el.textContent = `island loaded ✓ — splitMath.centsToAmountString(123456) = ${formatted}`;
  el.setAttribute('data-island-ready', 'true');
}

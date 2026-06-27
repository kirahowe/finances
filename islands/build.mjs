// esbuild bundler for the TS islands + pinned Datastar runtime.
//
// Each entry in ISLANDS is bundled to an ES module under the backend's static
// asset dir (backend/resources/public/js/islands/<name>.js), where the reitit
// static route serves it with a text/javascript MIME type. Add an island by
// adding its entry source here.
//
// The Datastar browser runtime is a managed dependency too: it is pinned to
// DATASTAR_VERSION and fetched once from the official jsDelivr/GitHub release
// into backend/resources/public/js/datastar.js (a gitignored build artifact).
// Upstream stopped publishing the v1.0 line to npm, so we pin-and-fetch instead
// of depending on a stale npm package. To upgrade: bump DATASTAR_VERSION.
//
// Flags: pass --watch to rebuild islands on change (used by `bb dev`).

import { build, context } from 'esbuild';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { readFile, writeFile, mkdir } from 'node:fs/promises';

const root = dirname(fileURLToPath(import.meta.url));
const publicJs = resolve(root, '../backend/resources/public/js');
const outdir = resolve(publicJs, 'islands');

const DATASTAR_VERSION = 'v1.0.2';
const datastarPath = resolve(publicJs, 'datastar.js');
const datastarUrl =
  `https://cdn.jsdelivr.net/gh/starfederation/datastar@${DATASTAR_VERSION}/bundles/datastar.js`;

// Fetch the pinned Datastar runtime if it is missing or the wrong version. The
// bundle's first line is a `// Datastar vX.Y.Z` marker, which we use both to
// skip redundant downloads and to sanity-check what we fetched.
async function ensureDatastar() {
  const marker = `// Datastar ${DATASTAR_VERSION}`;
  try {
    if ((await readFile(datastarPath, 'utf8')).startsWith(marker)) {
      console.log(`datastar ${DATASTAR_VERSION} present`);
      return;
    }
  } catch {
    // missing — fall through and fetch
  }
  console.log(`fetching datastar ${DATASTAR_VERSION} -> ${datastarPath}`);
  const res = await fetch(datastarUrl);
  if (!res.ok) {
    throw new Error(`datastar fetch failed: ${res.status} ${res.statusText} (${datastarUrl})`);
  }
  const body = await res.text();
  if (!body.startsWith(marker)) {
    throw new Error(`datastar fetch returned unexpected content (missing "${marker}" header)`);
  }
  await mkdir(publicJs, { recursive: true });
  await writeFile(datastarPath, body);
}

const ISLANDS = [
  'src/combobox.ts',
  'src/grid-nav.ts',
  'src/url.ts',
  'src/resize.ts',
  'src/split-editor.ts',
  'src/modal.ts',
  'src/plaid-link.ts',
];

const buildOpts = {
  entryPoints: ISLANDS.map((p) => resolve(root, p)),
  outdir,
  bundle: true,
  minify: true,
  format: 'esm',
  target: ['es2020'],
  sourcemap: false,
  logLevel: 'info',
};

await ensureDatastar();

if (process.argv.includes('--watch')) {
  const ctx = await context(buildOpts);
  await ctx.rebuild();
  await ctx.watch();
  console.log(`islands watching -> ${outdir}`);
} else {
  await build(buildOpts);
  console.log(`islands built -> ${outdir}`);
}

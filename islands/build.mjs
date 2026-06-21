// esbuild bundler for the TS islands.
//
// Each entry in ISLANDS is bundled to an ES module under the backend's static
// asset dir (backend/resources/public/js/islands/<name>.js), where the reitit
// static route serves it with a text/javascript MIME type. Add an island by
// adding its entry source here.

import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = dirname(fileURLToPath(import.meta.url));
const outdir = resolve(root, '../backend/resources/public/js/islands');

const ISLANDS = [
  'src/combobox.ts',
  'src/grid-nav.ts',
  'src/url.ts',
  'src/resize.ts',
];

await build({
  entryPoints: ISLANDS.map((p) => resolve(root, p)),
  outdir,
  bundle: true,
  minify: true,
  format: 'esm',
  target: ['es2020'],
  sourcemap: false,
  logLevel: 'info',
});

console.log(`islands built -> ${outdir}`);

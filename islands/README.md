# islands

Vanilla **TS islands** (latency/pointer-heavy widgets) for the server-authoritative
hiccup2 + **Datastar** frontend, bundled with **esbuild**. They replaced the React
widgets in the frontend rewrite (see `../doc/plans/datastar-handoff.md`).

- `src/lib/**` — framework-free pure logic, with their **vitest** tests
  (gridNavigation, splitMath, dragAndDrop, columnAutoSizing, categoryHierarchy,
  categoryReorder, + `types.ts` for a zod-free `Category`).
- `src/<name>.ts` — island entry points (mount on server-rendered DOM, interop with
  Datastar via DOM events): `combobox`, `grid-nav`, `url`, `resize`, `split-editor`,
  `modal`.
- `build.mjs` — esbuild config; **add new islands to the `ISLANDS` list**. Output →
  `../backend/resources/public/js/islands/<name>.js` (gitignored), served by the
  backend with a `text/javascript` MIME type and loaded per-page via `layout`'s
  `:islands`. The build also fetches the **pinned Datastar runtime**
  (`DATASTAR_VERSION`) into `../backend/resources/public/js/datastar.js` (gitignored)
  — the v1.0 line isn't on npm, so we pin-and-fetch instead of vendoring. Bump
  `DATASTAR_VERSION` to upgrade.

Run from the repo root via Babashka (preferred — `bb install` / `bb build` /
`bb test`), or directly here:

```bash
npm install
npm run build      # fetch pinned Datastar + bundle islands → backend static assets
npm run watch      # rebuild on change (used by `bb dev`)
npm test           # vitest (pure-logic tests)
npm run typecheck  # tsc --noEmit
```

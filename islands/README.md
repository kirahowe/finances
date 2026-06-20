# islands

Vanilla **TS islands** (latency/pointer-heavy widgets) for the Replicant +
Datastar frontend, bundled with **esbuild**. Part of the React→Replicant migration
(see `../doc/plans/replicant-datastar-progress.md`).

- `src/lib/**` — framework-free pure logic copied from the React app, with their
  **vitest** tests (gridNavigation, splitMath, dragAndDrop, columnAutoSizing,
  categoryHierarchy, categoryReorder, + `types.ts` for a zod-free `Category`).
- `src/<name>.ts` — island entry points (mount on server-rendered DOM, interop with
  Datastar via DOM events). Currently `hello.ts` (Phase-1 pipeline proof).
- `build.mjs` — esbuild config; **add new islands to the `ISLANDS` list**. Output →
  `../backend/resources/public/js/islands/<name>.js` (gitignored), served by the
  backend with a `text/javascript` MIME type and loaded per-page via `base-page`'s
  `:islands`.

```bash
npm install
npm run build      # bundle islands → backend static assets
npm test           # vitest (pure-logic tests)
npm run typecheck  # tsc --noEmit
```

Next islands to add (Phase 3c/3d): the category **combobox** (Zag vanilla — see the
spike's `../doc/spikes/replicant-datastar/islands/combobox-zag.ts`), the keyboard
**grid-nav** (port `src/lib/gridNavigation.ts`), and **column resize/auto-fit** (port
`src/lib/columnAutoSizing.ts`).

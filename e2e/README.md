# Browser checks (server-rendered frontend)

Playwright-driven checks of the Datastar pages, run in real Chromium. They prove
what server-side/curl checks can't: that the Datastar runtime and the esbuild
islands actually execute in a browser and that SSE patches land in the DOM. (A
curl text-grep can match *escaped* markup; the browser is the source of truth —
see the `#sync-result` escaping bug these checks caught in Phase 1.)

The specs are TypeScript, run directly by Node (native type-stripping, no build).
Deps live here in `e2e/` (`@playwright/test`); install with `bb install` (or
`npm install` from this dir).

## Run

```bash
bb e2e             # build islands, boot the seeded server, run every e2e/*.ts spec
bb e2e v2-grid     # run a subset by spec name (e2e/v2-grid.ts)
```

`bb e2e` builds the islands, boots the seeded e2e server on :8099 (deterministic,
no secrets needed), runs the specs, and tears the server down — exiting non-zero
if any spec fails.

### Manual run

```bash
# 1. frontend assets → backend/resources/public/js/
bb build

# 2. seeded backend on :8099
cd backend
E2E_PORT=8099 clojure -M:e2e -m finance-aggregator.dev.e2e-server &
cd ..

# 3. run a spec (each defaults to http://localhost:8099)
BASE_URL=http://localhost:8099 node e2e/setup.ts
```

Each spec exits non-zero if any check fails, printing a PASS/FAIL report. The seed
(2025-01) has 1 institution, 4 accounts, 10 transactions; the checks assert against
those fixed values, so the table specs navigate with `?month=2025-01`.

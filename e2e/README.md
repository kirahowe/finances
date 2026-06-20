# Browser checks (server-rendered frontend)

Playwright-driven checks of the Replicant + Datastar pages, run in real Chromium.
They prove what server-side/curl checks can't: that the Datastar runtime and the
esbuild islands actually execute in a browser and that SSE patches land in the
DOM. (A curl text-grep can match *escaped* markup; the browser is the source of
truth — see the `#sync-result` escaping bug these checks caught in Phase 1.)

Playwright is resolved from `frontend/node_modules` (already installed), so no
extra install is needed.

## Run

Boot the backend (the seeded e2e server needs no secrets), build the islands,
then run a check against it:

```bash
# 1. islands bundles → backend/resources/public/js/islands/
cd islands && npm install && npm run build && cd ..

# 2. backend on :8099 (seeded, deterministic, no secrets)
cd backend
JAVA_HOME=/opt/homebrew/Cellar/jabba/0.15.0/jdk/zulu@25.0.3/Contents/Home \
  E2E_PORT=8099 clojure -M:e2e -m finance-aggregator.dev.e2e-server &
cd ..

# 3. a check (defaults to http://localhost:8099)
BASE_URL=http://localhost:8099 node e2e/scaffold.mjs
```

Each `*.mjs` exits non-zero if any check fails, printing a PASS/FAIL report.

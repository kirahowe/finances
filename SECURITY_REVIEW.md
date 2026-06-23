# Security Review

Date: 2026-06-06
Scope: backend (`backend/`), not just pending changes.

Status: **Not deployed anywhere yet** — these are tracked for fix-up as they become
salient, not active incidents. Most are only remotely exploitable once the app binds
beyond loopback / ships the Docker prod layout.

> **Caveat (point-in-time review).** This is a snapshot from 2026-06-06. Code has moved
> since (notably the React `frontend/` was deleted in the Datastar rewrite), so the
> file:line anchors below may have drifted and some items may already be addressed — for
> example the static-file path-traversal item (#2): `http/routes/static.clj` now refuses
> any path containing `..` and serves via the classpath (`io/resource`). Findings are
> **not** re-adjudicated here; treat anchors as approximate.

Severity ordering below. Each item has a file:line anchor and a concrete fix.

---

## CRITICAL

### 1. Unauthenticated RCE via `POST /api/query`
`backend/src/finance_aggregator/http/handlers/entities.clj:215`
(route: `backend/src/finance_aggregator/http/routes/entities.clj:30`)

```clojure
(let [query (read-string query-str)   ; query-str is the raw request body
      results (d/q query db)]
```

`clojure.core/read-string` honors `*read-eval*` (default `true`), so the `#=` reader
macro executes arbitrary code at read time — before `d/q` runs. No auth anywhere in
the stack, so any client that can reach the port gets code execution as the server
process (which also holds the credential decryption key).

PoC:
```json
{"query": "#=(clojure.java.shell/sh \"id\")"}
```

Fix: remove the endpoint (preferred). If a query API is truly needed, use
`clojure.edn/read-string` (no reader-eval) **plus** authentication and a query
allowlist — note EDN parsing still allows full-DB exfiltration.

---

## HIGH

### 2. Path traversal / arbitrary file read in static serving
`backend/src/finance_aggregator/http/routes/static.clj:41-44` (helper at line 16)

`/js/*path` catch-all is concatenated into `resources/public/js/<path>` and `slurp`ed
with no normalization. `GET /js/../../../../etc/passwd` reads arbitrary files
(including `resources/secrets.edn.age`, prod config, source).

Fix: canonicalize the resolved path and verify it stays under `resources/public`
(`.getCanonicalPath` prefix check), or use ring/reitit resource/file handlers.

### 3. No authentication on any endpoint
`backend/src/finance_aggregator/http/middleware.clj` (only CORS/JSON/params middleware)

All routes are open — transactions/accounts, item deletion, sync triggers, and the
`/api/query` RCE. Single-user/`test-user` is intentional for now, but the `prod`
config describes a remote Docker deployment, which makes #1/#2 remotely exploitable.

Fix: bind to `127.0.0.1` and/or require a token before any non-loopback deployment.

---

## MEDIUM

### 4. Plaid `access_token` returned to the client
`backend/src/finance_aggregator/http/handlers/plaid.clj:83`

Exchange handler encrypts+stores the token, then also returns it in the response.
Defeats server-side encryption; the token leaks into the browser + network logs. Fix:
drop `:access_token` from the response.

### 5. `read-string` on stored credential blobs (defense-in-depth)
`backend/src/finance_aggregator/db/credentials.clj:125, 272, 317`

Encrypted-data EDN is parsed with reader-eval-enabled `read-string`. App writes the
field today, but any future write-path compromise becomes a second RCE on read.
Fix: use `clojure.edn/read-string` (applies anywhere DB/file content is parsed).

### 6. Internal exception details leaked to clients
`backend/src/finance_aggregator/http/errors.clj:66-73`

500 path returns `(.getMessage ex)` and the full `ex-data` (hints, file paths,
item-ids, SimpleFin tokens) to the caller. With wildcard CORS that's cross-origin
disclosure. Fix: log details server-side, return a generic 500 message.

---

## LOW / NOTES

- **Wildcard CORS default** — `backend/resources/system/base-system.edn:13-17`:
  `allowed-origins ["*"]`, `allowed-headers ["*"]`. `prod` overrides; `dev` and any
  new env inherit the permissive default. Tighten the base default.
- **Credentials in `ex-info` data** — `backend/src/finance_aggregator/simplefin/client.clj:21,49,71`:
  setup token and `claim-url` placed in exception data, can land in logs. Scrub.
- **No request-size limits** — `/api/csv/import` and `/api/query` `slurp` the full
  body into memory; cheap DoS. Add a size cap.
- **`keyword` on untrusted input** — `backend/src/finance_aggregator/http/handlers/categories.clj:47-48,79`:
  interning keywords from arbitrary client strings is a minor unbounded-memory vector.

---

## Suggested order when these become salient
1. Remove/lock down `POST /api/query` (#1)
2. Fix static path traversal (#2)
3. Add auth + loopback binding before any deployment (#3)
4. Stop returning the Plaid access token (#4)
5. Swap `read-string` → `edn/read-string`, scrub error/exception leakage (#5, #6)

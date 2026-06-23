# Development Setup

Quick guide to running the Finance Aggregator locally.

The app is **server-authoritative**: a single Clojure backend renders the pages
(hiccup2 SSR + Datastar over SSE) and serves the frontend assets — the TS
"islands" and the pinned Datastar runtime. There is no separate frontend dev
server. Common dev tasks are exposed as **Babashka tasks** (`bb <task>`); run
`bb tasks` to list them.

## Prerequisites

### Required Tools

1. **Babashka** (task runner + secrets management):
   ```bash
   brew install borkdude/brew/babashka
   ```

2. **age encryption** (for secrets):
   ```bash
   brew install age
   ```

3. **Jabba** (Java version manager):
   ```bash
   curl -sL https://github.com/shyiko/jabba/raw/master/install.sh | bash
   jabba install zulu@25.0.3
   jabba use zulu@25.0.3   # once per terminal session
   ```

4. **Node + npm** (frontend deps: TS islands + Playwright e2e):
   ```bash
   brew install node
   ```

5. **clj-kondo** (Clojure linting, used by `bb lint`):
   ```bash
   brew install borkdude/brew/clj-kondo
   ```

## First-Time Setup

### 1. Install Frontend Dependencies

The remaining frontend code is npm-managed in two workspaces — `islands/` (the
Zag/esbuild widgets) and `e2e/` (Playwright browser checks). Install both:

```bash
bb install
```

### 2. Configure Secrets

The project uses age-encrypted secrets instead of environment variables. No `.env`
files needed!

```bash
# Generate your encryption key
bb secrets keygen

# Create and edit your secrets file
bb secrets new
```

In your editor, add your Plaid API credentials and a database encryption key:

```clojure
{:plaid {:client-id "your_actual_client_id"
         :secret "your_actual_secret"
         :environment :sandbox}

 :database {:encryption-key "GENERATE_ME"}}  ; See below to generate
```

**To generate a secure encryption key:**
```bash
# Start a Clojure REPL
cd backend && clojure -M:repl

# Generate and copy the key
(require '[finance-aggregator.lib.encryption :as enc])
(println (enc/generate-encryption-key))
# Copy the output and paste it as your :encryption-key value
```

Get your Plaid credentials from: https://dashboard.plaid.com/team/keys

See `backend/SECRETS.md` for detailed documentation.

### 3. Configure Plaid Link for Multiple Account Selection

The application expects multiple account selection to be enabled in Plaid Link:

1. Go to the [Plaid Dashboard Link Customization](https://dashboard.plaid.com/link)
2. Navigate to **Link Customization**
3. Find the **Account Select** section
4. Set to **"Enabled for multiple accounts"** - This allows users to select/deselect individual accounts

**Important**: Ensure your language and country settings in the customization match your link token configuration (default: `en` and `US`). Mismatched settings will prevent customization from being applied.

The application automatically captures and displays selected accounts when users complete the Plaid Link flow.

**Note**: Some Plaid flows (Instant Match, Automated Micro-deposits Auth) only support single account selection and will override this setting.

## Running the Application

```bash
bb dev
```

This builds the frontend assets once (fetches the pinned Datastar runtime +
bundles the islands), then starts the backend dev server while watching the
islands and rebuilding them on change. Stop it with `Ctrl+C` (the islands watcher
is torn down with it).

- **App**: http://localhost:8080
- **Health Check**: http://localhost:8080/health

### Alternative: Manual Start

If you prefer to drive the backend from a REPL (recommended for interactive
development):

```bash
# Build the frontend assets first (islands + Datastar runtime)
bb build

# Backend REPL
cd backend
jabba use zulu@25.0.3
clojure -M:repl -m nrepl.cmdline
# Then in your editor, connect to the REPL and: (go)
```

## Tasks

Run `bb tasks` to list everything. The common ones:

| Task         | What it does                                                              |
|--------------|--------------------------------------------------------------------------|
| `bb install` | Install npm deps for `islands/` and `e2e/`                                |
| `bb build`   | Fetch the pinned Datastar runtime + bundle the TS islands                 |
| `bb dev`     | Watch/rebuild islands + start the backend dev server                     |
| `bb test`    | Backend tests (kaocha) + island tests (vitest)                           |
| `bb lint`    | clj-kondo (backend) + `tsc` typechecks (islands, e2e)                     |
| `bb e2e`     | Build, boot the seeded server, run the Playwright browser checks         |
| `bb secrets` | Manage age-encrypted secrets                                             |

### Testing

```bash
bb test            # backend (kaocha) + islands (vitest)
bb e2e             # all browser checks against a seeded, deterministic server
bb e2e v2-grid     # run a subset by spec name (e2e/v2-grid.ts)
```

`bb e2e` boots the seeded e2e server (no secrets needed) on port 8099, runs the
specs in `e2e/*.ts`, and tears the server down. See `e2e/README.md`.

## Frontend Dependencies

We still use a package manager for the frontend deps we do have:

- **`islands/`** — npm-managed (`@zag-js/*`, esbuild, vitest, typescript).
  `npm`/`bb` commands run from here; the lockfile is committed.
- **`e2e/`** — npm-managed (`@playwright/test`, typescript). Lockfile committed.
- **Datastar runtime** — the v1.0 line is no longer published to npm, so it is
  **pinned and fetched** instead of vendored: `islands/build.mjs` pins
  `DATASTAR_VERSION` and downloads that exact release from the official jsDelivr/
  GitHub bundle into `backend/resources/public/js/datastar.js` (a gitignored
  build artifact). To upgrade, bump `DATASTAR_VERSION` and run `bb build`.

## REPL Development (Backend)

For interactive development with the backend:

```bash
cd backend
jabba use zulu@25.0.3
clojure -M:repl -m nrepl.cmdline
```

Then in your editor, connect to the REPL and run:

```clojure
(require 'user)
(help)   ; See all available REPL commands
(dev)    ; Load dev namespace
(go)     ; Start the system
```

See `doc/implementation/adr-003-backend/repl-quick-reference.md` for details.

## Project Structure

```
finance-aggregator/
├── bb.edn                # Babashka dev tasks (dev/build/test/lint/e2e/secrets)
├── backend/              # Clojure backend (SSR + Datastar, serves frontend assets)
│   ├── src/              # Source code
│   ├── test/             # Tests
│   ├── env/              # Per-environment config + dev/e2e source
│   ├── resources/        # Config, secrets, public assets (CSS + built JS)
│   └── SECRETS.md        # Secrets management guide
├── islands/              # Vanilla TS islands (Zag widgets), esbuild → backend assets
├── e2e/                  # Playwright browser checks (TypeScript, run by Node)
└── scripts/
    └── secrets.clj       # bb secrets command
```

## Troubleshooting

### "bb: command not found"

Install Babashka: `brew install borkdude/brew/babashka`

### "age: command not found"

Install age: `brew install age`

### "Failed to decrypt secrets file"

You need to generate an age key and create secrets:

```bash
bb secrets keygen
bb secrets new
```

### Backend won't start

Make sure you've set the Java version:

```bash
jabba use zulu@25.0.3
```

### Island bundles or Datastar 404

Build the frontend assets (also done automatically by `bb dev`/`bb e2e`):

```bash
bb build
```

## Documentation

- **Backend**:
  - Architecture: `doc/adr/adr-003-clojure-backend-architecture.md`
  - Plaid Integration: `doc/adr/adr-004-plaid-integration.md`
  - REPL Guide: `doc/implementation/adr-003-backend/repl-quick-reference.md`
  - Secrets Management: `backend/SECRETS.md`
- **Frontend (server-authoritative)**:
  - Islands: `islands/README.md`
  - Browser checks: `e2e/README.md`
  - Migration history: `doc/plans/datastar-handoff.md`
```


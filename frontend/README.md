# Finance Aggregator — Frontend

The web UI for Finance Aggregator: a transactions workspace (`/`) for viewing,
filtering, sorting, splitting, categorizing, and matching transfers, plus an
admin area (`/setup`) for accounts and categories. It talks to the Clojure
backend's HTTP API.

## Stack

- **React 19** + **React Router v7** (framework mode — loaders/actions, SSR)
- **TypeScript**
- **Zod** for runtime validation
- **Vite** build tool
- **TanStack Table** for the transactions table
- **Downshift** for the category combobox
- Plain **CSS** — no utility framework. See styling conventions below.

## Getting started

Install dependencies (pnpm only):

```bash
pnpm install
```

Run the dev server (expects the backend API on `http://localhost:8080`):

```bash
pnpm dev
```

The app is served at `http://localhost:5173`. For the full stack (backend +
frontend together) use `overmind start` from the repo root — see the root
[README](../README.md) and [DEVELOPMENT.md](../DEVELOPMENT.md).

## Testing

```bash
pnpm typecheck   # react-router typegen + tsc
pnpm test        # unit/integration (Vitest)
pnpm test:e2e    # end-to-end (Playwright)
```

`pnpm test:e2e` launches its own isolated, seeded backend (port 8081) and a
dedicated frontend (port 5174), so it never touches the dev stack or real data;
each spec resets the seed first. The seed is pinned to month `2025-01` so the
data is stable regardless of the run date.

## Styling conventions

- **No inline styles.** All styling lives in CSS files under `app/styles/`:
  page styles in `app/styles/pages/`, component styles in
  `app/styles/components/`.
- Use shared design tokens (colors, spacing, radii) from
  `app/styles/base/variables.css` rather than hard-coded values.

## View state in the URL

Table/view state — sort, filters, the hide-transfers toggle, column visibility,
resized column widths, and page/size — is serialized to the URL (never
`localStorage`), so a refresh or shared link restores the exact same view. The
(de)serializers live in `app/lib/` (`sortingState`, `filterState`,
`columnState`).

## Project layout

```
app/
├── components/   # React components
├── lib/          # pure logic, URL state, API client, hooks
├── routes/       # React Router routes (home, setup, …)
└── styles/       # base tokens, page styles, component styles
tests/
├── lib/          # unit tests for app/lib
├── components/   # component tests
├── integration/  # integration tests
└── e2e/          # Playwright specs
```

See [ADR-002: Modern React Frontend](../doc/adr/adr-002-modern-react-frontend-architecture.md)
for the architectural rationale.

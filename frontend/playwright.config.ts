import { defineConfig, devices } from '@playwright/test';

// E2E runs against an isolated, seeded backend (port 8081) and its own frontend
// dev server (port 5174 -> VITE_API_BASE=8081), so it never touches the dev stack
// (5173 -> 8080) or your real data. Tests share one mutable backend, so they run
// serially and every spec resets the seed before each test via the shared
// resetSeed() helper (tests/e2e/helpers.ts).
export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: 'html',
  use: {
    baseURL: 'http://localhost:5174',
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: [
    // NOTE: reuseExistingServer reuses a backend already on :8081. The e2e server
    // has no hot-reload, so after changing backend code you must kill the stale
    // process (it blocks forever) or tests run against old code. resetSeed() only
    // refreshes data, not code.
    {
      command: 'clojure -M:e2e -m finance-aggregator.dev.e2e-server',
      cwd: '../backend',
      url: 'http://localhost:8081/api/transactions',
      reuseExistingServer: !process.env.CI,
      timeout: 180_000,
      stdout: 'pipe',
      stderr: 'pipe',
    },
    {
      command: 'npm run dev',
      url: 'http://localhost:5174',
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
      env: { VITE_API_BASE: 'http://localhost:8081', FRONTEND_PORT: '5174' },
    },
  ],
});

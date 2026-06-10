import type { APIRequestContext } from '@playwright/test';

// The e2e backend (see backend/env/e2e/src/.../seed.clj) holds one shared,
// mutable dataset. Every spec resets it before each test so tests are isolated
// regardless of run order or a reused dev server.
export const E2E_API = 'http://localhost:8081';

export async function resetSeed(request: APIRequestContext): Promise<void> {
  const res = await request.post(`${E2E_API}/e2e/reset`);
  if (!res.ok()) {
    throw new Error(`Seed reset failed: HTTP ${res.status()} ${res.statusText()}`);
  }
}

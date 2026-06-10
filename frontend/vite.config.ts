import { reactRouter } from "@react-router/dev/vite";
import { defineConfig } from "vite";
import tsconfigPaths from "vite-tsconfig-paths";

// E2E (see playwright.config.ts) pins the dev-server port via FRONTEND_PORT so the
// app can detect the test context from its own port; inert for normal dev.
const frontendPort = process.env.FRONTEND_PORT ? Number(process.env.FRONTEND_PORT) : undefined;

export default defineConfig({
  plugins: [reactRouter(), tsconfigPaths()],
  server: frontendPort ? { port: frontendPort, strictPort: true } : undefined,
});

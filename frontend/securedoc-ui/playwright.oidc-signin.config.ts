import { defineConfig } from '@playwright/test';
import { E2E_PORTS } from './e2ePorts';

/**
 * OIDC sign-in regression tests (report 0007 / task 0008).
 *
 * Starts its own Vite dev server on 127.0.0.1 (port: e2ePorts.ts) in OIDC mode, so the real app is mounted under
 * React.StrictMode (development double-mount). The identity provider and the API are answered by
 * Playwright request mocks inside each test; no Keycloak, backend or database is needed.
 *
 * Run: npm run test:oidc-signin
 * Uses the installed Microsoft Edge (channel "msedge"), so no Playwright browser download is needed.
 */
const PORT = E2E_PORTS.oidcSignin;

export default defineConfig({
  testDir: './e2e-oidc',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  timeout: 60_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: `http://localhost:${PORT}`,
    channel: 'msedge',
    headless: true,
    trace: 'retain-on-failure',
    video: 'off',
  },
  webServer: {
    command: `node node_modules/vite/bin/vite.js --port ${PORT} --strictPort --host 127.0.0.1`,
    url: `http://localhost:${PORT}/`,
    reuseExistingServer: false,
    timeout: 60_000,
    env: {
      VITE_SECURITY_MODE: 'OIDC',
      VITE_OIDC_ISSUER: 'http://idp.test/realms/signin-race',
      VITE_OIDC_CLIENT_ID: 'signin-race-web',
      VITE_OIDC_REDIRECT_URI: `http://localhost:${PORT}/`,
      VITE_OIDC_SCOPE: 'openid profile email',
      VITE_CASE_FILE_UI_ENABLED: 'true',
    },
  },
});

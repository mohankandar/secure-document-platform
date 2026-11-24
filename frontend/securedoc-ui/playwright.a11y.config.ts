import { defineConfig } from '@playwright/test';
import { E2E_PORTS } from './e2ePorts';

/**
 * UI task U026: the accessibility suite. axe (WCAG 2.0 and 2.1, levels A and AA) on every main screen and state, in
 * light and dark, plus the keyboard paths and the "?" help on the Admin settings screens.
 *
 * Same harness as playwright.oidc-signin.config.ts: its own Vite dev server in OIDC mode, the identity provider and the
 * API answered by Playwright request mocks (e2e-a11y/harness.ts). No Keycloak, backend or database is needed.
 *
 * Run: npm run test:a11y (part of npm run test:standalone). Uses the installed Microsoft Edge (channel "msedge").
 * A11Y_SURVEY=<file> lists every violation per screen without failing (used for the "before" counts in the report).
 */
const PORT = E2E_PORTS.a11y;

export default defineConfig({
  testDir: './e2e-a11y',
  // The proof pictures for the U026 and U027 reports run only on request (U026_PROOF=1, U027_PROOF=before|after).
  testIgnore: [...(process.env.U026_PROOF ? [] : ['**/proof.spec.ts']), ...(process.env.U027_PROOF ? [] : ['**/proofU027.spec.ts']), ...(process.env.U028_PROOF ? [] : ['**/proofU028.spec.ts']), ...(process.env.P0029_PROOF ? [] : ['**/proof0029.spec.ts']), ...(process.env.P0030_PROOF ? [] : ['**/proof0030.spec.ts']), ...(process.env.P0057_PROOF ? [] : ['**/proof0057.spec.ts'])],
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
    viewport: { width: 1440, height: 900 },
    trace: 'retain-on-failure',
    video: 'off',
  },
  projects: [
    { name: 'light', use: { colorScheme: 'light' } },
    { name: 'dark', use: { colorScheme: 'dark' } },
  ],
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

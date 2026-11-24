import { defineConfig, devices } from '@playwright/test';
import { E2E_PORTS } from './e2ePorts';

/**
 * UI task U021: the tablet-friendly screens (Cases, the case panel, Approve, Review) in WebKit, Safari's engine,
 * with iPad device settings (touch, iPad user agent, coarse pointer) at the five sizes the task names.
 *
 * Same harness as playwright.oidc-signin.config.ts: its own Vite dev server in OIDC mode, the identity provider and the
 * API answered by Playwright request mocks inside each test. No Keycloak, backend or database is needed.
 *
 * Run: npm run test:tablet (part of npm run test:standalone).
 * Needs Playwright's WebKit browser once per machine: npx playwright install webkit.
 */
const PORT = E2E_PORTS.tablet;
const IPAD = devices['iPad (gen 7)'];
const SIZES: [number, number][] = [
  [768, 1024], // portrait
  [820, 1180], // iPad Air, portrait
  [1024, 768], // landscape
  [1180, 820], // iPad Air, landscape
  [1366, 1024], // iPad Pro, landscape
];

export default defineConfig({
  testDir: './e2e-tablet',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  timeout: 60_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: `http://localhost:${PORT}`,
    trace: 'retain-on-failure',
    video: 'off',
  },
  projects: SIZES.map(([width, height]) => ({
    name: `ipad-${width}x${height}`,
    use: { ...IPAD, browserName: 'webkit' as const, viewport: { width, height }, screen: { width, height } },
  })),
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

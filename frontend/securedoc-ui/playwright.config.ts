import { defineConfig, devices } from '@playwright/test';

/**
 * Minimal Playwright acceptance-test configuration for the LexaVault UI.
 *
 * Assumes LexaVault is already running locally:
 *   - frontend (vite dev) on http://localhost:5173
 *   - backend reachable by the UI as configured for local/demo mode
 * This config intentionally does NOT start the backend or frontend.
 */
export default defineConfig({
  testDir: './e2e',
  // Local/demo mode requires no credentials; keep runs serial and simple.
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  // LexaVault performs asynchronous document processing; allow generous but bounded waits.
  timeout: 60_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: 'http://localhost:5173',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    video: 'off',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});

import { defineConfig } from '@playwright/test';
import { E2E_PORTS } from './e2ePorts';

/**
 * Task 0035 (test layer L5): the export end-to-end run against the folder destination, on the real app.
 *
 * Not part of test:standalone or the normal build. Run it through scripts/Test-LexaVault-ExportE2E.ps1, which starts
 * a throwaway PostgreSQL container, sets the LEXAVAULT_E2E_* variables and removes the container afterwards. The
 * tests start and stop the backend jar themselves (e2e-export/stack.ts); this config starts the mock Ollama
 * (global setup) and a Vite dev server in LOCAL mode with the Case File UI, sending /api to that backend.
 * Every test is tagged @e2e-export.
 */
const PORT = E2E_PORTS.exportUi;

export default defineConfig({
  testDir: './e2e-export',
  grep: /@e2e-export/,
  globalSetup: './e2e-export/global-setup.ts',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  reporter: [['list'], ['json', { outputFile: process.env.LEXAVAULT_E2E_RESULTS || 'test-results/export-e2e.json' }]],
  timeout: 300_000,
  expect: { timeout: 20_000 },
  use: {
    baseURL: `http://localhost:${PORT}`,
    channel: 'msedge',
    headless: true,
    viewport: { width: 1440, height: 900 },
    trace: 'retain-on-failure',
    video: 'off',
  },
  webServer: {
    command: `node node_modules/vite/bin/vite.js --config vite.export-e2e.config.ts --port ${PORT} --strictPort --host 127.0.0.1`,
    url: `http://localhost:${PORT}/`,
    reuseExistingServer: false,
    timeout: 60_000,
    env: {
      VITE_SECURITY_MODE: 'LOCAL',
      VITE_CASE_FILE_UI_ENABLED: 'true',
      LEXAVAULT_E2E_APP_PORT: String(E2E_PORTS.exportApp),
    },
  },
});

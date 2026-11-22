import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * Task 0035: the dev server for the export end-to-end run (playwright.export-e2e.config.ts). The same app as
 * vite.config.ts, with /api sent to the throwaway backend the run starts (LEXAVAULT_E2E_APP_PORT, default 18035)
 * instead of the usual 8080.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: `http://localhost:${process.env.LEXAVAULT_E2E_APP_PORT || '18035'}`,
        changeOrigin: true,
      },
    },
  },
});

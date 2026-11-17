// PHASE 1 TEST ENVIRONMENT ONLY. Vite dev server for the Phase 1 screen checks:
// port 15173 (strict), /api proxied to the Phase 1 backend on 18080. Not used by the product build.
// Lives outside the frontend folder, so the React plugin is loaded from the frontend's own node_modules.
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const frontendRoot = path.resolve(here, '..', '..', 'frontend', 'securedoc-ui');
const requireFromFrontend = createRequire(path.join(frontendRoot, 'package.json'));
const reactPlugin = requireFromFrontend('@vitejs/plugin-react');
const react = reactPlugin.default ?? reactPlugin;

export default {
  root: frontendRoot,
  plugins: [react()],
  server: {
    port: 15173,
    strictPort: true,
    // Loopback IPv4 only, like the throwaway containers. Open it as http://localhost:15173/
    // (the redirect URI registered in the throwaway Keycloak).
    host: '127.0.0.1',
    proxy: {
      '/api': {
        target: 'http://localhost:18080',
        changeOrigin: true,
      },
    },
  },
};

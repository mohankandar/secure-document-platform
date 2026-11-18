// PHASE 1 TEST ENVIRONMENT ONLY. Vite settings for the Phase 1 screen checks: dev server on 15173 (strict) and
// production-build preview on 15174 (strict), both with /api proxied to the Phase 1 backend on 18080.
// Not used by the product build.
// Lives outside the frontend folder, so the React plugin is loaded from the frontend's own node_modules.
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const frontendRoot = path.resolve(here, '..', '..', 'frontend', 'securedoc-ui');
const requireFromFrontend = createRequire(path.join(frontendRoot, 'package.json'));
const reactPlugin = requireFromFrontend('@vitejs/plugin-react');
const react = reactPlugin.default ?? reactPlugin;

const phase1Proxy = {
  '/api': {
    target: 'http://localhost:18080',
    changeOrigin: true,
  },
};

export default {
  root: frontendRoot,
  plugins: [react()],
  // Production-build mode (Start-Phase1UiProd.ps1): build outside the repository, serve with `vite preview`.
  build: {
    outDir: 'C:/dev/lexavault/phase1-run/ui-dist',
    emptyOutDir: true,
  },
  preview: {
    port: 15174,
    strictPort: true,
    host: '127.0.0.1',
    proxy: phase1Proxy,
  },
  server: {
    port: 15173,
    strictPort: true,
    // Loopback IPv4 only, like the throwaway containers. Open it as http://localhost:15173/
    // (the redirect URI registered in the throwaway Keycloak).
    host: '127.0.0.1',
    proxy: phase1Proxy,
  },
};

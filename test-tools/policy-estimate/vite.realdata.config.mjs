// TASK 0020 A4 ONLY. The UI's dev server on 15180 (strict, loopback), with /api proxied to the throwaway backend that
// Invoke-PolicyEstimateCheck.ps1 -KeepRunning leaves on 18019. Not used by the product build.
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const frontendRoot = path.resolve(here, '..', '..', 'frontend', 'securedoc-ui');
const reactPlugin = createRequire(path.join(frontendRoot, 'package.json'))('@vitejs/plugin-react');
const react = reactPlugin.default ?? reactPlugin;

export default {
  root: frontendRoot,
  plugins: [react()],
  server: {
    port: 15180,
    strictPort: true,
    host: '127.0.0.1',
    // The backend allows only its configured origins; this test-only port is not one, so the proxy drops the Origin
    // header and the call reaches the backend as a same-origin request.
    proxy: {
      '/api': {
        target: 'http://localhost:18019',
        changeOrigin: true,
        configure: (proxy) => proxy.on('proxyReq', (req) => req.removeHeader('origin')),
      },
    },
  },
};

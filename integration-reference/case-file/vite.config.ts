import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Prototype build: the API is simulated in the browser (src/demo), so there is no backend proxy
// and the UI always runs with the local demo principal instead of OIDC.
export default defineConfig({
  plugins: [react()],
  define: {
    'import.meta.env.VITE_SECURITY_MODE': JSON.stringify('LOCAL'),
  },
  server: {
    port: 5174,
    strictPort: true,
    host: true,
  },
  preview: {
    port: 5174,
    strictPort: true,
  },
});

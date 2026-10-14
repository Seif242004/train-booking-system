import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  server: {
    // Fixed port: Keycloak's web-app client and the Gateway's CORS rule both name http://localhost:5173.
    // strictPort makes Vite fail instead of silently moving to another port that Keycloak would reject.
    port: 5173,
    strictPort: true,
  },
});

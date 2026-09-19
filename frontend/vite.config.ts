/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// The frontend is served from the same origin as the API in every deployment: that is what keeps the
// SameSite=Lax session cookie working and what lets the Webpay return leg land where the cookie is
// valid. In development Vite stands in for that origin and proxies the API to Spring Boot.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      // The gateway return is not part of the API but it is a backend route.
      '/payment-alert/webpay': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
})

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

function readPanelVersion(): string {
  const candidates = [
    resolve(__dirname, 'PANEL_VERSION'),
    resolve(__dirname, '../PANEL_VERSION'),
    '/PANEL_VERSION',
  ]
  for (const p of candidates) {
    try {
      const v = readFileSync(p, 'utf8').trim()
      if (v) return v
    } catch {
      /* next */
    }
  }
  return '0.0.0'
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const sameOrigin = env.VITE_API_URL === ''
  const proxyTarget = 'http://127.0.0.1:8093'

  return {
    plugins: [react()],
    define: {
      __PANEL_VERSION__: JSON.stringify(readPanelVersion()),
    },
    base: '/',
    server: {
      port: 5173,
      proxy: sameOrigin
        ? {
            '/api': proxyTarget,
            '/healthz': proxyTarget,
          }
        : undefined,
    },
    build: {
      outDir: 'dist',
      emptyOutDir: true,
      sourcemap: false,
    },
  }
})

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { defineConfig } from 'vite'
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

export default defineConfig({
  plugins: [react()],
  define: {
    __PANEL_VERSION__: JSON.stringify(readPanelVersion()),
  },
  base: '/',
  server: {
    port: 5173,
    proxy: {
      // Dev UI talks to panel port (not RPC)
      '/status.json': 'http://127.0.0.1:8093',
      '/api': 'http://127.0.0.1:8093',
      '/instance.json': 'http://127.0.0.1:8093',
      '/instances.json': 'http://127.0.0.1:8093',
      '/healthz': 'http://127.0.0.1:8093',
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: false,
  },
})

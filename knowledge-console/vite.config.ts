import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const consolePort = 5173

export default defineConfig({
  plugins: [react()],
  server: {
    host: 'localhost',
    port: consolePort,
    strictPort: true,
  },
  preview: {
    host: 'localhost',
    port: consolePort,
    strictPort: true,
  },
})

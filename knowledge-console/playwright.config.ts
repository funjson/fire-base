import { defineConfig } from '@playwright/test'
import { env } from 'node:process'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  // One serial Admin journey includes two bounded asynchronous waits (evaluation and Graph).
  timeout: 180_000,
  expect: {
    timeout: 10_000,
  },
  reporter: [['list']],
  outputDir: 'test-results',
  use: {
    baseURL: env.E2E_WEB_URL ?? 'http://localhost:5173',
    browserName: 'chromium',
    // CI may use Playwright's bundled browser; local acceptance can reuse an installed Chrome.
    channel: env.E2E_BROWSER_CHANNEL || undefined,
    headless: true,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
})

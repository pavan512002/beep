import { defineConfig } from '@playwright/test';

// Locally, PW_CHANNEL=chrome uses the installed Chrome (no browser download).
// In CI, `npx playwright install chromium` provides the bundled one.
export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.mjs',
  timeout: 30_000,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'list' : 'line',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    channel: process.env.PW_CHANNEL || undefined,
  },
  webServer: {
    command: 'node serve.mjs',
    url: 'http://127.0.0.1:4173/',
    reuseExistingServer: !process.env.CI,
  },
});

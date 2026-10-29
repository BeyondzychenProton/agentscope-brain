import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  workers: 1,
  retries: 0,
  timeout: 30_000,
  use: {
    baseURL: process.env.DEMO_URL || 'http://127.0.0.1:3000',
    browserName: 'chromium',
    headless: true,
    launchOptions: process.env.CHROMIUM_EXECUTABLE
      ? { executablePath: process.env.CHROMIUM_EXECUTABLE }
      : {},
  },
});

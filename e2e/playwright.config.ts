import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  fullyParallel: false, // tests share a running app instance; keep sequential to avoid DB races
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: 'html',

  use: {
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    trace: 'on-first-retry',
    // Follow redirects (e.g. form auth) automatically
    extraHTTPHeaders: {},
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'mobile-chrome',
      use: { ...devices['Galaxy S24'] },
    },
  ],

  // Start the app automatically if BASE_URL is not already set.
  // Remove or comment out this block when running against docker-compose.
  // webServer: {
  //   command: 'mvn quarkus:dev -pl ..',
  //   url: 'http://localhost:8080/login',
  //   reuseExistingServer: true,
  //   timeout: 120_000,
  // },
});

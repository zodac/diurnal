import { test as base, request as baseRequest, APIRequestContext, Page } from '@playwright/test';

export interface TestUser {
  email: string;
  password: string;
  displayName: string;
}

/**
 * Register a test user via the REST API and log them in via the form, storing
 * the session cookie on the given page context. Call once in beforeAll per spec.
 *
 * If the email is already registered (409), proceeds straight to login.
 */
export async function setupTestUser(page: Page, user: TestUser): Promise<void> {
  const apiCtx = await baseRequest.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
  });

  // Register (ignore 409 — user already exists from a previous run)
  await apiCtx.post('/api/auth/register', {
    data: { email: user.email, password: user.password, displayName: user.displayName },
  });

  await apiCtx.dispose();

  // Log in via the web form so Quarkus issues the encrypted lt_session cookie
  await page.goto('/login');
  await page.fill('input[name="email"]', user.email);
  await page.fill('input[name="password"]', user.password);
  await page.click('button[type="submit"]');
  await page.waitForURL('/');
}

/**
 * Log in an already-registered user via the web form, storing the session cookie
 * on the given page context. Unlike setupTestUser this does not attempt registration,
 * so it can be used to authenticate as another spec's user (e.g. the bootstrap admin).
 */
export async function loginAs(page: Page, user: TestUser): Promise<void> {
  await page.goto('/login');
  await page.fill('input[name="email"]', user.email);
  await page.fill('input[name="password"]', user.password);
  await page.click('button[type="submit"]');
  await page.waitForURL('/');
}

// ── Typed fixture extension ────────────────────────────────────────────────────

type Fixtures = {
  authenticatedPage: Page;
  testUser: TestUser;
};

/**
 * A Playwright test fixture that provides a page already authenticated as a
 * per-spec isolated test user. The user is derived from the spec file name so
 * parallel spec files never share state.
 *
 * Usage:
 *   import { test, expect } from '../helpers/fixtures';
 *   test('my test', async ({ authenticatedPage }) => { ... });
 */
export const test = base.extend<Fixtures>({
  testUser: async ({}, use, testInfo) => {
    // Derive a deterministic email from the spec file name so each spec has its own DB state
    const specName = testInfo.file.replace(/.*\/tests\//, '').replace('.spec.ts', '');
    const user: TestUser = {
      email: `e2e-${specName}@example.com`,
      password: 'testpassword123',
      displayName: `E2E ${specName}`,
    };
    await use(user);
  },

  authenticatedPage: async ({ page, testUser }, use) => {
    await setupTestUser(page, testUser);
    await use(page);
  },
});

export { expect } from '@playwright/test';

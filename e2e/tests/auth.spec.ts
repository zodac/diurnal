import { test, expect } from '@playwright/test';
import { setupTestUser } from '../helpers/fixtures';

const USER = {
  email: 'e2e-auth@example.com',
  password: 'testpassword123',
  displayName: 'E2E Auth',
};

test.describe('Authentication', () => {
  test.beforeAll(async ({ request }) => {
    // Pre-register the shared test user (409 is fine if already exists)
    await request.post('/api/auth/register', {
      data: { email: USER.email, password: USER.password, displayName: USER.displayName },
    });
  });

  test('login with valid credentials lands on dashboard and shows display name', async ({ page }) => {
    await page.goto('/login');
    await page.fill('input[name="email"]', USER.email);
    await page.fill('input[name="password"]', USER.password);
    await page.click('button[type="submit"]');

    await expect(page).toHaveURL('/');
    await expect(page.locator('header')).toContainText(USER.displayName);
  });

  test('login with wrong password stays on login page and shows error', async ({ page }) => {
    await page.goto('/login');
    await page.fill('input[name="email"]', USER.email);
    await page.fill('input[name="password"]', 'wrongpassword');
    await page.click('button[type="submit"]');

    await expect(page).toHaveURL(/\/login/);
    // Quarkus form auth redirects to /login?error on failure
    await expect(page.locator('body')).toContainText(/invalid|error/i);
  });

  test('logout clears session and redirects to login', async ({ page }) => {
    await setupTestUser(page, USER);
    await expect(page).toHaveURL('/');

    // On mobile the desktop logout button is hidden; open hamburger menu first
    const hamburger = page.locator('button[aria-label="Toggle menu"]');
    if (await hamburger.isVisible()) {
      await hamburger.click();
      await page.locator('#mobile-menu form[action="/logout"] button').click();
    } else {
      await page.locator('form[action="/logout"] button').first().click();
    }
    await expect(page).toHaveURL(/\/login/);

    // Session is gone — navigating to / redirects back to login
    await page.goto('/');
    await expect(page).toHaveURL(/\/login/);
  });

  test('register form with valid input redirects to login with registered banner', async ({ page }) => {
    const unique = `e2e-register-${Date.now()}@example.com`;
    await page.goto('/register');
    await page.fill('input[name="email"]', unique);
    await page.fill('input[name="displayName"]', 'New User');
    await page.fill('input[name="password"]', 'validpassword1');
    await page.click('button[type="submit"]');

    await expect(page).toHaveURL(/\/login\?registered/);
    await expect(page.locator('body')).toContainText(/account created/i);
  });

  test('register form with duplicate email shows error', async ({ page }) => {
    await page.goto('/register');
    await page.fill('input[name="email"]', USER.email);
    await page.fill('input[name="displayName"]', 'Dup User');
    await page.fill('input[name="password"]', 'validpassword1');
    await page.click('button[type="submit"]');

    await expect(page).toHaveURL(/\/register\?error=email_taken/);
  });

  test('register form with password too short shows error', async ({ page }) => {
    await page.goto('/register');
    await page.fill('input[name="email"]', `short-pw-${Date.now()}@example.com`);
    await page.fill('input[name="displayName"]', 'Short PW User');
    await page.fill('input[name="password"]', 'short');
    // Browser minlength="8" would block submission before the server sees it — bypass it
    await page.locator('input[name="password"]').evaluate(el => el.removeAttribute('minlength'));
    await page.click('button[type="submit"]');

    await expect(page).toHaveURL(/\/register\?error=invalid/);
  });
});

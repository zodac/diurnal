import {test, expect} from '@playwright/test';
import {setupTestUser} from '../helpers/fixtures';

const USER = {
    email: 'e2e-auth@example.com',
    password: 'test_password_123',
    displayName: 'E2E Auth',
};

test.describe('Authentication', () => {
    test.beforeAll(async ({request}) => {
        // Pre-register the shared test user (409 is fine if already exists)
        await request.post('/api/auth/register', {
            data: {email: USER.email, password: USER.password, displayName: USER.displayName},
        });
    });

    test('login with valid credentials lands on dashboard and shows display name', async ({page}) => {
        await page.goto('/login');
        await page.fill('input[name="email"]', USER.email);
        await page.fill('input[name="password"]', USER.password);
        await page.click('button[type="submit"]');

        await expect(page).toHaveURL('/');
        await expect(page.locator('header')).toContainText(USER.displayName);
    });

    test('login with wrong password stays on login page and shows error', async ({page}) => {
        await page.goto('/login');
        await page.fill('input[name="email"]', USER.email);
        await page.fill('input[name="password"]', 'wrong_password');
        await page.click('button[type="submit"]');

        await expect(page).toHaveURL(/\/login/);
        // Quarkus form auth redirects to /login?error on failure
        await expect(page.locator('body')).toContainText(/invalid|error/i);
    });

    test('logout clears session and redirects to login', async ({page}) => {
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

        // Session is gone — navigating to / redirects back to login page
        await page.goto('/');
        await expect(page).toHaveURL(/\/login/);
    });

    test('register form with valid input redirects to login with registered banner', async ({page}) => {
        const unique = `e2e-register-${Date.now()}@example.com`;
        await page.goto('/register');
        await page.fill('input[name="email"]', unique);
        await page.fill('input[name="displayName"]', 'New User');
        await page.fill('input[name="password"]', 'valid_password1');
        await page.fill('input[name="confirmPassword"]', 'valid_password1');
        await page.click('button[type="submit"]');

        await expect(page).toHaveURL(/\/login\?registered/);
        await expect(page.locator('body')).toContainText(/account created/i);
    });

    test('register form with duplicate email shows error and preserves input', async ({page}) => {
        await page.goto('/register');
        await page.fill('input[name="email"]', USER.email);
        await page.fill('input[name="displayName"]', 'Dup User');
        await page.fill('input[name="password"]', 'valid_password1');
        await page.fill('input[name="confirmPassword"]', 'valid_password1');
        await page.click('button[type="submit"]');

        // No redirect — the page re-renders with an error banner and the entered values intact.
        await expect(page).toHaveURL(/\/register$/);
        await expect(page.locator('body')).toContainText(/already registered/i);
        await expect(page.locator('input[name="email"]')).toHaveValue(USER.email);
        await expect(page.locator('input[name="displayName"]')).toHaveValue('Dup User');
    });

    test('register form with mismatched confirmation shows error', async ({page}) => {
        await page.goto('/register');
        await page.fill('input[name="email"]', `mismatch-${Date.now()}@example.com`);
        await page.fill('input[name="displayName"]', 'Mismatch User');
        await page.fill('input[name="password"]', 'valid_password1');
        await page.fill('input[name="confirmPassword"]', 'valid_password2');
        await page.click('button[type="submit"]');

        await expect(page).toHaveURL(/\/register$/);
        await expect(page.locator('body')).toContainText(/did not match/i);
    });

    test('register form submitted empty shows an error banner, not browser pop-ups', async ({page}) => {
        await page.goto('/register');
        // Submit with every field blank. `novalidate` suppresses native field pop-ups; the shared
        // client-side validator (data-validate) lists the missing fields in the error slot instead.
        await page.click('button[type="submit"]');

        await expect(page).toHaveURL(/\/register$/);
        await expect(page.locator('[data-form-errors]')).toContainText(/please fill in the following field/i);
    });

    test('login form submitted empty shows error banners, not browser pop-ups', async ({page}) => {
        await page.goto('/login');
        // Same shared validator as register: a blank submit is caught client-side and the missing
        // fields are listed in the error slot, rather than firing the browser's native pop-ups.
        await page.click('button[type="submit"]');

        await expect(page).toHaveURL(/\/login$/);
        const errors = page.locator('[data-form-errors]');
        await expect(errors).toContainText(/please fill in the following field/i);
        await expect(errors).toContainText('Email');
        await expect(errors).toContainText('Password');
    });

    test('register form accepts a short (non-empty) password', async ({page}) => {
        // There is no minimum password length — any non-empty password registers successfully.
        await page.goto('/register');
        await page.fill('input[name="email"]', `short-pw-${Date.now()}@example.com`);
        await page.fill('input[name="displayName"]', 'Short PW User');
        await page.fill('input[name="password"]', 'short');
        await page.fill('input[name="confirmPassword"]', 'short');
        await page.click('button[type="submit"]');

        await expect(page).toHaveURL(/\/login\?registered/);
    });

    test('login page with ?error=oidc shows unauthorized OIDC error banner', async ({page}) => {
        // Simulates the redirect from quarkus.oidc.authentication.error-path after IdP denies access
        await page.goto('/login?error=oidc');
        await expect(page.locator('body')).toContainText(/not authorized/i);
    });

    test('messy OIDC error URL is cleaned up to standard error=oidc', async ({page}) => {
        // When Authelia denies access it appends its own ?error=... to the error-path,
        // producing a double-? URL. The server detects this and redirects to the clean form.
        await page.goto('/login?error=oidc%3Ferror%3Daccess_denied%26error_description%3DFoo');
        await expect(page).toHaveURL(/\/login\?error=oidc$/);
        await expect(page.locator('body')).toContainText(/not authorized/i);
    });
});

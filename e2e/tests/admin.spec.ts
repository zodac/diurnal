import { test, expect } from '../helpers/fixtures';

// The fixture user (e2e-admin@example.com) registers after e2e-actions@example.com
// so they are never the first user and therefore always have the 'user' role.
test.describe('Admin access control', () => {
  // ── Navbar: Admin link visibility ─────────────────────────────────────

  test('non-admin user does not see Admin link in navbar', async ({ authenticatedPage: page }) => {
    await page.goto('/');
    // Use href selector: a:has-text("Admin") is case-insensitive and would also match
    // the display-name link for a user whose name contains "admin" (e.g. "E2E admin").
    const adminLinks = page.locator('a[href="/admin/users"]');
    await expect(adminLinks).toHaveCount(0);
  });

  // ── /admin/users access ───────────────────────────────────────────────

  test('non-admin navigating to /admin/users gets a styled 403 page', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/users');
    await expect(page.locator('h1')).toContainText('Access Denied');
  });

  test('403 page still renders the navbar so users can navigate away', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/users');
    // The Dashboard link and logout form are in the DOM. The logout form may be inside
    // the collapsed mobile hamburger menu and hidden by CSS, so we only assert attachment.
    await expect(page.locator('a[href="/"]').first()).toBeAttached();
    await expect(page.locator('form[action="/logout"]').first()).toBeAttached();
  });

  test('403 page does not contain the admin user management table', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/users');
    // The user management content should not leak through to non-admins
    await expect(page.locator('body')).not.toContainText('User Management');
  });
});

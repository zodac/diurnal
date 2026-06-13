import { test, expect, setupTestUser, TestUser } from '../helpers/fixtures';

// e2e-actions@example.com is the very first user the suite registers, so RoleAssigner
// makes them the (only, and therefore last) administrator. We authenticate as them to
// exercise the admin-only screens. setupTestUser tolerates a 409 on register, so this
// works whether actions.spec already created them (full run) or not (admin.spec alone).
const ADMIN: TestUser = {
  email: 'e2e-actions@example.com',
  password: 'testpassword123',
  displayName: 'E2E actions',
};

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

// ── Last-admin guard (regression) ────────────────────────────────────────
// Deleting/demoting the last administrator is blocked server-side with a 409 whose
// HX-Retarget points the error at #admin-error. htmx drops non-2xx swaps by default,
// so without the page's htmx:beforeSwap opt-in the user saw nothing at all.
test.describe('Last administrator cannot be removed', () => {
  test('deleting the last admin surfaces an inline error and keeps the account', async ({ page }) => {
    await setupTestUser(page, ADMIN);
    await page.goto('/admin/users');

    const adminRow = page.locator('tr', { hasText: ADMIN.email });
    await expect(adminRow).toBeVisible();

    // Click Delete → row swaps to the confirm panel, then click its destructive Delete.
    await adminRow.getByRole('button', { name: 'Delete' }).click();
    await page.locator('.dt-btn-danger').click();

    // The guard error must actually render in the banner (the bug: it never did).
    await expect(page.locator('#admin-error')).toContainText('Cannot delete the last administrator');

    // And the account must still exist after the blocked delete.
    await page.goto('/admin/users');
    await expect(page.locator('tr', { hasText: ADMIN.email })).toBeVisible();
  });

  test('demoting the last admin surfaces an inline error', async ({ page }) => {
    await setupTestUser(page, ADMIN);
    await page.goto('/admin/users');

    const adminRow = page.locator('tr', { hasText: ADMIN.email });
    await adminRow.getByRole('button', { name: 'Edit' }).click();
    await adminRow.locator('select[name="role"]').selectOption('user');
    await adminRow.getByRole('button', { name: 'Save' }).click();

    await expect(page.locator('#admin-error')).toContainText('Cannot remove the last administrator');

    // The admin role badge must remain.
    await page.goto('/admin/users');
    await expect(page.locator('tr', { hasText: ADMIN.email })).toContainText('Administrator');
  });
});

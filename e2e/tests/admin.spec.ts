import type { TestUser } from "../helpers/fixtures"
import { test, expect, registerUser, loginAs } from "../helpers/fixtures"
import { ensureSoleAdmin, ensureNotAdmin } from "../helpers/db"
import type { Page } from "@playwright/test"

// A dedicated admin user for the admin-only screens. Rather than relying on RoleAssigner's
// "first user ever = admin" rule (fragile: depends on spec order + a pristine DB), we register
// this user and promote it to admin directly in the test DB, then log in. Deterministic and
// independent of execution order or prior DB state.
const ADMIN: TestUser = {
    email: "e2e-admin-user@example.com",
    password: "test_password123",
    displayName: "E2E Admin User",
}

// Register → promote to admin in the DB → log in. The promotion must precede login because roles
// are baked into the session at authentication time (PasswordIdentityProvider).
async function loginAsAdmin(page: Page): Promise<void> {
    await registerUser(ADMIN)
    await ensureSoleAdmin(ADMIN.email)
    await loginAs(page, ADMIN)
}

// A dedicated non-admin user for the access-control tests. The per-spec fixture user would do,
// EXCEPT that the first user ever registered is auto-promoted to admin ("first user = admin"), so
// on a pristine tmpfs DB whichever worker registers first ends up an admin — making the fixture
// user's role non-deterministic. We register this user and demote it in the DB before login, so it
// is reliably a plain 'user' regardless of execution order or prior DB state.
const NON_ADMIN: TestUser = {
    email: "e2e-admin-nonadmin@example.com",
    password: "test_password123",
    displayName: "E2E Non Admin",
}

// Register → force to plain 'user' in the DB → log in. The demotion must precede login because
// roles are baked into the session at authentication time (PasswordIdentityProvider).
async function loginAsNonAdmin(page: Page): Promise<void> {
    await registerUser(NON_ADMIN)
    await ensureNotAdmin(NON_ADMIN.email)
    await loginAs(page, NON_ADMIN)
}

test.describe("Admin access control", () => {
    // ── Navbar: Admin link visibility ─────────────────────────────────────

    test("non-admin user does not see Admin link in navbar", async ({ page }) => {
        await loginAsNonAdmin(page)
        await page.goto("/")
        // Use href selector: a:has-text("Admin") is case-insensitive and would also match
        // the display-name link for a user whose name contains "admin" (e.g. "E2E admin").
        const adminLinks = page.locator('a[href="/admin/users"]')
        await expect(adminLinks).toHaveCount(0)
    })

    // ── /admin/users access ───────────────────────────────────────────────

    test("non-admin navigating to /admin/users gets a styled 403 page", async ({ page }) => {
        await loginAsNonAdmin(page)
        await page.goto("/admin/users")
        await expect(page.locator("h1")).toContainText("Access Denied")
    })

    test("403 page still renders the navbar so users can navigate away", async ({ page }) => {
        await loginAsNonAdmin(page)
        await page.goto("/admin/users")
        // The Dashboard link and logout form are in the DOM. The logout form may be inside
        // the collapsed mobile hamburger menu and hidden by CSS, so we only assert attachment.
        await expect(page.locator('a[href="/"]').first()).toBeAttached()
        await expect(page.locator('form[action="/logout"]').first()).toBeAttached()
    })

    test("403 page does not contain the admin user management table", async ({ page }) => {
        await loginAsNonAdmin(page)
        await page.goto("/admin/users")
        // The user management content should not leak through to non-admins
        await expect(page.locator("body")).not.toContainText("User Management")
    })
})

// ── Last-admin guard (regression) ────────────────────────────────────────
// Deleting/demoting the last administrator is blocked server-side with a 409 whose
// HX-Retarget points the error at #admin-error. htmx drops non-2xx swaps by default,
// so without the page's htmx:beforeSwap opt-in the user saw nothing at all.
test.describe("Last administrator cannot be removed", () => {
    test("deleting the last admin surfaces an inline error and keeps the account", async ({ page }) => {
        // This test depends on the GLOBAL invariant "exactly one admin exists", but the admin account
        // (ADMIN) is shared across both Playwright projects. Running it in two projects at once means
        // two workers drive the same shared admin row/delete endpoint concurrently — so pin it to a
        // single project. (The access-control / edit-mode tests are project-independent and still run
        // in both.) Combined with the re-assert below, the sole-admin precondition is deterministic.
        test.skip(
            test.info().project.name !== "chromium",
            "Mutates the globally-shared admin user; run in one project to avoid cross-worker races",
        )

        await loginAsAdmin(page)

        // Re-assert sole-admin immediately before the destructive action. loginAsAdmin already did
        // this, but a concurrently-running spec can leave a stray "first user = admin" account in the
        // DB after that point; a second admin would make ADMIN no longer the LAST admin and the guard
        // would not fire. Demoting here closes that window (all fixtures have registered by now).
        await ensureSoleAdmin(ADMIN.email)
        await page.goto("/admin/users")

        const adminRow = page.locator("tr", { hasText: ADMIN.email })
        await expect(adminRow).toBeVisible()

        // Click Delete → row swaps to the confirmation panel, then click its destructive Delete.
        // Hover first: view-mode actions are revealed (and clickable) only on row highlight.
        await adminRow.hover()
        await adminRow.getByRole("button", { name: "Delete" }).click()
        await page.locator(".dt-btn-danger").click()

        // The guard error must actually render in the banner (the bug: it never did).
        await expect(page.locator("#admin-error")).toContainText("Cannot delete the last administrator")

        // The rejected confirmation must disarm: the row reverts to its normal state (Edit/Delete
        // controls back, no "permanently remove" prompt) rather than staying armed.
        const adminRowAfter = page.locator("tr", { hasText: ADMIN.email })
        await expect(adminRowAfter).not.toContainText(/permanently remove/i)
        await expect(adminRowAfter.getByRole("button", { name: "Delete" })).toBeVisible()

        // And the account must still exist after the blocked delete.
        await page.goto("/admin/users")
        await expect(page.locator("tr", { hasText: ADMIN.email })).toBeVisible()
    })
})

// ── Edit-mode action buttons (consistency with the Actions table) ─────────
test.describe("User row edit mode", () => {
    test("entering edit mode swaps Edit/Delete for Save/Cancel, like the Actions table", async ({ page }) => {
        await loginAsAdmin(page)
        await page.goto("/admin/users")
        const row = page.locator("tr", { hasText: ADMIN.email })

        // View mode shows Edit + Delete.
        await expect(row.getByRole("button", { name: "Edit" })).toBeVisible()
        await expect(row.getByRole("button", { name: "Delete" })).toBeVisible()

        // Edit mode: Edit→Save, Delete→Cancel (Edit and Delete hidden), and the row gains the shared
        // `.dt-row-highlight` ring (same element the confirm-delete row uses, only the colour differs).
        await row.hover()
        await row.getByRole("button", { name: "Edit" }).click()
        await expect(row.getByRole("button", { name: "Save" })).toBeVisible()
        await expect(row.getByRole("button", { name: "Cancel" })).toBeVisible()
        await expect(row.getByRole("button", { name: "Edit" })).toBeHidden()
        await expect(row.getByRole("button", { name: "Delete" })).toBeHidden()
        await expect(row).toHaveClass(/dt-row-highlight/)

        // Cancel restores the view-mode buttons and removes the highlight.
        await row.getByRole("button", { name: "Cancel" }).click()
        await expect(row.getByRole("button", { name: "Edit" })).toBeVisible()
        await expect(row.getByRole("button", { name: "Delete" })).toBeVisible()
        await expect(row.getByRole("button", { name: "Save" })).toBeHidden()
        await expect(row.getByRole("button", { name: "Cancel" })).toBeHidden()
        await expect(row).not.toHaveClass(/dt-row-highlight/)
    })
})

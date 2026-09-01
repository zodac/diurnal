import type { TestUser } from "../helpers/fixtures"
import { test, expect, registerUser, loginAs, pinLanguage } from "../helpers/fixtures"
import { ensureSoleAdmin, ensureNotAdmin } from "../helpers/db"
import type { Page, Request } from "@playwright/test"

// A dedicated admin user for the admin-only screens. Rather than relying on RoleAssigner's
// "first user ever = admin" rule (fragile: depends on spec order + a pristine DB), we register
// this user and promote it to admin directly in the test DB, then log in. Deterministic and
// independent of execution order or prior DB state.
const ADMIN: TestUser = {
    email: "e2e-admin-user@example.com",
    password: "test_password123",
    displayName: "E2E Admin User",
}

// Register → promote to admin in the DB → log in → widen the Users page size. The promotion must
// precede login because roles are baked into the session at authentication time
// (PasswordIdentityProvider).
//
// The page size is not a detail: /admin/users page 1 shows the OLDEST rows by created_at, and the
// default size is 5. Every spec in the run registers its own fixture user against one shared database,
// so ADMIN's position in that ordering depends on how the worker pool happened to interleave — it was
// measured sitting at position 4 of 5, one row from falling off page 1 and taking every assertion here
// with it. Overriding just this section (PageSection.USERS) puts the row on page 1 for good, whatever
// order the suite runs in.
async function loginAsAdmin(page: Page): Promise<void> {
    await registerUser(ADMIN)
    await ensureSoleAdmin(ADMIN.email)
    await loginAs(page, ADMIN)
    await pinLanguage(page, "en-GB")
    const widened = await page.request.patch("/api/v1/users/me", {
        data: { preferences: { pageSizes: [{ section: "users", pageSize: 100 }] } },
    })
    if (!widened.ok()) {
        throw new Error(`could not widen the admin Users page size: HTTP ${widened.status()}`)
    }
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
    await pinLanguage(page, "en-GB")
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

    test("saving with no role change makes no request and restores view", async ({ page }) => {
        await loginAsAdmin(page)
        await page.goto("/admin/users")
        const row = page.locator("tr", { hasText: ADMIN.email })

        await row.hover()
        await row.getByRole("button", { name: "Edit" }).click()
        await expect(row.getByRole("button", { name: "Save" })).toBeVisible()

        // Save without touching the role select → no POST to /role should fire (no phantom
        // "changed role" log line, no needless UPDATE), and the row returns to view mode.
        let posted = false
        const watch = (r: Request): void => {
            if (/\/admin\/users\/\d+\/role$/.test(r.url()) && r.method() === "POST") {posted = true}
        }
        page.on("request", watch)
        await row.getByRole("button", { name: "Save" }).click()
        await page.waitForTimeout(300)
        page.off("request", watch)
        expect(posted).toBe(false)

        await expect(row.getByRole("button", { name: "Edit" })).toBeVisible()
        await expect(row.getByRole("button", { name: "Save" })).toBeHidden()
        await expect(row).not.toHaveClass(/dt-row-highlight/)
    })
})

// ── User table layout ─────────────────────────────────────────────────────────
// Both assertions here are about geometry, so they can only be made by rendering the table at a
// width where it does not fit: the /admin/users table is eight columns wide, and a half-screen
// desktop window (or any phone) is where it starts to overflow its wrap.
//
// TextFields.DISPLAY_NAME_MAX_LENGTH characters, the worst case the column has to survive - a name
// this long used to size its own column and push the trailing columns off the side of the screen.
const MAX_LENGTH_DISPLAY_NAME = "Bartholomew Fitzgerald-Montgomery Wallingford III!"

// The admin's OWN row is the one row guaranteed to be on page 1 (see loginAsAdmin), so the long name
// goes on that account rather than on a freshly registered one that the suite's other users could
// push onto a later page. Nothing else asserts this account's display name.
async function nameTheAdmin(page: Page, displayName: string): Promise<void> {
    const renamed = await page.request.patch("/api/v1/users/me", { data: { displayName } })
    if (!renamed.ok()) {
        throw new Error(`could not set the admin display name: HTTP ${renamed.status()}`)
    }
}

test.describe("User table at a width the columns cannot fit", () => {
    test.use({ viewport: { width: 800, height: 900 } })

    test("the Sign-in heading stays on one line", async ({ page }) => {
        await loginAsAdmin(page)
        await page.goto("/admin/users")

        // "Sign-in" is the only heading with an internal break opportunity (its hyphen), and auto table
        // layout took it the moment another column wanted the room - splitting the word over two lines
        // and making the whole header row taller. Measured against a heading that has no break
        // opportunity at all, so this asserts "one line" rather than a pixel height.
        const headings = page.locator("#admin-users-list .dt-head-cell")
        const signIn = headings.filter({ hasText: "Sign-in" })
        const role = headings.filter({ hasText: "Role" })
        await expect(signIn).toHaveCount(1)
        expect(await signIn.evaluate((el) => el.clientHeight)).toBe(await role.evaluate((el) => el.clientHeight))
    })

    test("a max-length display name is clipped, and hovering it reveals the whole name", async ({ page, isMobile }) => {
        await loginAsAdmin(page)
        await nameTheAdmin(page, MAX_LENGTH_DISPLAY_NAME)
        await page.goto("/admin/users")

        const name = page.locator("tr", { hasText: ADMIN.email }).locator(".dt-cell-clip")
        await expect(name).toHaveText(MAX_LENGTH_DISPLAY_NAME)   // clipped visually only - the full string stays in the DOM
        expect(await name.evaluate((el) => el.scrollWidth > el.clientWidth + 1)).toBe(true)

        // The reveal is hover-only (app.js gates it on `(hover: hover)`); the long-press path is
        // covered by truncation-tooltips.spec.ts and needs no second assertion here.
        if (isMobile === true) {
            return
        }
        await name.hover({ position: { x: 4, y: 4 }, force: true })
        await expect(page.locator(".app-tooltip-float")).toHaveText(MAX_LENGTH_DISPLAY_NAME)
    })
})

test.describe("User table at a width the columns do fit", () => {
    test.use({ viewport: { width: 1600, height: 900 } })

    test("the same display name is shown in full, with no tooltip", async ({ page }) => {
        await loginAsAdmin(page)
        await nameTheAdmin(page, MAX_LENGTH_DISPLAY_NAME)
        await page.goto("/admin/users")

        // The cap is deliberately not applied here: with the room to show the name there is nothing to
        // explain, and `data-tip-full` measures the box live, so the bubble must stay shut on hover.
        // No `scrollWidth` check: without the cap the span is inline, and an inline box reports
        // clientWidth 0 - so the meaningful assertions are that the cap is not applied and that the
        // live measurement in app.js consequently finds nothing to reveal.
        const name = page.locator("tr", { hasText: ADMIN.email }).locator(".dt-cell-clip")
        await expect(name).toHaveText(MAX_LENGTH_DISPLAY_NAME)
        expect(await name.evaluate((el) => globalThis.getComputedStyle(el).maxWidth)).toBe("none")
        await name.hover({ position: { x: 4, y: 4 }, force: true })
        await page.waitForTimeout(800)
        await expect(page.locator(".app-tooltip-float")).toHaveCount(0)
    })
})

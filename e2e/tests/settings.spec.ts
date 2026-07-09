import type { Page } from "@playwright/test"
import { test, expect } from "../helpers/fixtures"

// Each preference auto-saves via its own HTMX PATCH to /settings/<name> (e.g. /settings/theme,
// /settings/page-size) fired on the control's `change` event. That PATCH is asynchronous, so a test
// MUST wait for it to finish before reloading/navigating — otherwise the reload races the save and
// reads the stale value (the root cause of the previous flakiness).
async function waitForSave(page: Page, action: Promise<unknown>): Promise<void> {
    await Promise.all([
        page.waitForResponse(r => r.url().includes("/settings/") && r.request().method() === "PATCH"),
        action,
    ])
}

// Theme and calendar style are chosen from preview tiles backed by hidden radio inputs. Tests in
// a spec share one user, so a value may already be selected; we check the radio and always dispatch
// `change` so the htmx save fires regardless (mirroring how Playwright's selectOption behaved on the
// old <select>). Page size is now preset pills + a number field, driven directly in each test.
async function selectTile(page: Page, name: string, value: string): Promise<void> {
    await waitForSave(page, page.locator(`input[name="${name}"][value="${value}"]`).evaluate(
        (el: HTMLInputElement) => {
            el.checked = true
            el.dispatchEvent(new Event("change", { bubbles: true }))
        }))
}

// Open the Display Name field's edit mode by its Edit button. The button is scoped to
// #display-name-view because the Account card now also has a Password field with its own Edit
// button. It is revealed only on hover (opacity + pointer-events), and Playwright runs its
// pre-click hit-test *before* moving the mouse, so a plain .click() can deadlock on the container
// ("<div id=display-name-view> intercepts pointer events"). dispatchEvent fires the button's
// onclick (startEditDisplayName) directly — exactly the behaviour under test — with no hit-test.
async function clickDisplayNameEdit(page: Page): Promise<void> {
    await page.locator("#display-name-view").getByRole("button", { name: "Edit" }).dispatchEvent("click")
}

// Open the Password field's edit mode. Same hover-reveal caveat as the Display Name Edit button, so
// fire the onclick (startEditPassword) directly rather than hit-testing a hover-hidden button.
async function clickPasswordEdit(page: Page): Promise<void> {
    await page.locator("#password-view").getByRole("button", { name: "Edit" }).dispatchEvent("click")
}

test.describe("Settings page", () => {
    test("select dark theme persists across reload", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await selectTile(page, "theme", "dark")

        await page.reload()
        await expect(page.locator("html")).toHaveClass(/dark/)
        await expect(page.locator('input[name="theme"][value="dark"]')).toBeChecked()
    })

    test("select light theme persists across reload and removes dark class", async ({ authenticatedPage: page }) => {
        // Set to dark first
        await page.goto("/settings")
        await selectTile(page, "theme", "dark")
        await page.reload()

        // Now switch to light
        await page.goto("/settings")
        await selectTile(page, "theme", "light")

        await page.reload()
        await expect(page.locator("html")).not.toHaveClass(/dark/)
        await expect(page.locator('input[name="theme"][value="light"]')).toBeChecked()
    })

    test("select system theme persists across reload", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await selectTile(page, "theme", "dark")
        await page.reload()

        await page.goto("/settings")
        await selectTile(page, "theme", "system")

        await page.reload()
        await expect(page.locator('input[name="theme"][value="system"]')).toBeChecked()
    })

    test("theme picker offers exactly System, Light, and Dark tiles", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        const values = await page.locator('input[name="theme"]').evaluateAll(
            els => els.map(e => (e as HTMLInputElement).value))
        expect(values).toEqual(["system", "light", "dark"])
        const labels = await page.locator('[role="radiogroup"][aria-label="Theme"] .preview-label').allInnerTexts()
        expect(labels).toEqual(["System", "Light", "Dark"])
    })

    test("change page size to 25 via preset pill persists", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await waitForSave(page, page.locator('.page-size-pill[data-value="25"]').click())

        await page.goto("/settings")
        await expect(page.locator("#pageSize")).toHaveValue("25")
    })

    test("page size offers preset pills for the standard options", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        const values = await page.locator(".page-size-pill").evaluateAll(pills =>
            pills.map(p => (p as HTMLElement).dataset.value))
        expect(values).toEqual(["5", "10", "25", "50", "100"])
    })

    test("entering an invalid page size is rejected, shows an error, and keeps the previous value", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        // Establish a known-good value first (25 via its preset pill).
        await waitForSave(page, page.locator('.page-size-pill[data-value="25"]').click())

        // The Preferences card's status indicator (shared with the "Saved" flash).
        const indicator = page.locator(".card", { has: page.locator("#page-size-row") }).locator("[data-saved]")
        const field = page.locator("#pageSize")

        // Type an out-of-range value and commit it (blur fires the change → save).
        await field.fill("0")
        await Promise.all([
            page.waitForResponse(r =>
                r.url().includes("/settings/page-size")
                && r.request().method() === "PATCH"
                && r.status() === 422),
            field.blur(),
        ])

        // The error is shown in red and states the valid range; the field reverts to the last good value.
        await expect(indicator).toHaveClass(/text-danger/)
        await expect(indicator).toContainText(/between 1 and 100/)
        await expect(field).toHaveValue("25")

        // And the rejected value was never persisted.
        await page.goto("/settings")
        await expect(page.locator("#pageSize")).toHaveValue("25")
    })

    test("settings page shows account display name and email", async ({ authenticatedPage: page, testUser }) => {
        await page.goto("/settings")
        // Display name may differ from testUser.displayName if a prior test run changed it without
        // restoring — verify it is present and non-empty; the email is immutable and checked exactly.
        await expect(page.locator("#display-name-text")).not.toBeEmpty()
        await expect(page.locator("body")).toContainText(testUser.email)
    })

    test("display name is read-only by default with an Edit button", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await expect(page.locator("#account-form")).toBeHidden()
        await expect(page.locator("#display-name-view").getByRole("button", { name: "Edit" })).toBeVisible()
    })

    test("clicking Edit shows the input with Save and Cancel buttons", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await clickDisplayNameEdit(page)
        await expect(page.locator("#account-form")).toBeVisible()
        await expect(page.locator("#display-name-view")).toBeHidden()
        await expect(page.getByRole("button", { name: "Save" })).toBeVisible()
        await expect(page.getByRole("button", { name: "Cancel" })).toBeVisible()
    })

    test("Cancel restores read mode without saving", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        // Capture the current name before editing — it may differ from testUser.displayName if a
        // previous test run changed it. Cancel must restore exactly what was there before.
        const nameBefore = await page.locator("#display-name-text").textContent() ?? ""
        await clickDisplayNameEdit(page)
        await page.fill('input[name="displayName"]', "Should Not Save")
        await page.getByRole("button", { name: "Cancel" }).click()
        await expect(page.locator("#account-form")).toBeHidden()
        await expect(page.locator("#display-name-text")).toHaveText(nameBefore)
    })

    test("update display name persists across reload", async ({ authenticatedPage: page, testUser }) => {
        await page.goto("/settings")
        await clickDisplayNameEdit(page)
        await page.fill('input[name="displayName"]', "Updated Name")
        await Promise.all([
            page.waitForResponse(r => r.url().includes("/settings/display-name") && r.request().method() === "POST"),
            page.getByRole("button", { name: "Save" }).click(),
        ])
        await expect(page.locator("#display-name-text")).toHaveText("Updated Name")

        await page.reload()
        await expect(page.locator("#display-name-text")).toHaveText("Updated Name")

        // Restore the original display name so subsequent viewports (e.g. mobile-chrome)
        // see the expected testUser.displayName rather than this test's intermediate value.
        await clickDisplayNameEdit(page)
        await page.fill('input[name="displayName"]', testUser.displayName)
        await Promise.all([
            page.waitForResponse(r => r.url().includes("/settings/display-name") && r.request().method() === "POST"),
            page.getByRole("button", { name: "Save" }).click(),
        ])
        await expect(page.locator("#display-name-text")).toHaveText(testUser.displayName)
    })

    test("email is displayed read-only and not in a form input", async ({ authenticatedPage: page, testUser }) => {
        await page.goto("/settings")
        await expect(page.locator('input[name="email"]')).toHaveCount(0)
        await expect(page.locator("body")).toContainText(testUser.email)
    })

    // Advance step 1 → 2: the current password is verified server-side, so Next only advances once
    // /settings/password/verify accepts it. Waits on the new-password step becoming visible.
    async function passwordStep1Next(page: Page, currentPassword: string): Promise<void> {
        await page.fill("#currentPassword", currentPassword)
        await page.locator("#password-current-form").getByRole("button", { name: "Next" }).click()
        await expect(page.locator("#password-new-form")).toBeVisible()
    }

    // A path-exact match for the final (mutating) POST, since /settings/password/verify shares the prefix.
    function isPasswordCommit(url: string): boolean {
        return new URL(url).pathname === "/settings/password"
    }

    // The password change starts by asking for the CURRENT password (a hijacked session cannot silently
    // reset it), then walks new → re-enter, each step in the same slot so the row never reflows.
    test("password change asks for current password first, then new and confirm, in one slot", async ({ authenticatedPage: page, testUser }) => {
        await page.goto("/settings")
        await clickPasswordEdit(page)

        // Step 1: the current password, before anything else is offered.
        await expect(page.locator("#password-current-form")).toBeVisible()
        await expect(page.locator("#password-new-form")).toBeHidden()
        await expect(page.locator("#password-confirm-form")).toBeHidden()
        await passwordStep1Next(page, testUser.password)

        // Step 2: the new password takes the same slot; the current-password input is gone.
        await expect(page.locator("#password-current-form")).toBeHidden()
        await page.fill("#newPassword", "brand_new_secret")
        await page.locator("#password-new-form").getByRole("button", { name: "Next" }).click()

        // Step 3: re-enter, again in the same slot.
        await expect(page.locator("#password-new-form")).toBeHidden()
        await expect(page.locator("#password-confirm-form")).toBeVisible()

        // Cancel returns to the read view without saving.
        await page.locator("#password-confirm-form").getByRole("button", { name: "Cancel" }).click()
        await expect(page.locator("#password-view")).toBeVisible()
        await expect(page.locator("#password-confirm-form")).toBeHidden()
    })

    test("a wrong current password is rejected at step 1 without advancing", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await clickPasswordEdit(page)

        // The Next click posts to /settings/password/verify, which rejects the wrong current password.
        await page.fill("#currentPassword", "definitely-not-the-password")
        await Promise.all([
            page.waitForResponse(r => r.url().includes("/settings/password/verify")
                && r.request().method() === "POST" && r.status() === 422),
            page.locator("#password-current-form").getByRole("button", { name: "Next" }).click(),
        ])

        // The user never leaves step 1 — the new-password step is never offered, and nothing was saved.
        await expect(page.locator("#password-current-form")).toBeVisible()
        await expect(page.locator("#password-new-form")).toBeHidden()
        await expect(page.locator("#password-confirm-form")).toBeHidden()
    })

    test("changing the password with the correct current password persists, then restores it", async ({ authenticatedPage: page, testUser }) => {
        const newPassword = "e2e_rotated_secret_1"

        // Rotate the password using the real current one.
        await page.goto("/settings")
        await clickPasswordEdit(page)
        await passwordStep1Next(page, testUser.password)
        await page.fill("#newPassword", newPassword)
        await page.locator("#password-new-form").getByRole("button", { name: "Next" }).click()
        await page.fill("#confirmPassword", newPassword)
        await Promise.all([
            page.waitForResponse(r => isPasswordCommit(r.url())
                && r.request().method() === "POST" && r.status() === 200),
            page.locator("#password-confirm-form").getByRole("button", { name: "Save" }).click(),
        ])
        await expect(page.locator("#password-view")).toBeVisible()

        // Restore the original password so the shared fixture user still logs in for later tests. The
        // step-1 verify here also proves the just-rotated password is now the accepted "current" one.
        await clickPasswordEdit(page)
        await passwordStep1Next(page, newPassword)
        await page.fill("#newPassword", testUser.password)
        await page.locator("#password-new-form").getByRole("button", { name: "Next" }).click()
        await page.fill("#confirmPassword", testUser.password)
        await Promise.all([
            page.waitForResponse(r => isPasswordCommit(r.url())
                && r.request().method() === "POST" && r.status() === 200),
            page.locator("#password-confirm-form").getByRole("button", { name: "Save" }).click(),
        ])
        await expect(page.locator("#password-view")).toBeVisible()
    })

    test("calendar style picker offers exactly Full, Minimal, and Stacked tiles", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        const values = await page.locator('input[name="calendarView"]').evaluateAll(
            els => els.map(e => (e as HTMLInputElement).value))
        expect(values).toEqual(["full", "minimal", "stacked"])
        const labels = await page.locator('[role="radiogroup"][aria-label="Calendar style"] .preview-label').allInnerTexts()
        expect(labels).toEqual(["Full", "Minimal", "Stacked"])
    })

    test("select minimal calendar style persists across reload", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await selectTile(page, "calendarView", "minimal")

        await page.reload()
        await expect(page.locator('input[name="calendarView"][value="minimal"]')).toBeChecked()
    })

    test("select full calendar style persists across reload", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await selectTile(page, "calendarView", "minimal")

        await page.goto("/settings")
        await selectTile(page, "calendarView", "full")

        await page.reload()
        await expect(page.locator('input[name="calendarView"][value="full"]')).toBeChecked()
    })

    test("clicking a preview tile info button opens the full-size dashboard preview", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await expect(page.locator("#preview-modal")).toBeHidden()

        // The (!) affordance sits outside the radio label, so it opens the modal without changing the value.
        await page.locator('[role="radiogroup"][aria-label="Theme"] .preview-info').first().click()
        await expect(page.locator("#preview-modal")).toBeVisible()
        // The modal builds one <img> per gallery tile inside #preview-modal-imgs (cycled by the arrows
        // rather than re-fetched). The System tile is first, so its image leads. Previews are WebP named
        // page-{nova,standard}-{full,minimal,stacked}-{theme}, per font + calendar style + viewport
        // (`-mobile` variant), so allow any font/style and the optional `-mobile` suffix.
        await expect(page.locator("#preview-modal-imgs img").first())
            .toHaveAttribute("src", /page-(nova|standard)-(full|minimal|stacked)-system(-mobile)?\.webp/)

        // Escape closes it.
        await page.keyboard.press("Escape")
        await expect(page.locator("#preview-modal")).toBeHidden()
    })

    test("log out everywhere arms an in-place confirm, not a native dialog", async ({ authenticatedPage: page }) => {
        // Fail if a native confirm()/alert() ever appears — the whole point is to avoid it.
        let dialogFired = false
        page.on("dialog", (d) => { dialogFired = true; void d.dismiss() })

        await page.goto("/settings")
        const view = page.locator("#logout-all-view")
        const confirm = page.locator("#logout-all-confirm")
        await expect(view).toBeVisible()
        await expect(confirm).toBeHidden()

        // Arming reveals the confirm state (destructive action + Cancel); no dialog is shown.
        await view.getByRole("button", { name: "Log out everywhere" }).click()
        await expect(confirm).toBeVisible()
        await expect(confirm.getByRole("button", { name: "Cancel" })).toBeVisible()
        await expect(view).toBeHidden()
        expect(dialogFired).toBe(false)

        // Cancel restores the resting view without logging out.
        await confirm.getByRole("button", { name: "Cancel" }).click()
        await expect(confirm).toBeHidden()
        await expect(view).toBeVisible()
    })

    test("confirming log out everywhere ends the session and returns to login", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await page.locator("#logout-all-view").getByRole("button", { name: "Log out everywhere" }).click()
        await Promise.all([
            page.waitForURL(/\/login/),
            page.locator("#logout-all-confirm").getByRole("button", { name: "Log out everywhere" }).click(),
        ])

        // The session is revoked, so a protected page bounces straight back to /login.
        await page.goto("/")
        await expect(page).toHaveURL(/\/login/)
    })
})

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
})

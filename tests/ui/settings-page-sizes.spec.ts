import { test, expect } from "../helpers/fixtures"
import { establishNumericPref, waitForSave } from "../helpers/prefs"

// The per-section "items per page" overrides: the disclosure under Settings -> Preferences -> Items
// per page, holding one row per paginated list. A row left EMPTY follows the general preference (which
// it shows as its placeholder); the server stores an override only for the rows that carry a number.
// Kept out of settings.spec.ts because that file is at its max-lines cap; the shared save/pill helpers
// live in helpers/prefs.ts so both specs drive the controls the same way.
test.describe("Settings page - per-section page sizes", () => {
    test("per-section page sizes are collapsed until the disclosure is opened", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        const panel = page.locator("#page-size-sections-panel")
        const toggle = page.locator("#page-size-sections-toggle")

        await expect(panel).toBeHidden()
        await expect(toggle).toHaveAttribute("aria-expanded", "false")

        await toggle.click()
        await expect(panel).toBeVisible()
        await expect(toggle).toHaveAttribute("aria-expanded", "true")

        // One row per section this (non-admin) user can reach; the admin-only "Users" row is not offered.
        await expect(panel.locator("[data-page-size-section]")).toHaveCount(4)
        await expect(page.locator("#page-size-users")).toHaveCount(0)
    })

    test("a section override persists, and the Default pill clears it back to the general value", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await establishNumericPref(page, "pageSizePresets", "pageSize", "25")
        await page.locator("#page-size-sections-toggle").click()

        // An empty box showing the general value as its placeholder IS "follows the setting above".
        const field = page.locator("#page-size-actions")
        await expect(field).toHaveValue("")
        await expect(field).toHaveAttribute("placeholder", "25")

        await waitForSave(page, page.locator("#page-size-actions-presets .num-pref-pill[data-value='10']").click())
        await page.goto("/settings")
        await page.locator("#page-size-sections-toggle").click()
        await expect(page.locator("#page-size-actions")).toHaveValue("10")
        // The override is that section's alone - every other row still follows the general value.
        await expect(page.locator("#page-size-notes")).toHaveValue("")

        await waitForSave(page, page.locator("#page-size-actions-presets .num-pref-pill[data-num-pref-clear]").click())
        await page.goto("/settings")
        await page.locator("#page-size-sections-toggle").click()
        await expect(page.locator("#page-size-actions")).toHaveValue("")
    })
})

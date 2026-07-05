import type { Locator } from "@playwright/test"
import { test, expect } from "../helpers/fixtures"

// Regression guard for the Tailwind v3 → v4 migration: v4's Preflight dropped the default
// `button { cursor: pointer }` that v3 set, so every interactive control that relied on it lost the
// hand cursor. A single base rule in app.css restores it for every `button`/`[role=button]`; these
// tests assert the rendered cursor for one element of each distinct "type" that regressed (there is
// no need to cover every instance — one per type proves the shared rule reaches it).
async function expectPointer(locator: Locator): Promise<void> {
    await expect(locator).toBeVisible()
    // `globalThis` (not a bare `getComputedStyle`) so eslint's no-undef is satisfied without browser
    // globals in the lint config; at runtime inside the browser it resolves to window.getComputedStyle.
    const cursor = await locator.evaluate(el => globalThis.getComputedStyle(el).cursor)
    expect(cursor, "interactive control should show the pointer (hand) cursor").toBe("pointer")
}

test.describe("Cursor — interactive controls show the pointer", () => {
    // Type: navbar link that is actually a <button> (the "Log out" form submit). It regressed while
    // the real <a> nav links did not — this is why it looked different from the rest of the navbar.
    test("navbar Log out button", async ({ authenticatedPage: page }) => {
        await page.setViewportSize({ width: 1280, height: 800 })
        await page.goto("/")
        await expectPointer(page.locator('button:has-text("Log out")').first())
    })

    // Type: icon toggle button (the mobile hamburger).
    test("mobile hamburger toggle", async ({ authenticatedPage: page }) => {
        await page.setViewportSize({ width: 390, height: 844 })
        await page.goto("/")
        await expectPointer(page.locator('button[aria-label="Toggle menu"]'))
    })

    // Type: the log increment/decrement action buttons in the dashboard day panel. The panel only
    // renders +/− controls when the user has at least one action, so seed one first.
    test("day-panel increment button", async ({ authenticatedPage: page }) => {
        await page.goto("/actions")
        await page.evaluate(async () => {
            const params = new URLSearchParams({ name: `CursorAction${Date.now()}`, colour: "#6366f1" })
            await fetch("/actions", {
                method: "POST",
                headers: { "Content-Type": "application/x-www-form-urlencoded" },
                body: params.toString(),
            })
        })
        await page.goto("/")
        await expect(page.locator("#day-panel")).not.toContainText("Click a day to log actions")
        await expectPointer(page.locator("#day-panel").getByLabel("Increase").first())
    })

    // Type: the month/year jump-picker arrows (.cal-nav-btn) inside the calendar popup.
    test("month/year picker arrows", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await page.locator("#cal-jump").click()
        await expect(page.locator("#cal-pop")).not.toHaveClass(/hidden/)
        await expectPointer(page.locator('#cal-pop button[data-y="1"]'))
    })

    // Type: the calendar toolbar nav buttons (.cal-toolbar-btn — «‹›»). These were the "fine" ones,
    // because they hard-coded the cursor; that hard-code was removed in favour of the shared base
    // rule, so this guards that they still get the pointer from the single source of truth.
    test("calendar toolbar next-month arrow", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await expectPointer(page.locator("#cal-next"))
    })

    // Type: a plain in-content action <button> (Settings → Edit display name).
    test("settings Edit display-name button", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await expectPointer(page.locator("#display-name-view").getByRole("button", { name: "Edit" }))
    })

    // Type: the "(!)" preview-open trigger on a settings preview tile.
    test("settings preview (!) trigger", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await expectPointer(page.locator(".preview-info").first())
    })

    // Type: the preview-gallery buttons (Apply + Close + prev/next). These share the brand-button
    // look with the calendar arrows but had diverged on the cursor — assert every one now matches.
    test("preview gallery buttons (apply, close, prev, next)", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await page.locator(".preview-info").first().click()
        await expect(page.locator("#preview-modal")).toBeVisible()

        await expectPointer(page.locator("#preview-apply"))
        await expectPointer(page.locator('button[aria-label="Close preview"]'))
        await expectPointer(page.locator("#preview-prev"))
        await expectPointer(page.locator("#preview-next"))
    })
})

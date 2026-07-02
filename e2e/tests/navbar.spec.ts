import { test, expect } from "../helpers/fixtures"

test.describe("Navbar — desktop", () => {
    test("full nav links are visible and hamburger is hidden", async ({ authenticatedPage: page }) => {
        await page.setViewportSize({ width: 1280, height: 800 })
        await page.goto("/")

        await expect(page.locator('nav a:has-text("Dashboard")').first()).toBeVisible()
        await expect(page.locator('nav a:has-text("Actions")').first()).toBeVisible()
        await expect(page.locator('nav a:has-text("Stats")').first()).toBeVisible()

        // Hamburger button should not be visible at desktop width
        const hamburger = page.locator('button[aria-label*="menu"], button.hamburger, #hamburger-btn').first()
        await expect(hamburger).toBeHidden()
    })
})

test.describe("Navbar — mobile", () => {
    test.use({ viewport: { width: 390, height: 844 } }) // iPhone 14-ish

    test("hamburger is visible and nav links are hidden", async ({ authenticatedPage: page }) => {
        await page.goto("/")

        // The mobile hamburger button should be present
        await expect(page.locator("#hamburger-btn, [data-hamburger]").or(
            page.locator("button").filter({ has: page.locator("svg") }).first(),
        )).toBeVisible()

        // Inline nav links should be hidden
        await expect(page.locator('nav .hidden a:has-text("Dashboard"), nav a.hidden:has-text("Dashboard")')).toHaveCount(0)
    })

    test("clicking hamburger opens the menu", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        const hamburger = page.locator("button").filter({ has: page.locator("svg") }).first()
        await hamburger.click()

        // Mobile menu should now be visible with navigation links
        const menu = page.locator('#mobile-menu, [id*="mobile"]').first()
            .or(page.locator("nav").locator('[class*="flex-col"]').first())
        await expect(menu.locator('a:has-text("Dashboard")')).toBeVisible()
        await expect(menu.locator('a:has-text("Actions")')).toBeVisible()
        await expect(menu.locator('a:has-text("Stats")')).toBeVisible()
    })

    test("clicking hamburger again closes the menu", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        const hamburger = page.locator("button").filter({ has: page.locator("svg") }).first()
        await hamburger.click()
        await hamburger.click()

        const menu = page.locator("#mobile-menu")
        await expect(menu).toHaveAttribute("data-open", "false")
    })

    test("mobile menu contains separator between nav and user items", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        const hamburger = page.locator("button").filter({ has: page.locator("svg") }).first()
        await hamburger.click()

        // The separator (hr or a div acting as one) should appear between nav links and the user/logout section
        const menu = page.locator('#mobile-menu, [id*="mobile"]').first()
            .or(page.locator("nav").locator('[class*="flex-col"]').first())
        await expect(menu.locator('hr, [role="separator"], [class*="border-t"]')).toHaveCount(1)
    })

    test("active page is highlighted in mobile menu", async ({ authenticatedPage: page }) => {
        await page.goto("/actions")
        const hamburger = page.locator("button").filter({ has: page.locator("svg") }).first()
        await hamburger.click()

        const actionsLink = page.locator('#mobile-menu a:has-text("Actions"), [id*="mobile"] a:has-text("Actions")').first()
        await expect(actionsLink).toHaveClass(/active|indigo|font-semibold|bg-/)
    })
})

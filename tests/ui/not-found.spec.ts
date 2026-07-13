import { test, expect } from "../helpers/fixtures"

test.describe("404 page", () => {
    test.beforeAll(async ({ request }) => {
        // Ensure at least one user exists so the app is past first-run setup (409 if already present),
        // making the unauthenticated redirect target /login rather than /welcome.
        await request.post("/api/auth/register", {
            data: { email: "e2e-notfound@example.com", password: "test_password123", displayName: "NotFound" },
        })
    })

    test("unknown path unauthenticated redirects to login", async ({ page }) => {
        // A signed-out browser navigation to an unknown route is steered into the auth flow,
        // not shown a 404.
        await page.goto("/this-path-does-not-exist")
        await expect(page).toHaveURL(/\/login/)
    })

    test("unknown path authenticated shows 404 page", async ({ authenticatedPage: page }) => {
        const response = await page.goto("/this-path-does-not-exist")

        expect(response?.status()).toBe(404)
        await expect(page.locator("h1")).toContainText("Page Not Found")
    })
})

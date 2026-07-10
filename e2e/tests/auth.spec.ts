import { test, expect } from "@playwright/test"
import { setupTestUser } from "../helpers/fixtures"

const USER = {
    email: "e2e-auth@example.com",
    password: "test_password_123",
    displayName: "E2E Auth",
}

test.describe("Authentication", () => {
    test.beforeAll(async ({ request }) => {
        // Pre-register the shared test user (409 is fine if already exists)
        await request.post("/api/auth/register", {
            data: { email: USER.email, password: USER.password, displayName: USER.displayName },
        })
    })

    test("login with valid credentials lands on dashboard and shows display name", async ({ page }) => {
        await page.goto("/login")
        await page.fill('input[name="email"]', USER.email)
        await page.fill('input[name="password"]', USER.password)
        await page.click('button[type="submit"]')

        await expect(page).toHaveURL("/")
        await expect(page.locator("header")).toContainText(USER.displayName)
    })

    test("login with wrong password shows an inline error without reloading or clearing fields", async ({ page }) => {
        await page.goto("/login")
        await page.fill('input[name="email"]', USER.email)
        await page.fill('input[name="password"]', "wrong_password")

        // The form is posted via fetch (data-ajax-submit): a failed attempt surfaces an inline error
        // and lets the user amend and retry — no full-page reload, so the typed values survive.
        await page.click('button[type="submit"]')
        await expect(page.locator("[data-form-errors]")).toContainText(/invalid email or password/i)
        await expect(page).toHaveURL(/\/login$/)
        await expect(page.locator('input[name="email"]')).toHaveValue(USER.email)
        await expect(page.locator('input[name="password"]')).toHaveValue("wrong_password")

        // A minor change (the correct password) then succeeds from the same page.
        await page.fill('input[name="password"]', USER.password)
        await page.click('button[type="submit"]')
        await expect(page).toHaveURL("/")
        await expect(page.locator("header")).toContainText(USER.displayName)
    })

    test("logout clears session and redirects to login", async ({ page }) => {
        await setupTestUser(page, USER)
        await expect(page).toHaveURL("/")

        // On mobile the desktop logout button is hidden; open hamburger menu first
        const hamburger = page.locator('button[aria-label="Toggle menu"]')
        if (await hamburger.isVisible()) {
            await hamburger.click()
            await page.locator('#mobile-menu form[action="/logout"] button').click()
        } else {
            await page.locator('form[action="/logout"] button').first().click()
        }
        await expect(page).toHaveURL(/\/login/)

        // Session is gone — navigating to / redirects back to login page
        await page.goto("/")
        await expect(page).toHaveURL(/\/login/)
    })

    test("register form with valid input logs straight in and lands on the dashboard", async ({ page }) => {
        const unique = `e2e-register-${Date.now()}@example.com`
        await page.goto("/register")
        await page.fill('input[name="email"]', unique)
        await page.fill('input[name="displayName"]', "New User")
        await page.fill('input[name="password"]', "valid_password1")
        await page.fill('input[name="confirmPassword"]', "valid_password1")
        await page.click('button[type="submit"]')

        // Registration now authenticates the new account and drops it on the dashboard — no trip
        // back through the login page.
        await expect(page).toHaveURL("/")
        await expect(page.locator("header")).toContainText("New User")
    })

    test("register form with duplicate email shows error and preserves input", async ({ page }) => {
        await page.goto("/register")
        await page.fill('input[name="email"]', USER.email)
        await page.fill('input[name="displayName"]', "Dup User")
        await page.fill('input[name="password"]', "valid_password1")
        await page.fill('input[name="confirmPassword"]', "valid_password1")
        await page.click('button[type="submit"]')

        // No redirect — the banner swaps in place (via the data-ajax-errors fetch) and every entered
        // value stays intact, INCLUDING the passwords, so the user can just amend the email and
        // resubmit. This matches the login card, which also keeps the password on a failed attempt.
        await expect(page).toHaveURL(/\/register$/)
        await expect(page.locator("body")).toContainText(/already registered/i)
        await expect(page.locator('input[name="email"]')).toHaveValue(USER.email)
        await expect(page.locator('input[name="displayName"]')).toHaveValue("Dup User")
        await expect(page.locator('input[name="password"]')).toHaveValue("valid_password1")
        await expect(page.locator('input[name="confirmPassword"]')).toHaveValue("valid_password1")
    })

    test("register form with mismatched confirmation shows error", async ({ page }) => {
        await page.goto("/register")
        await page.fill('input[name="email"]', `mismatch-${Date.now()}@example.com`)
        await page.fill('input[name="displayName"]', "Mismatch User")
        await page.fill('input[name="password"]', "valid_password1")
        await page.fill('input[name="confirmPassword"]', "valid_password2")
        await page.click('button[type="submit"]')

        await expect(page).toHaveURL(/\/register$/)
        await expect(page.locator("body")).toContainText(/did not match/i)
    })

    test("register submit button is disabled until every required field is filled", async ({ page }) => {
        await page.goto("/register")
        const submit = page.locator('button[type="submit"]')
        // Blank form → the button is disabled, so an obviously incomplete submission can't be fired.
        await expect(submit).toBeDisabled()

        // Filling fields one at a time keeps it disabled until the last required field is non-blank.
        await page.fill('input[name="email"]', `enable-${Date.now()}@example.com`)
        await expect(submit).toBeDisabled()
        await page.fill('input[name="displayName"]', "Enable User")
        await expect(submit).toBeDisabled()
        await page.fill('input[name="password"]', "valid_password1")
        await expect(submit).toBeDisabled()
        await page.fill('input[name="confirmPassword"]', "valid_password1")
        await expect(submit).toBeEnabled()

        // Blanking a field re-locks the button.
        await page.fill('input[name="confirmPassword"]', "")
        await expect(submit).toBeDisabled()
    })

    test("register form submitted empty shows an error banner, not browser pop-ups", async ({ page }) => {
        await page.goto("/register")
        // The submit button is disabled while fields are blank, but the client-side validator remains a
        // backstop for anyone who bypasses that UI lock (no JS, a forced submit). Trigger the form's
        // submit directly to simulate the bypass: `novalidate` suppresses native field pop-ups, so the
        // shared validator (data-validate) lists the missing fields in the error slot instead.
        await page.locator("form[data-validate]").evaluate((form) => (form as HTMLFormElement).requestSubmit())

        await expect(page).toHaveURL(/\/register$/)
        await expect(page.locator("[data-form-errors]")).toContainText(/please fill in the following field/i)
    })

    test("login submit button is disabled until both fields are filled", async ({ page }) => {
        await page.goto("/login")
        const submit = page.locator('button[type="submit"]')
        // Blank form → the button is disabled, so an incomplete login can't be fired.
        await expect(submit).toBeDisabled()

        await page.fill('input[name="email"]', USER.email)
        await expect(submit).toBeDisabled()   // password still blank
        await page.fill('input[name="password"]', USER.password)
        await expect(submit).toBeEnabled()

        // Blanking either field re-locks the button.
        await page.fill('input[name="email"]', "")
        await expect(submit).toBeDisabled()
    })

    test("login form submitted empty shows error banners, not browser pop-ups", async ({ page }) => {
        await page.goto("/login")
        // The submit button is disabled while fields are blank, but the client-side validator stays a
        // backstop for anyone who bypasses that UI lock (no JS, a forced submit). Trigger the form's
        // submit directly to simulate the bypass: the missing fields are listed in the error slot,
        // rather than firing the browser's native pop-ups.
        await page.locator("form[data-validate]").evaluate((form) => (form as HTMLFormElement).requestSubmit())

        await expect(page).toHaveURL(/\/login$/)
        const errors = page.locator("[data-form-errors]")
        await expect(errors).toContainText(/please fill in the following field/i)
        await expect(errors).toContainText("Email")
        await expect(errors).toContainText("Password")
    })

    test("register form accepts a short (non-empty) password", async ({ page }) => {
        // There is no minimum password length — any non-empty password registers successfully.
        await page.goto("/register")
        await page.fill('input[name="email"]', `short-pw-${Date.now()}@example.com`)
        await page.fill('input[name="displayName"]', "Short PW User")
        await page.fill('input[name="password"]', "short")
        await page.fill('input[name="confirmPassword"]', "short")
        await page.click('button[type="submit"]')

        await expect(page).toHaveURL("/")
    })

    test("repeated failed register attempts keep the error banner in place without reloading", async ({ page }) => {
        // Regression: the banner used to be cleared and re-triggered on every failed attempt (a full-page
        // reload for the backend duplicate-email case), making the card "jump". The data-ajax-errors
        // handler now rewrites the banner ONLY when its markup changes, so two IDENTICAL failures must
        // leave the very same DOM node in place (no flicker); it clears only on success.
        await page.goto("/register")
        await page.fill('input[name="email"]', USER.email)
        await page.fill('input[name="displayName"]', "Dup User")
        await page.fill('input[name="password"]', "valid_password1")
        await page.fill('input[name="confirmPassword"]', "valid_password1")
        await page.click('button[type="submit"]')

        const banner = page.locator("[data-form-errors] .banner-error")
        await expect(banner).toContainText(/already registered/i)

        // Tag the live node with an EXPANDO PROPERTY, not an attribute. An attribute would change the
        // slot's innerHTML and so defeat the "only rewrite when changed" optimization under test; a JS
        // property is invisible to that innerHTML comparison and rides along only if the node itself is
        // preserved. A clear + re-add (the regression) would drop it.
        await banner.evaluate((el) => { (el as unknown as { __probe?: string }).__probe = "kept" })

        // Re-submit the identical failing input (fields are retained, so no refill needed) and wait for
        // the round-trip to FULLY settle before asserting — this is what removes the flakiness. The POST
        // completes, then showErrors() re-enables the submit button in the same synchronous block as its
        // re-render decision, so once the button is enabled again the handler has run to completion and
        // we are not racing a mid-flight DOM.
        await Promise.all([
            page.waitForResponse((r) => r.url().includes("/register") && r.request().method() === "POST"),
            page.click('button[type="submit"]'),
        ])
        await expect(page.locator('button[type="submit"]')).toBeEnabled()

        // No navigation, banner still shown, and it is the SAME node — the expando survived because the
        // identical banner was never re-rendered.
        await expect(page).toHaveURL(/\/register$/)
        await expect(banner).toContainText(/already registered/i)
        const survived = await banner.evaluate((el) => (el as unknown as { __probe?: string }).__probe)
        expect(survived, "banner DOM node must be preserved across an identical repeat failure").toBe("kept")
    })

    test("login page with ?error=oidc shows unauthorized OIDC error banner", async ({ page }) => {
        // Simulates the redirect from quarkus.oidc.authentication.error-path after IdP denies access
        await page.goto("/login?error=oidc")
        await expect(page.locator("body")).toContainText(/not authorized/i)
    })

    test("messy OIDC error URL is cleaned up to standard error=oidc", async ({ page }) => {
        // When Authelia denies access it appends its own ?error=... to the error-path,
        // producing a double-? URL. The server detects this and redirects to the clean form.
        await page.goto("/login?error=oidc%3Ferror%3Daccess_denied%26error_description%3DFoo")
        await expect(page).toHaveURL(/\/login\?error=oidc$/)
        await expect(page.locator("body")).toContainText(/not authorized/i)
    })
})

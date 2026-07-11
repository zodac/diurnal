import type { Page } from "@playwright/test"
import { test as base, request as baseRequest } from "@playwright/test"

/* global window, location -- referenced inside in-browser addInitScript/page.evaluate callbacks */

export interface TestUser {
    email: string;
    password: string;
    displayName: string;
}

/**
 * Register a test user via the REST API and log them in via the form, storing
 * the session cookie on the given page context. Call once in beforeAll per spec.
 *
 * If the email is already registered (409), proceeds straight to login.
 */
export async function setupTestUser(page: Page, user: TestUser): Promise<void> {
    await registerUser(user)
    await loginAs(page, user)
}

/**
 * Register a test user via the REST API. Idempotent: a 409 (already registered) is ignored.
 * Does NOT log in — pair with loginAs (and optionally ensureAdmin in between).
 */
export async function registerUser(user: TestUser): Promise<void> {
    const apiCtx = await baseRequest.newContext({
        baseURL: process.env.BASE_URL ?? "http://localhost:8080",
    })
    await apiCtx.post("/api/auth/register", {
        data: { email: user.email, password: user.password, displayName: user.displayName },
    })
    await apiCtx.dispose()
}

/**
 * Log in an already-registered user via the web form, storing the session cookie
 * on the given page context. Unlike setupTestUser this does not attempt registration,
 * so it can be used to authenticate as another spec's user (e.g. the bootstrap admin).
 */
export async function loginAs(page: Page, user: TestUser): Promise<void> {
    await page.goto("/login")
    await page.fill('input[name="email"]', user.email)
    await page.fill('input[name="password"]', user.password)
    await page.click('button[type="submit"]')
    await page.waitForURL("/")
}

// ── Typed fixture extension ────────────────────────────────────────────────────

interface Fixtures {
    authenticatedPage: Page;
    testUser: TestUser;
}

/**
 * Matches a console message produced by a browser-enforced Content-Security-Policy block (Chromium's
 * wording is "Refused to ... because it violates the following Content Security Policy directive").
 * Kept narrow (CSP-specific, not "any console error") so this fixture only fails a spec on an actual
 * policy regression, not on unrelated console noise.
 */
const CSP_CONSOLE_ERROR = /content security policy/i

/**
 * A Playwright test fixture that provides a page already authenticated as a
 * per-spec isolated test user. The user is derived from the spec file name so
 * parallel spec files never share state.
 *
 * Usage:
 *   import { test, expect } from '../helpers/fixtures';
 *   test('my test', async ({ authenticatedPage }) => { ... });
 */
export const test = base.extend<Fixtures>({
    // CSP regression gate: overriding the built-in `page` fixture (rather than adding a
    // separate autouse one) means every spec that requests `page` — directly or via `authenticatedPage`
    // — gets this for free, turning "watch the console, zero expected" (a one-off manual pass)
    // into a permanent regression gate. Two channels are watched: the DOM `securitypolicyviolation`
    // event (fires for every actual block, incl. ones that don't log) and the console "Refused to ..."
    // error Chromium also emits — belt-and-braces in case a future engine only surfaces one of the two.
    page: async ({ page }, use, testInfo) => {
        const violations: string[] = []
        page.on("console", msg => {
            if (msg.type() === "error" && CSP_CONSOLE_ERROR.test(msg.text())) {
                violations.push(`console: ${msg.text()}`)
            }
        })
        await page.addInitScript(() => {
            window.addEventListener("securitypolicyviolation", (e: SecurityPolicyViolationEvent) => {
                const w = window as unknown as { __cspViolations?: string[] }
                w.__cspViolations = w.__cspViolations ?? []
                w.__cspViolations.push(`${e.violatedDirective}: blocked "${e.blockedURI}" on ${location.pathname}`)
            })
        })

        await use(page)

        const domViolations = await page.evaluate(() => {
            const w = window as unknown as { __cspViolations?: string[] }
            return w.__cspViolations ?? []
        }).catch(() => [] as string[])
        violations.push(...domViolations)

        if (violations.length > 0) {
            throw new Error(`CSP violation(s) detected during "${testInfo.title}":\n${violations.join("\n")}`)
        }
    },

    // Playwright requires a fixture's first argument to use the object-destructuring pattern; this
    // fixture depends on no other fixtures, so it destructures nothing (hence the empty-pattern disable).
    // eslint-disable-next-line no-empty-pattern
    testUser: async ({}, use, testInfo) => {
        // Derive a deterministic email from the spec file name AND project so each project×spec
        // combination gets its own isolated DB user — required for parallel workers.
        const specName = testInfo.file.replace(/.*\/tests\//, "").replace(".spec.ts", "")
        const project = testInfo.project.name.toLowerCase().replace(/[^a-z0-9]+/g, "-")
        const user: TestUser = {
            email: `e2e-${specName}-${project}@example.com`,
            password: "test_password123",
            displayName: `E2E ${specName}`,
        }
        await use(user)
    },

    authenticatedPage: async ({ page, testUser }, use) => {
        await setupTestUser(page, testUser)
        await use(page)
    },
})

export { expect } from "@playwright/test"

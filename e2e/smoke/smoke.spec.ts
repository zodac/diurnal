import { test, expect } from "@playwright/test"
import { registerUser, loginAs } from "../helpers/fixtures"

/* global window -- referenced inside an in-browser page.waitForFunction callback */

// Deployment-smoke suite — runs against the REAL production Docker image (built from Dockerfile via
// docker-compose.smoke.yml) on the prod profile with a live Postgres. Unlike the E2E suite there is
// NO frozen clock and NO pre-seeded DB, so every test self-seeds and uses only the app's own UTC
// "today" (TZ=UTC in the smoke compose; the browser is pinned to UTC). The goal is to catch
// regressions that exist ONLY in the packaged runtime image — missing jlink modules (e.g. the
// java.rmi incident), non-root file permissions, distroless missing tooling, the CSS-hash + favicon
// build stages, and healthcheck wiring — NOT to re-cover feature behaviour, which the E2E suite
// already does against the dev runtime. Keep this suite small and image-focused.

// today's date as YYYY-MM-DD in UTC (matches the TZ=UTC smoke server).
function todayStr(): string {
    return new Date().toISOString().slice(0, 10)
}

// Run-scoped suffix so a re-run against the same (ephemeral, but just in case) DB never clashes.
const RUN = `smoke_${Date.now()}`

test.describe("deployment smoke", () => {

    test("health endpoint reports OK", async ({ request }) => {
        // HealthResource is a custom DB-backed liveness check (not SmallRye Health): plain-text
        // "OK" + 200 only when the database connection is valid, else 503 "DB unavailable". Asserting
        // the exact body confirms the DB-connected path, not merely that something answered 200.
        const res = await request.get("/health")
        expect(res.status()).toBe(200)
        expect((await res.text()).trim()).toBe("OK")
    })

    test("hashed stylesheet and favicon are served by the image", async ({ page, request }) => {
        await page.goto("/login")
        // The stylesheet filename is content-hashed at image build time (app.<hash>.css) and baked
        // into microprofile-config. Asserting the live <link> resolves exercises the `css` build
        // stage + the hash rewrite — a desync there would 404 here but never in dev/E2E.
        const href = await page.locator('link[rel="stylesheet"]').first().getAttribute("href")
        expect(href, "login page should link a stylesheet").toBeTruthy()
        const css = await request.get(href ?? "")
        expect(css.status(), `stylesheet ${href} should be served`).toBe(200)
        expect(css.headers()["content-type"] ?? "").toContain("css")
        // Every <script src> on the page is likewise content-hashed at image build time (htmx.<hash>.min.js
        // and app.<hash>.js, both baked into microprofile-config by the same Dockerfile rename step). Assert
        // each resolves — a hash/config desync would 404 here but never in dev/E2E (which serve un-hashed).
        const scriptSrcs = await page.locator("script[src]").evaluateAll(
            els => els.map(el => (el as HTMLScriptElement).getAttribute("src")).filter(Boolean) as string[],
        )
        expect(scriptSrcs.length, "login page should link at least one hashed script").toBeGreaterThan(0)
        for (const src of scriptSrcs) {
            const js = await request.get(src)
            expect(js.status(), `script ${src} should be served`).toBe(200)
        }
        // favicon.ico is rasterised in the `icons` build stage and served from the web root.
        expect((await request.get("/favicon.ico")).status()).toBe(200)
    })

    test("full security header set is present on the production image", async ({ request }) => {
        // The smoke tier is the natural home for this because the
        // prod hash-rename wiring (Dockerfile) differs from dev, and the FOUC script's pinned CSP hash
        // must keep matching regardless of how the *other* assets (CSS/JS filenames) get hashed. This
        // asserts directive/header presence only — SecurityHeadersFilterIT already re-derives and pins
        // the exact FOUC script hash against a live fetch, so it isn't re-verified byte-for-byte here.
        const res = await request.get("/login")
        expect(res.status()).toBe(200)
        const headers = res.headers()

        const csp = headers["content-security-policy"] ?? ""
        expect(csp).toContain("default-src 'self'")
        expect(csp).toContain("frame-ancestors 'self'")
        expect(csp).toContain("base-uri 'self'")
        expect(csp).toContain("form-action 'self'")
        expect(csp).toContain("object-src 'none'")
        expect(csp).toMatch(/script-src 'self' 'sha256-[^']+'/)
        expect(csp).toContain("script-src-attr 'none'")
        expect(csp).toMatch(/style-src 'self' 'sha256-[^']+'/)
        expect(csp).toContain("style-src-attr 'unsafe-inline'")
        expect(csp).toContain("img-src 'self'")
        expect(csp).toContain("font-src 'self'")
        expect(csp).toContain("connect-src 'self'")

        expect(headers["x-content-type-options"]).toBe("nosniff")
        expect(headers["referrer-policy"]).toBe("strict-origin-when-cross-origin")
        expect(headers["x-frame-options"]).toBe("SAMEORIGIN")
        expect(headers["cross-origin-opener-policy"]).toBe("same-origin")
        expect(headers["cross-origin-resource-policy"]).toBe("same-origin")
        expect(headers["permissions-policy"]).toBeTruthy()
    })

    test("register -> login -> create action -> log persists across reload", async ({ page }) => {
        const user = {
            email: `${RUN}@example.com`,
            password: "smoke_password123",
            displayName: "Deployment Smoke",
        }
        const actionName = `Smoke ${RUN}`

        // Registers via /api/auth/register (Bearer API) then logs in via the web form — exercising
        // both auth surfaces and the shared server-side session store (opaque tokens in Postgres).
        await registerUser(user)
        await loginAs(page, user)

        // Create an action via the HTMX form on /actions, confirm it lands in the list.
        await page.goto("/actions")
        await page.waitForFunction(() => typeof (window as {htmx?: unknown}).htmx !== "undefined")
        await page.fill('input[name="name"]', actionName)
        await Promise.all([
            page.waitForResponse(r => r.url().endsWith("/actions") && r.request().method() === "POST"),
            page.locator('form[hx-post="/actions"] button[type="submit"]').click(),
        ])
        await expect(page.locator("#action-list")).toContainText(actionName)

        // Log it once today via the dashboard day panel; the event then appears on today's cell.
        const today = todayStr()
        await page.goto("/")
        const increment = page.locator("#day-panel").getByLabel("Increase").first()
        await Promise.all([
            page.waitForResponse(r => r.url().includes("/logs/") && r.request().method() === "POST"),
            increment.click(),
        ])
        await expect(page.locator(`.d-min-cell[data-date="${today}"] .d-full-event`)).toHaveCount(1, { timeout: 5000 })

        // A fresh reload proves the log persisted to the real Postgres (not just client state) and
        // that the CalendarResource feed round-trips through the packaged runtime.
        await page.reload()
        await expect(page.locator(`.d-min-cell[data-date="${today}"] .d-full-event`)).toHaveCount(1, { timeout: 5000 })
    })
})

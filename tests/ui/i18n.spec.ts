import type { TestUser } from "../helpers/fixtures"
import { test, expect, registerUser, loginAs, pinLanguage } from "../helpers/fixtures"

/**
 * Phase 6 of I18N.md: a smoke-level pass per OFFERED language (page loads, `dir`/`lang` are correct, a
 * real translated string renders) rather than full parallel suites per language — every OTHER E2E spec
 * still runs entirely in English, now pinned explicitly (playwright.config.ts's `locale: "en-GB"` +
 * fixtures.ts's `pinLanguage`) rather than resting on it being the unstated default. This spec is the
 * one place the suite deliberately looks at non-English rendering.
 *
 * Two negotiation paths exist and are both covered (see Language.java's own Javadoc):
 *   - logged-OUT pages (login) re-negotiate from the `Accept-Language` header on every request — no
 *     cookie, nothing persisted; overridden here per `describe` block via `test.use({ locale })`.
 *   - logged-IN pages read the persisted `User.language` preference and ignore the header entirely —
 *     set here via the same `PATCH /api/v1/users/me` call `pinLanguage` (and smoke.spec.ts's own
 *     non-English test) use.
 *
 * A missing translation for a key doesn't render a literal placeholder or a bare key name — Quarkus's
 * `@MessageBundle` silently falls back to the English `@Message` default (confirmed by AppMessagesIT /
 * guarded against a gap by AppMessageCoverageTest at the unit-test tier) — so the useful E2E-level check
 * is that a STRING KNOWN TO HAVE A TRANSLATION actually renders as that translation, not the English
 * default, which is what `dashboardLabel`/`signInLabel` below assert.
 */
interface LanguageCase {
    tag: string;
    dir: "ltr" | "rtl";
    // dashboardNavLink (AppMessages) — logged-in, from the persisted User.language.
    dashboardLabel: string;
    // signIn (AppMessages) — logged-out, from the negotiated Accept-Language header.
    signInLabel: string;
}

const LANGUAGES: LanguageCase[] = [
    { tag: "en-GB", dir: "ltr", dashboardLabel: "Dashboard", signInLabel: "Sign in" },
    { tag: "en-US", dir: "ltr", dashboardLabel: "Dashboard", signInLabel: "Sign in" },
    { tag: "es-ES", dir: "ltr", dashboardLabel: "Panel", signInLabel: "Iniciar sesión" },
    { tag: "ar-SA", dir: "rtl", dashboardLabel: "لوحة التحكم", signInLabel: "تسجيل الدخول" },
    { tag: "ja-JP", dir: "ltr", dashboardLabel: "ダッシュボード", signInLabel: "ログイン" },
]

for (const lang of LANGUAGES) {
    test.describe(`i18n smoke: ${lang.tag}`, () => {
        // Only affects the `page` fixture's browser context (and so the Accept-Language header it
        // sends) — the API request contexts registerUser/pinLanguage use are unaffected, and
        // User.language is set explicitly below regardless of this override.
        test.use({ locale: lang.tag })

        test(`logged-out /login negotiates ${lang.tag} from Accept-Language`, async ({ page }) => {
            await page.goto("/login")
            await expect(page.locator("html")).toHaveAttribute("lang", lang.tag)
            await expect(page.locator("html")).toHaveAttribute("dir", lang.dir)
            await expect(page.locator('button[type="submit"]')).toContainText(lang.signInLabel)
        })

        test(`logged-in pages render ${lang.tag} from the stored preference`, async ({ page }) => {
            const user: TestUser = {
                email: `e2e-i18n-${lang.tag.toLowerCase()}@example.com`,
                password: "test_password123",
                displayName: `E2E i18n ${lang.tag}`,
            }
            await registerUser(user)
            await loginAs(page, user)
            await pinLanguage(page, lang.tag)

            await page.goto("/")
            await expect(page.locator("html")).toHaveAttribute("lang", lang.tag)
            await expect(page.locator("html")).toHaveAttribute("dir", lang.dir)
            await expect(page.locator('nav a[href="/"]').first()).toContainText(lang.dashboardLabel)
        })
    })
}

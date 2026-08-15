import type { Page } from "@playwright/test"
import { test, expect } from "../helpers/fixtures"
import { todayStr } from "../helpers/dates"

// The "Character count" preference (Settings > Notes). Display-only, and deliberately NOT absolute: the
// counter is the only thing on screen explaining an inert Save, so it comes back while a note is over its
// bound however the preference is set. That interaction spans a preference, a template and note.js, so no
// server test can see it.
//
// Split from notes.spec.ts (which covers the counter itself, at its default of ON) because that file is at
// the max-lines bound, the same reason note-drafts.spec.ts and note-search.spec.ts live on their own.
test.describe("Settings – note character count", () => {
    async function setCounterPreference(page: Page, show: boolean): Promise<void> {
        await page.evaluate(async (value: boolean) => {
            await fetch("/api/v1/users/me", {
                method: "PATCH",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ preferences: { showNoteCounter: value } }),
            })
        }, show)
    }

    // Restored through the API: the counter specs in notes.spec.ts assume the shipped default, and these
    // files share a user and DB, so a run that left it off would break them.
    test.afterEach(async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await setCounterPreference(page, true)
    })

    // Today may already carry a note from another spec; these assertions start from a known empty box.
    test.beforeEach(async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await page.evaluate(async (day: string) => {
            await fetch(`/api/v1/notes/${day}`, { method: "DELETE" })
        }, todayStr())
    })

    test("the toggle hides the counter, and the limit still applies", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")

        // The switch's own <input> is `sr-only` — a 1px clipped box sitting under the track that paints it —
        // so check()/uncheck() cannot reach it: the visible <span> takes the pointer. Clicking that span is
        // both what actually works and what a user does.
        await Promise.all([
            page.waitForResponse(r => r.url().includes("/internal/settings") && r.request().method() === "PATCH"),
            page.locator("#note-counter-row label span").click(),
        ])
        await expect(page.locator("#showNoteCounter")).not.toBeChecked()

        await page.goto("/")
        const counter = page.locator("#note-count")
        const limit = Number(await page.locator("#note-panel").getAttribute("data-note-max"))
        await expect(counter).toBeHidden()

        // Under the bound it stays gone, however much is typed.
        await page.locator("#note-input").fill("still counting, silently")
        await expect(counter).toBeHidden()
        await expect(page.locator("#note-save")).toBeEnabled()

        // Over it the counter returns, in red, because it is the only explanation for Save going inert.
        await page.locator("#note-input").fill("x".repeat(limit + 1))
        await expect(counter).toBeVisible()
        await expect(counter).toHaveClass(/note-count-over/)
        await expect(page.locator("#note-save")).toBeDisabled()

        // Back under, and it disappears again rather than staying revealed.
        await page.locator("#note-input").fill("x".repeat(limit - 1))
        await expect(counter).toBeHidden()
        await expect(page.locator("#note-save")).toBeEnabled()
    })

    test("the toggle reflects the stored preference on load", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await setCounterPreference(page, false)

        await page.goto("/settings")
        await expect(page.locator("#showNoteCounter")).not.toBeChecked()

        await setCounterPreference(page, true)
        await page.reload()
        await expect(page.locator("#showNoteCounter")).toBeChecked()
        await page.goto("/")
        await expect(page.locator("#note-count")).toBeVisible()
    })
})


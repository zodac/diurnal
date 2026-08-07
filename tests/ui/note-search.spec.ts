import type { Page } from "@playwright/test"
import { test, expect } from "../helpers/fixtures"

// Date helpers, in UTC to match the server (app.timezone=UTC under -Dall). Deliberately duplicated from
// the other note specs rather than shared, for the same reason they are: one-line expressions are not
// worth coupling two independent specs over.
function pastDateStr(daysAgo: number): string {
    const d = new Date()
    d.setUTCDate(d.getUTCDate() - daysAgo)
    return d.toISOString().slice(0, 10)
}

// The /notes page: the browse-and-search view over everything the user has written. The matching rule
// and the paging are covered by the unit tests and ITs; these pin what only a browser can show — the
// debounced live filter, the highlight surviving Qute's escaping, and the deep link landing the
// dashboard on the right day.
test.describe("Notes page – search", () => {
    // The specs share one user and DB, so every note here is written against days far enough back that
    // the dashboard specs never touch them, with a run-unique token so a re-run cannot match stale rows.
    const token = `tok${Date.now()}`
    const matchDay = pastDateStr(40)
    const otherDay = pastDateStr(41)

    async function writeNote(page: Page, date: string, content: string): Promise<void> {
        await page.evaluate(
            async ({ day, body }: { day: string; body: string }) => {
                await fetch(`/api/v1/notes/${day}`, {
                    method: "PUT",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ content: body }),
                })
            },
            { day: date, body: content },
        )
    }

    async function clearNoteOn(page: Page, date: string): Promise<void> {
        await page.evaluate(async (day: string) => {
            await fetch(`/api/v1/notes/${day}`, { method: "DELETE" })
        }, date)
    }

    test.beforeEach(async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await writeNote(page, matchDay, `Ran a 5k ${token} before work`)
        await writeNote(page, otherDay, `Swam at the pool ${token}x`)
    })

    test.afterEach(async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await clearNoteOn(page, matchDay)
        await clearNoteOn(page, otherDay)
    })

    test("the navbar links to the notes page", async ({ authenticatedPage: page }) => {
        // Desktop width explicitly: at the mobile viewport the links live inside the hamburger menu, and that
        // the entry is present in BOTH bars is navbar.spec.ts's job, not this spec's.
        await page.setViewportSize({ width: 1280, height: 800 })
        await page.goto("/")
        await page.locator('nav a:has-text("Notes")').first().click()
        await expect(page).toHaveURL(/\/notes$/)
        await expect(page.locator("h2")).toHaveText("Notes")
    })

    test("lists every note before anything is searched for", async ({ authenticatedPage: page }) => {
        await page.goto("/notes")

        await expect(page.locator(`#notes-tbody a[href="/?date=${matchDay}"]`)).toBeVisible()
        await expect(page.locator(`#notes-tbody a[href="/?date=${otherDay}"]`)).toBeVisible()
    })

    test("typing filters the list and highlights the match", async ({ authenticatedPage: page }) => {
        await page.goto("/notes")
        await page.locator("#note-search-input").fill(`5k ${token}`)

        // The list is swapped in by HTMX after the 300ms debounce.
        await expect(page.locator(`#notes-tbody a[href="/?date=${matchDay}"]`)).toBeVisible()
        await expect(page.locator(`#notes-tbody a[href="/?date=${otherDay}"]`)).toHaveCount(0)
        await expect(page.locator("#notes-tbody mark").first()).toHaveText(`5k ${token}`)
    })

    test("matching is case-insensitive", async ({ authenticatedPage: page }) => {
        await page.goto("/notes")
        await page.locator("#note-search-input").fill("RAN A 5K")

        await expect(page.locator(`#notes-tbody a[href="/?date=${matchDay}"]`)).toBeVisible()
    })

    test("a search matching nothing shows the search empty state", async ({ authenticatedPage: page }) => {
        await page.goto("/notes")
        await page.locator("#note-search-input").fill(`no-such-text-${token}`)

        await expect(page.locator("#notes-empty-row")).toHaveText("No notes match your search.")
    })

    test("a search in the URL is applied and left in the box on load", async ({ authenticatedPage: page }) => {
        await page.goto(`/notes?q=${encodeURIComponent(`5k ${token}`)}`)

        await expect(page.locator("#note-search-input")).toHaveValue(`5k ${token}`)
        await expect(page.locator(`#notes-tbody a[href="/?date=${matchDay}"]`)).toBeVisible()
        await expect(page.locator(`#notes-tbody a[href="/?date=${otherDay}"]`)).toHaveCount(0)
    })

    test("following a result opens the dashboard on that day, with the note in the box", async ({ authenticatedPage: page }) => {
        await page.goto("/notes")
        await page.locator(`#notes-tbody a[href="/?date=${matchDay}"]`).click()

        // The calendar has moved to that day's month and selected it, and the note box holds that day's
        // note — the whole point of linking to the dashboard rather than expanding the result in place.
        await expect(page.locator(`.d-min-cell[data-date="${matchDay}"]`)).toHaveClass(/d-min-selected/)
        await expect(page.locator("#note-input")).toHaveValue(`Ran a 5k ${token} before work`)

        // The ?date= is consumed on arrival: left in the address bar it would out-rank the session's own
        // selection on every reload, so clicking another day and refreshing would snap back to this one.
        await expect(page).toHaveURL(/\/$/)
    })

    test("the day a result opened survives a reload, and is not overridden by the spent link", async ({ authenticatedPage: page }) => {
        await page.goto("/notes")
        await page.locator(`#notes-tbody a[href="/?date=${matchDay}"]`).click()
        await expect(page.locator(`.d-min-cell[data-date="${matchDay}"]`)).toHaveClass(/d-min-selected/)

        // Select a different day, then reload: the reload must keep THAT day, not the one the link named.
        await page.locator(`.d-min-cell[data-date="${otherDay}"]`).click()
        await expect(page.locator(`.d-min-cell[data-date="${otherDay}"]`)).toHaveClass(/d-min-selected/)
        await page.reload()
        await expect(page.locator(`.d-min-cell[data-date="${otherDay}"]`)).toHaveClass(/d-min-selected/)
    })
})

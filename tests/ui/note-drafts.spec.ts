import { test, expect, loginAs, logout } from "../helpers/fixtures"
import { todayStr, otherDaysThisMonth } from "../helpers/dates"

// An unsaved note draft outlives a navigation but not the tab, and exactly ONE is carried — the day last
// edited. Split from notes.spec.ts (which covers the box itself) because it is a lifetime question rather
// than an editing one, and because that file is at the max-lines bound. The draft store is per-tab
// sessionStorage, so nothing here can be seen server-side: these assertions are the only cover it has.
test.describe("Dashboard – note draft retention", () => {
    // A unique note per run: the specs share one user and DB, so a fixed string could already be saved on a
    // re-run, leaving the box (correctly) not dirty.
    function freshNote(): string {
        return `Draft body ${Date.now()}`
    }

    // The day the draft is moved to. A day of the CURRENT month rather than an offset: the spec clicks back
    // to today's cell, and a relative one is not always drawn beside it (see otherDaysThisMonth).
    const OTHER_DAY = otherDaysThisMonth(1)[0]

    test.beforeEach(async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await page.evaluate(async (days: string[]) => {
            await Promise.all(days.map(day => fetch(`/api/v1/notes/${day}`, { method: "DELETE" })))
        }, [todayStr(), OTHER_DAY])
    })

    test("an unsaved note survives leaving the page, and raises no confirmation popup", async ({ authenticatedPage: page }) => {
        const todayBody = freshNote()
        const otherDayBody = `${freshNote()} (other day)`

        // The box used to raise the browser's beforeunload confirmation instead of retaining the draft.
        // Playwright dismisses dialogs automatically, so one is recorded rather than awaited — the
        // requirement is that leaving the page asks the user nothing at all.
        const dialogs: string[] = []
        page.on("dialog", d => dialogs.push(d.type()))

        await page.locator("#note-input").fill(todayBody)
        await page.locator(`.d-min-cell[data-date="${OTHER_DAY}"]`).click()
        await page.locator("#note-input").fill(otherDayBody)
        await expect(page.locator("#note-status")).toHaveText("Unsaved changes")

        // Away and back. Every page is a full load, so the box is rebuilt from nothing — the draft comes
        // back from the tab's own storage, still unsaved and still never sent to the server.
        await page.goto("/actions")
        await page.goto("/")
        await expect(page.locator("#note-input")).toHaveValue(otherDayBody)
        await expect(page.locator("#note-status")).toHaveText("Unsaved changes")
        await expect(page.locator("#note-save")).toBeEnabled()

        // Only the day last edited is carried, so the draft left on the OTHER day is gone: it survived
        // switching days within the page (the in-memory map), not the page load.
        await page.locator(`.d-min-cell[data-date="${todayStr()}"]`).click()
        await expect(page.locator("#note-input")).toHaveValue("")

        // And saving takes the draft out of the store, so the next navigation restores no unsaved state.
        await page.locator("#note-input").fill(todayBody)
        await page.locator("#note-save").click()
        await expect(page.locator("#note-status")).toHaveText("Saved")
        await page.goto("/actions")
        await page.goto("/")
        await expect(page.locator("#note-input")).toHaveValue(todayBody)
        await expect(page.locator("#note-status")).toHaveText("")

        expect(dialogs, "leaving the page must not prompt").toHaveLength(0)
    })

    test("an unsaved note is dropped by logging out", async ({ authenticatedPage: page, testUser }) => {
        await page.locator("#note-input").fill(freshNote())
        await expect(page.locator("#note-status")).toHaveText("Unsaved changes")

        // Reaching the login page ends the working session, so the draft goes with it: a half-written
        // journal entry must not be sitting in the box for whoever signs in next on this tab.
        await logout(page)
        await loginAs(page, testUser)

        await page.goto("/")
        await expect(page.locator("#note-input")).toHaveValue("")
        await expect(page.locator("#note-status")).toHaveText("")
    })
})

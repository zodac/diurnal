import type { Page } from "@playwright/test"
import { test, expect } from "../helpers/fixtures"
import { todayStr, futureDateStr, otherDaysThisMonth } from "../helpers/dates"

// The note box: a day's free-text note, written from the panel under the day logger. The write rules
// are covered by the ITs; these pin the browser-side behaviour that no server test can see — the caches,
// the dirty state, the drag handles and their lifetime.
// The day every spec here switches to when it needs one that is not today. A day of the CURRENT month
// rather than an offset, so it is drawn beside today's cell whatever the calendar date: these specs click
// back to today, and two of them assert that return is a client-side cache hit, so none can afford the
// calendar to be paged to reach it (see otherDaysThisMonth). The marker specs leave a note on this day, so
// the note-box specs clear it in their beforeEach rather than assuming it is empty.
const OTHER_DAY = otherDaysThisMonth(1)[0]

test.describe("Dashboard – note box", () => {
    // A unique note per run: the specs share one user and DB, so a fixed string would already be saved
    // on a re-run and the box would (correctly) not be dirty, leaving Save disabled.
    function freshNote(): string {
        return `Note body ${Date.now()}`
    }

    async function clearNoteOn(page: Page, date: string): Promise<void> {
        await page.evaluate(async (day: string) => {
            await fetch(`/api/v1/notes/${day}`, { method: "DELETE" })
        }, date)
    }

    test.beforeEach(async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await clearNoteOn(page, todayStr())
        await clearNoteOn(page, OTHER_DAY)
    })

    test("writing and saving a note keeps it across a date change", async ({ authenticatedPage: page }) => {
        const body = freshNote()
        await page.goto("/")
        await expect(page.locator("#note-input")).toHaveValue("")

        // Save is inert until the box is dirty, so an untouched box can't fire a pointless request.
        await expect(page.locator("#note-save")).toBeDisabled()
        await page.locator("#note-input").fill(body)
        await expect(page.locator("#note-save")).toBeEnabled()
        await expect(page.locator("#note-status")).toHaveText("Unsaved changes")

        await page.locator("#note-save").click()
        await expect(page.locator("#note-status")).toHaveText("Saved")
        await expect(page.locator("#note-save")).toBeDisabled()

        // Another day is a different note — and coming back reads from the client-side cache.
        await page.locator(`.d-min-cell[data-date="${OTHER_DAY}"]`).click()
        await expect(page.locator("#note-input")).toHaveValue("")
        await page.locator(`.d-min-cell[data-date="${todayStr()}"]`).click()
        await expect(page.locator("#note-input")).toHaveValue(body)

        // And it is really persisted, not just cached.
        await page.reload()
        await expect(page.locator("#note-input")).toHaveValue(body)
    })

    test("clearing the box and saving deletes the note", async ({ authenticatedPage: page }) => {
        const body = freshNote()
        await page.goto("/")
        await page.locator("#note-input").fill(body)
        await page.locator("#note-save").click()
        await expect(page.locator("#note-status")).toHaveText("Saved")

        await page.locator("#note-input").fill("")
        await page.locator("#note-save").click()
        await expect(page.locator("#note-status")).toHaveText("Saved")

        await page.reload()
        await expect(page.locator("#note-input")).toHaveValue("")
    })

    test("an unsaved edit survives switching away and back, and Undo discards it", async ({ authenticatedPage: page }) => {
        const body = freshNote()
        await page.goto("/")
        await page.locator("#note-input").fill(body)

        // Switching day must not silently drop a half-written note.
        await page.locator(`.d-min-cell[data-date="${OTHER_DAY}"]`).click()
        await expect(page.locator("#note-input")).toHaveValue("")
        await page.locator(`.d-min-cell[data-date="${todayStr()}"]`).click()
        await expect(page.locator("#note-input")).toHaveValue(body)
        await expect(page.locator("#note-status")).toHaveText("Unsaved changes")

        await page.locator("#note-undo").click()
        await expect(page.locator("#note-input")).toHaveValue("")
        await expect(page.locator("#note-status")).toHaveText("")
    })

    test("a note can be written for a future date, where logging is refused", async ({ authenticatedPage: page }) => {
        const body = freshNote()
        await page.goto("/")
        await page.locator(`.d-min-cell[data-date="${futureDateStr(3)}"]`).click()

        await expect(page.locator("#day-logger-panel")).toContainText("can't be logged for a future date")
        await expect(page.locator("#note-input")).toBeEnabled()

        await page.locator("#note-input").fill(body)
        await page.locator("#note-save").click()
        await expect(page.locator("#note-status")).toHaveText("Saved")

        await clearNoteOn(page, futureDateStr(3))
    })

    test("Clear empties the box without writing, and only appears when there is something to clear", async ({ authenticatedPage: page }) => {
        const body = freshNote()
        await page.goto("/")

        // Nothing stored yet, so there is nothing to clear.
        await expect(page.locator("#note-clear")).toBeHidden()

        await page.locator("#note-input").fill(body)
        await expect(page.locator("#note-clear")).toBeHidden() // still nothing STORED
        await page.locator("#note-save").click()
        await expect(page.locator("#note-status")).toHaveText("Saved")
        await expect(page.locator("#note-clear")).toBeVisible()

        // Clearing must not write: it leaves an ordinary unsaved edit for the user to commit or undo.
        const writes: string[] = []
        page.on("request", r => {
            if (r.method() === "POST" && new URL(r.url()).pathname.startsWith("/internal/notes/")) {
                writes.push(r.url())
            }
        })
        await page.locator("#note-clear").click()
        await expect(page.locator("#note-input")).toHaveValue("")
        await expect(page.locator("#note-status")).toHaveText("Unsaved changes")
        expect(writes).toHaveLength(0)

        // Undo brings the stored note back, still without a write.
        await page.locator("#note-undo").click()
        await expect(page.locator("#note-input")).toHaveValue(body)
        expect(writes).toHaveLength(0)

        // Clear then Save is what actually deletes it.
        await page.locator("#note-clear").click()
        await page.locator("#note-save").click()
        await expect(page.locator("#note-status")).toHaveText("Saved")
        await expect(page.locator("#note-clear")).toBeHidden()
        await page.reload()
        await expect(page.locator("#note-input")).toHaveValue("")
    })

    test("re-typing the stored text fires no request", async ({ authenticatedPage: page }) => {
        const body = freshNote()
        await page.goto("/")
        await page.locator("#note-input").fill(body)
        await page.locator("#note-save").click()
        await expect(page.locator("#note-status")).toHaveText("Saved")

        const writes: string[] = []
        page.on("request", r => {
            if (r.method() === "POST" && new URL(r.url()).pathname.startsWith("/internal/notes/")) {
                writes.push(r.url())
            }
        })

        // Edit away and back again: the box is no longer dirty, so Save goes inert and nothing is sent.
        await page.locator("#note-input").fill(`${body} edited`)
        await expect(page.locator("#note-save")).toBeEnabled()
        await page.locator("#note-input").fill(body)
        await expect(page.locator("#note-save")).toBeDisabled()
        await expect(page.locator("#note-status")).toHaveText("")
        expect(writes).toHaveLength(0)
    })

    test("a rejected note shows a banner and persists nothing", async ({ authenticatedPage: page }) => {
        // A zero-width space: rendered as nothing, so the shared content policy refuses it.
        const body = `Ran 5k\u200Bbefore work ${Date.now()}`
        await page.goto("/")
        await page.locator("#note-input").fill(body)
        await page.locator("#note-save").click()

        await expect(page.locator("#note-error")).toContainText("invisible or text-direction characters")

        // "Persists nothing" is asked of the SERVER (404 = the day has no note), not of the box: the refused
        // text is deliberately kept as an ordinary unsaved draft, retained across the reload, so it can be
        // corrected rather than retyped from memory.
        const status = await page.evaluate(async (day: string) => {
            const resp = await fetch(`/api/v1/notes/${day}`)
            return resp.status
        }, todayStr())
        expect(status, "the refused note must not have been stored").toBe(404)

        await page.reload()
        await expect(page.locator("#note-input")).toHaveValue(body)
        await expect(page.locator("#note-status")).toHaveText("Unsaved changes")
    })
})

// The drag handles. Geometry again, because the requirement is about what the box DOES, and because the
// resize is hand-rolled (native `resize: both` offers only a corner grip and cannot do the edges).
test.describe("Dashboard – note box resize", () => {
    test.use({ viewport: { width: 1280, height: 1000 } })

    async function dragHandle(page: Page, axis: string, dx: number, dy: number): Promise<void> {
        // Scroll first: mouse coordinates are viewport-relative, and once the box has been grown its
        // bottom-right corner sits below the fold (the note starts on the grid's second row). Without
        // this the press lands on empty space and the drag silently does nothing.
        const locator = page.locator(`[data-note-resize="${axis}"]`)
        await locator.scrollIntoViewIfNeeded()
        const handle = await locator.boundingBox()
        if (!handle) {
            throw new Error(`the ${axis} resize handle has no layout box`)
        }
        const x = handle.x + handle.width / 2
        const y = handle.y + handle.height / 2
        await page.mouse.move(x, y)
        await page.mouse.down()
        await page.mouse.move(x + dx, y + dy, { steps: 10 })
        await page.mouse.up()
    }

    async function panelBox(page: Page): Promise<{ width: number, height: number }> {
        const box = await page.locator("#note-panel").boundingBox()
        if (!box) {
            throw new Error("the note panel has no layout box")
        }
        return { width: box.width, height: box.height }
    }

    test("the right edge resizes width only, the bottom edge height only", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        const start = await panelBox(page)

        await dragHandle(page, "right", 120, 80)
        const afterRight = await panelBox(page)
        expect(afterRight.width - start.width).toBeGreaterThan(100)
        expect(Math.abs(afterRight.height - start.height)).toBeLessThanOrEqual(1)

        await page.reload()
        await dragHandle(page, "bottom", 120, 80)
        const afterBottom = await panelBox(page)
        expect(Math.abs(afterBottom.width - start.width)).toBeLessThanOrEqual(1)
        expect(afterBottom.height - start.height).toBeGreaterThan(60)
    })

    test("the corner resizes both, and the box can never shrink below its default", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        const start = await panelBox(page)

        await dragHandle(page, "corner", 160, 120)
        const grown = await panelBox(page)
        expect(grown.width).toBeGreaterThan(start.width)
        expect(grown.height).toBeGreaterThan(start.height)

        // Dragging far back past the origin must stop at the default, never below it.
        await dragHandle(page, "corner", -600, -600)
        const floored = await panelBox(page)
        expect(Math.abs(floored.width - start.width)).toBeLessThanOrEqual(1)
        expect(Math.abs(floored.height - start.height)).toBeLessThanOrEqual(1)
    })

    test("a resize is kept across a date change but reset by navigating away", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        const start = await panelBox(page)

        await dragHandle(page, "corner", 100, 90)
        const grown = await panelBox(page)

        await page.locator(`.d-min-cell[data-date="${OTHER_DAY}"]`).click()
        await expect(page.locator("#day-logger-panel")).not.toContainText("Click a day to log actions")
        const afterDateChange = await panelBox(page)
        expect(afterDateChange.width).toBeCloseTo(grown.width, 0)
        expect(afterDateChange.height).toBeCloseTo(grown.height, 0)

        // Leaving the page and returning re-runs dashboard.js against a fresh panel, so the size is gone.
        await page.goto("/actions")
        await page.goto("/")
        const afterNavigation = await panelBox(page)
        expect(Math.abs(afterNavigation.width - start.width)).toBeLessThanOrEqual(1)
        expect(Math.abs(afterNavigation.height - start.height)).toBeLessThanOrEqual(1)
    })

    test("widening the box leaves the calendar a usable width", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await dragHandle(page, "right", 2000, 0)

        const calendar = await page.locator("#calendar-wrap").boundingBox()
        if (!calendar) {
            throw new Error("the calendar has no layout box")
        }
        // The box may steal width from the fluid calendar, but never squeeze it out of existence.
        expect(calendar.width).toBeGreaterThanOrEqual(260)
    })
})

// The status line's colours are shared with the rest of the app: green for a saved acknowledgement (the
// settings cards' `text-success`) and the on-brand accent for the unsaved-changes state (the same colour
// the active navbar link uses). Asserted as computed colour, not class names, so a token change is caught.
test.describe("Dashboard – note status colours", () => {
    test("unsaved changes is on-brand, saved is green", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await page.evaluate(async (day: string) => {
            await fetch(`/api/v1/notes/${day}`, { method: "DELETE" })
        }, todayStr())
        await page.reload()

        const status = page.locator("#note-status")
        const colourOf = (locator: typeof status): Promise<string> =>
            // `globalThis` (not a bare getComputedStyle/document) satisfies eslint's no-undef without browser
            // globals in the lint config; in the browser they resolve to window.*. Same idiom as cursor.spec.ts.
            locator.evaluate((el: HTMLElement) => globalThis.getComputedStyle(el).color)

        const brand = await colourOf(page.locator("a.nav-link-active").first())

        await page.locator("#note-input").fill(`Colour check ${Date.now()}`)
        await expect(status).toHaveText("Unsaved changes")
        expect(await colourOf(status)).toBe(brand)

        // The same green the settings cards use for their own "Saved".
        const success = await page.evaluate(() => {
            const probe = globalThis.document.createElement("span")
            probe.className = "text-success"
            globalThis.document.body.appendChild(probe)
            const colour = globalThis.getComputedStyle(probe).color
            probe.remove()
            return colour
        })

        await page.locator("#note-save").click()
        await expect(status).toHaveText("Saved")
        expect(await colourOf(status)).toBe(success)
    })
})

// The calendar's green day numbers. The marker rides the shared `.d-min-cell`, so one class covers all
// three calendar styles — which is exactly what these assert, per style, as a computed colour rather than
// a class name so a token change is caught.
test.describe("Dashboard – calendar note markers", () => {
    const CALENDAR_VIEWS = ["full", "minimal", "stacked"]

    // Calendar style is a preview tile backed by a hidden radio; set it and dispatch `change` so the htmx
    // auto-save PATCH fires, then await it (the page must be on /settings).
    async function setCalendarView(page: Page, value: string): Promise<void> {
        await Promise.all([
            page.waitForResponse(r => r.url().includes("/internal/settings") && r.request().method() === "PATCH"),
            page.locator(`input[name="calendarView"][value="${value}"]`).evaluate((el: HTMLInputElement) => {
                el.checked = true
                el.dispatchEvent(new Event("change", { bubbles: true }))
            }),
        ])
    }

    // The colour the marker is CURRENTLY set to: --note-colour on #calendar-wrap, which the dashboard renders
    // from the user's noteColour preference. Returned in the `rgb(r, g, b)` form getComputedStyle().color gives.
    async function markerColour(page: Page): Promise<string> {
        return page.locator("#calendar-wrap").evaluate((el: HTMLElement) => {
            const hex = globalThis.getComputedStyle(el).getPropertyValue("--note-colour").trim()
            const probe = globalThis.document.createElement("span")
            probe.style.color = hex
            globalThis.document.body.appendChild(probe)
            const colour = globalThis.getComputedStyle(probe).color
            probe.remove()
            return colour
        })
    }

    function numberSelector(view: string): string {
        return view === "full" ? ".d-full-daynum" : ".d-min-date"
    }

    // Always read through expect.poll: the calendar repaints its whole grid when a month's events or notes
    // land (and again after the idle neighbour prefetch), so a handle resolved a moment earlier can be
    // detached by the time the callback runs — which yields an empty string rather than a colour.
    async function numberColour(page: Page, view: string, date: string): Promise<string> {
        try {
            return await page.locator(`.d-min-cell[data-date="${date}"] ${numberSelector(view)}`)
                .evaluate((el: HTMLElement) => globalThis.getComputedStyle(el).color)
        } catch {
            return ""
        }
    }

    test.afterEach(async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await setCalendarView(page, "full")
    })

    test("a day with a note shows a green number in every calendar style", async ({ authenticatedPage: page }) => {
        const [marked, plain] = otherDaysThisMonth(2)
        await page.goto("/")
        await page.evaluate(async ([withNote, without]: string[]) => {
            await fetch(`/api/v1/notes/${withNote}`, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ content: "Marked day" }),
            })
            await fetch(`/api/v1/notes/${without}`, { method: "DELETE" })
        }, [marked, plain])

        // The user's own note colour, read from the custom property the server sets on the calendar — so this
        // follows the setting rather than pinning a literal that a changed default would break.
        const noteColour = await markerColour(page)

        for (const view of CALENDAR_VIEWS) {
            await page.goto("/settings")
            await setCalendarView(page, view)
            await page.goto("/")
            await expect(page.locator(`.d-min-cell[data-date="${marked}"]`)).toHaveClass(/d-note-day/)

            await expect.poll(() => numberColour(page, view, marked)).toBe(noteColour)
            await expect.poll(() => numberColour(page, view, plain)).not.toBe(noteColour)
        }
    })

    test("the marker appears on save and goes on clear, without a reload", async ({ authenticatedPage: page }) => {
        const [day] = otherDaysThisMonth(1)
        await page.goto("/")
        await page.evaluate(async (d: string) => {
            await fetch(`/api/v1/notes/${d}`, { method: "DELETE" })
        }, day)
        await page.reload()

        const cell = page.locator(`.d-min-cell[data-date="${day}"]`)
        await cell.click()
        await expect(cell).not.toHaveClass(/d-note-day/)

        await page.locator("#note-input").fill(`Marker check ${Date.now()}`)
        await page.locator("#note-save").click()
        await expect(page.locator("#note-status")).toHaveText("Saved")
        await expect(cell).toHaveClass(/d-note-day/)

        await page.locator("#note-input").fill("")
        await page.locator("#note-save").click()
        await expect(page.locator("#note-status")).toHaveText("Saved")
        await expect(cell).not.toHaveClass(/d-note-day/)
    })

    test("today's number turns green too, in a shade legible on its brand fill", async ({ authenticatedPage: page }) => {
        // Today's number sits on a solid brand fill, where the ordinary green-600 is about 1.4:1 and simply
        // unreadable — so it turns a LIGHTENED green rather than staying white. An earlier version kept it
        // white with a thin green underline, which was so easy to miss that the marker read as broken.
        await page.goto("/")
        await page.evaluate(async (d: string) => {
            await fetch(`/api/v1/notes/${d}`, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ content: "Today's note" }),
            })
        }, todayStr())

        // Colours.readableOn(#16a34a, #6366f1): the default green-600 raised up the HSL lightness axis until it
        // clears 3:1 on the fill. Pinned as a literal on purpose — it is the derivation itself under test here.
        const onBrandGreen = "rgb(125, 237, 166)"   // #7deda6
        const todayCell = page.locator(`.d-min-cell[data-date="${todayStr()}"]`)

        for (const view of CALENDAR_VIEWS) {
            await page.goto("/settings")
            await setCalendarView(page, view)
            await page.goto("/")
            await expect(todayCell).toHaveClass(/d-note-day/)
            await expect.poll(() => numberColour(page, view, todayStr())).toBe(onBrandGreen)
        }
    })

    test("a day's note is read from cache with no request when its month is already loaded", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await page.waitForTimeout(1500) // let the idle neighbour prefetch settle

        const requests: string[] = []
        page.on("request", r => {
            if (new URL(r.url()).pathname === "/internal/notes") {
                requests.push(r.url())
            }
        })

        // Both days are in the visible month, whose notes are already resident - which is exactly why this
        // one takes OTHER_DAY rather than an offset: paging to reach an offset would fetch the month it
        // landed on, and today's cell would no longer be drawn to click back to.
        await page.locator(`.d-min-cell[data-date="${OTHER_DAY}"]`).click()
        await expect(page.locator("#day-logger-panel")).not.toContainText("Click a day to log actions")
        await page.locator(`.d-min-cell[data-date="${todayStr()}"]`).click()
        await expect(page.locator("#note-input")).toBeEnabled()

        expect(requests).toHaveLength(0)
    })
})

// The character counter. It exists because `maxlength` counts UTF-16 units and would cut an emoji-heavy
// note off at half its allowance with no explanation — so the box lets the user overrun and refuses to
// SAVE instead, with the counter as the reason.
test.describe("Dashboard – note character counter", () => {
    // These specs share a user and DB, so today may already carry a note from another test; the counter
    // assertions start from a known empty box.
    test.beforeEach(async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await page.evaluate(async (day: string) => {
            await fetch(`/api/v1/notes/${day}`, { method: "DELETE" })
        }, todayStr())
    })

    test("counts code points, shows only when typeable, and blocks Save over the limit", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        const counter = page.locator("#note-count")
        const limit = Number(await page.locator("#note-panel").getAttribute("data-note-max"))
        expect(limit).toBeGreaterThan(0)

        await expect(counter).toBeVisible()
        await expect(counter).toHaveText(`0 / ${limit.toLocaleString("en-US")}`)

        // An emoji is ONE character to a reader and to the server, though it is two UTF-16 units.
        await page.locator("#note-input").fill("💪".repeat(10))
        await expect(counter).toHaveText(`10 / ${limit.toLocaleString("en-US")}`)
        await expect(counter).not.toHaveClass(/note-count-over/)

        // Over the limit: the text is ACCEPTED into the box (so the user can see how far over and edit
        // back), the counter turns red, and Save refuses. Undo stays live — it is the way back.
        await page.locator("#note-input").fill("x".repeat(limit + 5))
        await expect(counter).toHaveText(`${(limit + 5).toLocaleString("en-US")} / ${limit.toLocaleString("en-US")}`)
        await expect(counter).toHaveClass(/note-count-over/)
        await expect(page.locator("#note-save")).toBeDisabled()
        await expect(page.locator("#note-undo")).toBeEnabled()
        expect(await page.locator("#note-input").inputValue()).toHaveLength(limit + 5)

        // Back under the limit and Save returns.
        await page.locator("#note-input").fill("x".repeat(limit - 1))
        await expect(counter).not.toHaveClass(/note-count-over/)
        await expect(page.locator("#note-save")).toBeEnabled()
    })

    test("the counter is hidden when no day is selected", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await expect(page.locator("#note-count")).toBeVisible()

        // Stepping the month clears the day selection, so there is nothing to count against.
        await page.locator("#cal-prev").click()
        await expect(page.locator("#note-count")).toBeHidden()
    })
})

// The note COLOUR setting. One picker in Settings > Notes, stored as one hex and rendered verbatim
// in both themes (like an action's colour); the only derived shade is the lightened variant today's
// brand-filled calendar cell needs, which the server computes. These pin the wiring end to end — picker
// to preference to the two places the colour is shown — which no server test can see.
test.describe("Settings – note colour", () => {
    const DEFAULT_NOTE_COLOUR = "#16a34a"

    async function setNoteColour(page: Page, value: string): Promise<void> {
        await Promise.all([
            page.waitForResponse(r => r.url().includes("/internal/settings") && r.request().method() === "PATCH"),
            page.locator("#noteColour").evaluate((el: HTMLInputElement, colour: string) => {
                el.value = colour
                el.dispatchEvent(new Event("change", { bubbles: true }))
            }, value),
        ])
    }

    // Restored through the API rather than the picker: every other marker spec pins the DEFAULT colour
    // (and the shade derived from it), so a run that left a different one would break them.
    test.afterEach(async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await page.evaluate(async (colour: string) => {
            await fetch("/api/v1/users/me", {
                method: "PATCH",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ preferences: { noteColour: colour } }),
            })
        }, DEFAULT_NOTE_COLOUR)
    })

    test("the picked colour recolours the calendar marker and the Notes statistics", async ({ authenticatedPage: page }) => {
        const [day] = otherDaysThisMonth(1)
        await page.goto("/")
        await page.evaluate(async (d: string) => {
            await fetch(`/api/v1/notes/${d}`, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ content: "Coloured day" }),
            })
        }, day)

        await page.goto("/settings")
        await expect(page.locator("#noteColour")).toHaveValue(DEFAULT_NOTE_COLOUR)
        await setNoteColour(page, "#0284c7")

        // It is really persisted, not just left in the input.
        await page.reload()
        await expect(page.locator("#noteColour")).toHaveValue("#0284c7")

        // The calendar's day number is the picked colour verbatim...
        await page.goto("/")
        // Whichever element holds the number in the calendar style in force: `full` has .d-full-daynum,
        // `minimal`/`stacked` the digits inside .d-min-date. Both take --note-colour from the same rule pair.
        const marked = page.locator(
            `.d-min-cell[data-date="${day}"] .d-full-daynum, .d-min-cell[data-date="${day}"] .d-min-date`).first()
        await expect(page.locator(`.d-min-cell[data-date="${day}"]`)).toHaveClass(/d-note-day/)
        await expect.poll(async () => {
            try {
                return await marked.evaluate((el: HTMLElement) => globalThis.getComputedStyle(el).color)
            } catch {
                return ""
            }
        }).toBe("rgb(2, 132, 199)")

        // ...and so is the Notes card's swatch on the Stats page, which is pinned first.
        await page.goto("/stats")
        await expect(page.locator(".swatch").first()).toHaveAttribute("style", /background-color:\s*#0284c7/)
    })

    test("randomising suggests a colour, saves it, and never repeats the one already set", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await setNoteColour(page, DEFAULT_NOTE_COLOUR)

        await Promise.all([
            page.waitForResponse(r => r.url().includes("/internal/settings") && r.request().method() === "PATCH"),
            page.locator("#settings-notes [data-random-colour]").click(),
        ])

        const suggested = await page.locator("#noteColour").inputValue()
        expect(suggested).toMatch(/^#[0-9a-f]{6}$/)
        expect(suggested).not.toBe(DEFAULT_NOTE_COLOUR)

        // The randomise button drops its suggestion in AND saves it — the same auto-save a hand-picked
        // colour gets, because the handler fires the input's own `change` event.
        await page.reload()
        await expect(page.locator("#noteColour")).toHaveValue(suggested)
    })

    test("the default button reverts the colour, and is inert once it already is the default", async ({ authenticatedPage: page }) => {
        const defaultBtn = page.locator("#note-colour-default")

        await page.goto("/settings")
        // Nothing to revert to on a fresh account, so the button starts inert — a click could only send
        // a save that changes nothing.
        await expect(defaultBtn).toBeDisabled()

        await setNoteColour(page, "#d946ef")
        await expect(defaultBtn).toBeEnabled()

        await Promise.all([
            page.waitForResponse(r => r.url().includes("/internal/settings") && r.request().method() === "PATCH"),
            defaultBtn.click(),
        ])
        await expect(page.locator("#noteColour")).toHaveValue(DEFAULT_NOTE_COLOUR)
        await expect(defaultBtn).toBeDisabled()

        // Reverting SAVES, exactly as picking a colour by hand does.
        await page.reload()
        await expect(page.locator("#noteColour")).toHaveValue(DEFAULT_NOTE_COLOUR)
        await expect(defaultBtn).toBeDisabled()
    })
})

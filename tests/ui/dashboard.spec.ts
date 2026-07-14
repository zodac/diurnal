import type { Page } from "@playwright/test"
import { test, expect, loginAs } from "../helpers/fixtures"

// Unique action name for this test run — kept live, so no DB unique-constraint collision
// across repeated runs or across chromium/mobile-chrome sharing the same user+DB.
// Contains 'DashAction' so toContainText('DashAction') still matches.
const DASH_NAME = `DashAction${Date.now()}`

// Helper: today's date as YYYY-MM-DD in UTC — matches the server (app.timezone=UTC under -Dall).
function todayStr(): string {
    return new Date().toISOString().slice(0, 10)
}

// Helper: a past date offset by -n days, computed entirely in UTC. Using setUTCDate/getUTCDate
// (not the local setDate/getDate) keeps the arithmetic in the same zone as toISOString(), so a
// non-UTC host (e.g. NZST) near midnight can't shift the result by a day.
function pastDateStr(daysAgo: number): string {
    const d = new Date()
    d.setUTCDate(d.getUTCDate() - daysAgo)
    return d.toISOString().slice(0, 10)
}

// Helper: a future date offset by +n days, computed in UTC (same rationale as pastDateStr). A
// small offset stays within the rendered month grid (which also shows trailing days of the next
// month), so the cell is clickable without paging the calendar.
function futureDateStr(daysAhead: number): string {
    const d = new Date()
    d.setUTCDate(d.getUTCDate() + daysAhead)
    return d.toISOString().slice(0, 10)
}

// Helper: a date guaranteed to be in the current UTC month (so its cell is a primary cell of the
// rendered month grid) and different from today. Uses the 1st, or the 2nd when today is the 1st.
function otherDayThisMonth(): string {
    const d = new Date()
    d.setUTCDate(d.getUTCDate() === 1 ? 2 : 1)
    return d.toISOString().slice(0, 10)
}

// Reset every logged action for *today* back to zero — deterministically, entirely through the API.
//
// This replaces the old approach of clicking the day panel's Decrease button in a loop and awaiting a
// POST /logs/ response, which was flaky: the panel re-renders asynchronously (initial load + the
// post-mutation cal.refresh()), so the located button could detach between the visibility check and the
// click, dispatching the click to a stale node that fired no request — and the awaited response then
// timed out the whole hook (the observed "increment twice" flake).
//
// Instead: read the (nonzero-first, paginated) day panel's action ids straight from its HTML and POST a
// `set` count=0 for each, looping until the /logs/events feed reports nothing left for today. No UI
// timing is involved, so there is no race to lose.
async function resetTodayLogs(page: Page): Promise<void> {
    await page.evaluate(async () => {
        const today = new Date().toISOString().slice(0, 10)
        // The day panel lists highest-count actions first and is paginated, so re-read page 1 and zero
        // every action it lists until the events feed for today is empty. The guard bounds the loop.
        for (let guard = 0; guard < 50; guard++) {
            const events = await (await fetch(`/logs/events?start=${today}&end=${today}`)).json()
            if (!Array.isArray(events) || events.length === 0) {break}

            const html = await (await fetch(`/logs/day/${today}`)).text()
            // Each log row wraps as id="log-<yyyy-MM-dd>-<action UUID>"; capture the UUID.
            const ids = [...html.matchAll(/id="log-\d{4}-\d{2}-\d{2}-([0-9a-f-]+)"/g)].map(m => m[1])
            await Promise.all(ids.map(id =>
                fetch(`/logs/${today}/${id}/set`, {
                    method: "POST",
                    headers: { "Content-Type": "application/x-www-form-urlencoded" },
                    body: "count=0",
                })))
        }
    })
}

// Calendar style is chosen from a preview tile backed by a hidden radio. Tests share one user, so
// the target value may already be selected — we set the radio and always dispatch `change` so the
// htmx auto-save PATCH fires regardless, then await it (the page must be on /settings).
async function setCalendarView(page: Page, value: string): Promise<void> {
    await Promise.all([
        page.waitForResponse(r => r.url().includes("/settings/calendar-view") && r.request().method() === "PATCH"),
        page.locator(`input[name="calendarView"][value="${value}"]`).evaluate(
            (el: HTMLInputElement) => {
                el.checked = true
                el.dispatchEvent(new Event("change", { bubbles: true }))
            }),
    ])
}

test.describe("Dashboard", () => {
    test.beforeEach(async ({ authenticatedPage: page }) => {
        // Create DASH_NAME if it doesn't exist yet (200 = created, 409 = already exists — both fine).
        // We never delete this action, so the unique constraint is never violated on re-runs.
        await page.goto("/actions")
        await page.evaluate(async (name: string) => {
            const params = new URLSearchParams({ name, colour: "#6366f1" })
            await fetch("/actions", {
                method: "POST",
                headers: { "Content-Type": "application/x-www-form-urlencoded" },
                body: params.toString(),
            })
        }, DASH_NAME)

        // Reset today's log counts to 0 before each test. Tests that increment/decrement need a clean
        // starting state. Done via the API (see resetTodayLogs) so it can't race the UI.
        await resetTodayLogs(page)
    })

    test("page load: today is pre-selected and day panel loads automatically", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        // Today's cell should have the selected class
        const todayCell = page.locator(`.d-min-cell[data-date="${todayStr()}"]`)
        await expect(todayCell).toHaveClass(/d-min-selected/)
        // Day panel should have loaded content (not the placeholder)
        await expect(page.locator("#day-logger-panel")).not.toContainText("Click a day to log actions")
    })

    test("click a past date loads that day in the day panel", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        const past = pastDateStr(3)
        const cell = page.locator(`.d-min-cell[data-date="${past}"]`)
        await cell.click()
        await expect(page.locator("#day-logger-panel")).toContainText("DashAction")
    })

    test("clicking a logged event loads the correct day panel", async ({ authenticatedPage: page }) => {
        // Log an action on today via the day panel first
        await page.goto("/")
        await page.locator("#day-logger-panel").getByLabel("Increase").first().click()
        const countEl = page.locator('#day-logger-panel [id^="log-"]').first().locator("input[name=count]")
        await expect(countEl).toHaveValue("1")

        // Navigate to yesterday — its count should be 0
        const past = pastDateStr(1)
        await page.locator(`.d-min-cell[data-date="${past}"]`).click()
        await expect(page.locator('#day-logger-panel [id^="log-"]').first().locator("input[name=count]")).toHaveValue("0")

        // Click the event on today's cell (clicking anywhere in the cell selects its day) to navigate back
        const event = page.locator(".d-full-event").first()
        await event.click()
        await expect(page.locator('#day-logger-panel [id^="log-"]').first().locator("input[name=count]")).toHaveValue("1")
    })

    test("increment button increases count from 0 to 1", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        const countEl = page.locator('#day-logger-panel [id^="log-"]').first().locator("input[name=count]")
        await expect(countEl).toHaveValue("0")
        await page.locator("#day-logger-panel").getByLabel("Increase").first().click()
        await expect(countEl).toHaveValue("1")
    })

    test("increment twice reaches count 2", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        const incrementBtn = page.locator("#day-logger-panel").getByLabel("Increase").first()
        await incrementBtn.click()
        await incrementBtn.click()
        const countEl = page.locator('#day-logger-panel [id^="log-"]').first().locator("input[name=count]")
        await expect(countEl).toHaveValue("2")
    })

    test("decrement from 1 reaches 0 and hides minus button", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await page.locator("#day-logger-panel").getByLabel("Increase").first().click()
        await page.locator("#day-logger-panel").getByLabel("Decrease").first().click()
        const countEl = page.locator('#day-logger-panel [id^="log-"]').first().locator("input[name=count]")
        await expect(countEl).toHaveValue("0")
        await expect(page.locator("#day-logger-panel").getByLabel("Decrease").first()).toBeHidden()
    })

    test("decrement button is hidden when count is 0", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await expect(page.locator("#day-logger-panel").getByLabel("Decrease").first()).toBeHidden()
    })

    test("calendar events refresh after logging an action", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        const today = todayStr()
        // No events for today initially (before logging)
        const eventsBefore = await page.locator(`.d-min-cell[data-date="${today}"] .d-full-event`).count()
        // Log an action
        await page.locator("#day-logger-panel").getByLabel("Increase").first().click()
        // The grid refetches its month after htmx:afterRequest — wait for the new event row
        await expect(page.locator(`.d-min-cell[data-date="${today}"] .d-full-event`)).toHaveCount(Math.max(eventsBefore + 1, 1), { timeout: 5000 })
    })

    test("full view: an action logged on the 1st is not duplicated after refetching the previous month", async ({ authenticatedPage: page }) => {
        // Regression: the `full` calendar feed fetched each month with an INCLUSIVE `end` set to the 1st of
        // the *next* month, so the 1st of every month was returned by BOTH its own month's range and the
        // preceding month's. The full-view merge APPENDS one entry per event per date, so once the preceding
        // month was fetched via the unguarded single-month path (navigation onto it, or a log-triggered
        // force-refresh while viewing it), the current month's 1st gained a duplicate copy — the same action
        // rendered twice (each count 1, so no "×N"). Fixed by ending each fetch on the month's own last day
        // (dashboard.html `monthEnd`). See the July-1 double-render bug.
        const now = new Date()
        const first = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1)).toISOString().slice(0, 10)
        const prevFirst = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1)).toISOString().slice(0, 10)

        await page.goto("/")

        // Ensure DashAction has a log on the 1st of the current month, so its cell holds exactly one event.
        const firstEvent = page.locator(`.d-min-cell[data-date="${first}"] .d-full-event`).filter({ hasText: "DashAction" })
        await page.locator(`.d-min-cell[data-date="${first}"]`).click()
        if ((await firstEvent.count()) === 0) {
            await Promise.all([
                page.waitForResponse(r => r.url().includes("/logs/events")),
                page.locator("#day-logger-panel").getByLabel("Increase").first().click(),
            ])
        }
        await expect(firstEvent).toHaveCount(1, { timeout: 5000 })

        // Go to the previous month and log there. The mutation force-refreshes the *previous* month, whose
        // range (pre-fix) overran into the 1st of the current month and re-appended its event.
        await Promise.all([
            page.waitForResponse(r => r.url().includes("/logs/events")),
            page.locator("#cal-prev").click(),
        ])
        await page.locator(`.d-min-cell[data-date="${prevFirst}"]`).click()
        await Promise.all([
            page.waitForResponse(r => r.url().includes("/logs/events")), // the force-refresh of the previous month
            page.locator("#day-logger-panel").getByLabel("Increase").first().click(),
        ])

        // Back to the current month (served from cache) — the 1st must still show exactly one event, not two.
        await page.locator("#cal-next").click()
        await expect(firstEvent).toHaveCount(1, { timeout: 5000 })
    })

    test('future date shows "future" message with no +/− buttons', async ({ authenticatedPage: page }) => {
        await page.goto("/")
        // Click a deterministic future date. A 2-day offset is always within the current month grid
        // (which renders trailing days of the next month too), avoiding the leading-day coincidence
        // where "the next month's first cell" can resolve to today near a month boundary.
        await page.locator(`.d-min-cell[data-date="${futureDateStr(2)}"]`).click()
        await expect(page.locator("#day-logger-panel")).toContainText(/future|cannot log/i)
        await expect(page.locator("#day-logger-panel").getByLabel("Increase")).toHaveCount(0)
    })

    test("jump picker: calendar icon opens month grid, click closes it, Escape closes it", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        const jumpBtn = page.locator("#cal-jump")
        await jumpBtn.click()
        const popup = page.locator("#cal-pop")
        await expect(popup).not.toHaveClass(/hidden/)

        // Escape closes
        await page.keyboard.press("Escape")
        await expect(popup).toHaveClass(/hidden/)

        // Reopen and click outside to close
        await jumpBtn.click()
        await expect(popup).not.toHaveClass(/hidden/)
        await page.locator("h2").first().click() // click outside
        await expect(popup).toHaveClass(/hidden/)
    })

    test("jump picker: year arrows change year label", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await page.locator("#cal-jump").click()
        const yearLabel = page.locator(".cal-pop-year")
        const originalYear = await yearLabel.inputValue()

        await page.locator('button[data-y="1"]').click()
        await expect(yearLabel).not.toHaveValue(originalYear)
    })

    test("stats summary card is hidden when no actions are logged", async ({ page }) => {
        // Use a fresh user with no logs
        const freshUser = {
            email: `e2e-dashboard-empty-${Date.now()}@example.com`,
            password: "test_password123",
            displayName: "Empty",
        }
        const { setupTestUser } = await import("../helpers/fixtures")
        await setupTestUser(page, freshUser)
        await page.goto("/")
        // The Stats summary card is only rendered once there are logged actions; with none, the
        // whole card (and its "View stats" CTA) is omitted rather than showing an empty state.
        await expect(page.locator('a:has-text("View stats for all actions")')).toHaveCount(0)
    })
})

// All calendar types that support month-view navigation. Add a new entry here
// when a new calendar type is introduced — the adjacent-month test below covers it automatically.
// Add new calendar view types here to automatically include them in the navigation test.
const ALL_CALENDAR_VIEWS: string[] = ["full", "minimal", "stacked"]

test.describe("Dashboard – Calendar navigation", () => {
    test.afterEach(async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await setCalendarView(page, "full")
    })

    test("clicking an other-month date navigates the calendar to that month", async ({ authenticatedPage: page }) => {
        for (const calendarView of ALL_CALENDAR_VIEWS) {
            await page.goto("/settings")
            await setCalendarView(page, calendarView)
            await page.goto("/")

            // Every style now shares one hand-rolled grid, so the "other month" cell and the title use
            // the same selectors regardless of calendarView.
            const otherCellSelector = ".d-min-cell.d-min-other"
            const titleSelector = "#cal-title"

            const otherCell = page.locator(otherCellSelector).first()
            const otherDate = await otherCell.getAttribute("data-date")
            expect(otherDate).toBeTruthy()

            const titleBefore = await page.locator(titleSelector).textContent() ?? ""
            await otherCell.click()

            // Title must change to reflect the adjacent month
            await expect(page.locator(titleSelector)).not.toHaveText(titleBefore)

            // The clicked cell must no longer carry the "other month" class
            await expect(page.locator(`.d-min-cell[data-date="${otherDate}"]`)).not.toHaveClass(/d-min-other/)
        }
    })
})

test.describe("Dashboard – Minimal calendar", () => {
    // Switch to minimal view before each test; reset after so the outer describe is unaffected.
    test.beforeEach(async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await setCalendarView(page, "minimal")
    })

    test.afterEach(async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await setCalendarView(page, "full")
    })

    test("minimal calendar renders the shared grid (no legacy #calendar mount)", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await expect(page.locator("#d-min-grid")).toBeVisible()
        await expect(page.locator("#calendar")).toHaveCount(0) // the old FullCalendar mount is gone for every style
    })

    test("today cell carries the today highlight class", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await expect(page.locator(`.d-min-cell[data-date="${todayStr()}"]`)).toHaveClass(/d-min-today/)
    })

    test("today is pre-selected and day panel loads automatically", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await expect(page.locator("#day-logger-panel")).not.toContainText("Click a day to log actions")
    })

    test("clicking a past date loads that day in the day panel", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        const past = pastDateStr(3)
        await page.locator(`.d-min-cell[data-date="${past}"]`).click()
        await expect(page.locator("#day-logger-panel")).not.toContainText("Click a day to log actions")
    })

    test("clicked date receives the selected class", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        const past = pastDateStr(2)
        await page.locator(`.d-min-cell[data-date="${past}"]`).click()
        await expect(page.locator(`.d-min-cell[data-date="${past}"]`)).toHaveClass(/d-min-selected/)
    })

    test("dot appears under today after logging an action", async ({ authenticatedPage: page }) => {
        // Ensure today's log is at 0 first (deterministic API reset before loading the page).
        await resetTodayLogs(page)
        await page.goto("/")

        const today = todayStr()
        await expect(page.locator(`.d-min-cell[data-date="${today}"] .d-min-dot`)).toHaveCount(0)

        await Promise.all([
            page.waitForResponse(r => r.url().includes("/logs/") && r.request().method() === "POST"),
            page.locator("#day-logger-panel").getByLabel("Increase").first().click(),
        ])

        await expect(page.locator(`.d-min-cell[data-date="${today}"] .d-min-dot`)).toHaveCount(1, { timeout: 5000 })
    })

    test("jump picker opens and closes with Escape", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await page.locator("#cal-jump").click()
        await expect(page.locator("#cal-pop")).not.toHaveClass(/hidden/)

        await page.keyboard.press("Escape")
        await expect(page.locator("#cal-pop")).toHaveClass(/hidden/)
    })

    test("jump picker closes on click outside", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await page.locator("#cal-jump").click()
        await expect(page.locator("#cal-pop")).not.toHaveClass(/hidden/)

        await page.locator("h2").first().click()
        await expect(page.locator("#cal-pop")).toHaveClass(/hidden/)
    })

    test("prev/next month navigation changes the title", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        const originalTitle = await page.locator("#cal-title").textContent()
        await page.locator("#cal-next").click()
        await expect(page.locator("#cal-title")).not.toHaveText(originalTitle ?? "")
    })

    test("today button returns to current month", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        const originalTitle = await page.locator("#cal-title").textContent()
        await page.locator("#cal-next").click()
        // 'Today' lives at the bottom of the month/year picker popup, so open it first.
        await page.locator("#cal-jump").click()
        await page.locator("#cal-today").click()
        await expect(page.locator("#cal-title")).toHaveText(originalTitle ?? "")
    })
})

test.describe("Dashboard – Stacked calendar", () => {
    test.beforeEach(async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await setCalendarView(page, "stacked")
    })

    test.afterEach(async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await setCalendarView(page, "full")
    })

    test("stacked calendar renders the shared grid (no legacy #calendar mount)", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await expect(page.locator("#d-min-grid")).toBeVisible()
        await expect(page.locator("#calendar")).toHaveCount(0) // the old FullCalendar mount is gone for every style
    })

    test("today cell carries the today highlight class", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await expect(page.locator(`.d-min-cell[data-date="${todayStr()}"]`)).toHaveClass(/d-min-today/)
    })

    test("today is pre-selected and day panel loads automatically", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await expect(page.locator("#day-logger-panel")).not.toContainText("Click a day to log actions")
    })

    test("clicking a past date loads that day in the day panel", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        const past = pastDateStr(3)
        await page.locator(`.d-min-cell[data-date="${past}"]`).click()
        await expect(page.locator("#day-logger-panel")).not.toContainText("Click a day to log actions")
    })

    test("bar appears under today after logging an action", async ({ authenticatedPage: page }) => {
        // Reset log count to 0 (deterministic API reset before loading the page).
        await resetTodayLogs(page)
        await page.goto("/")

        const today = todayStr()
        await expect(page.locator(`.d-min-cell[data-date="${today}"] .d-stk-bar`)).toHaveCount(0)

        await Promise.all([
            page.waitForResponse(r => r.url().includes("/logs/") && r.request().method() === "POST"),
            page.locator("#day-logger-panel").getByLabel("Increase").first().click(),
        ])

        await expect(page.locator(`.d-min-cell[data-date="${today}"] .d-stk-bar`)).toHaveCount(1, { timeout: 5000 })
    })

    test("prev/next month navigation changes the title", async ({ authenticatedPage: page }) => {
        await page.goto("/")
        const originalTitle = await page.locator("#cal-title").textContent()
        await page.locator("#cal-next").click()
        await expect(page.locator("#cal-title")).not.toHaveText(originalTitle ?? "")
    })

    // The chosen day is retained for the current working session (per-tab sessionStorage): it survives
    // in-app navigation but resets when the tab closes or the auth session ends (login-page load clears it).
    test("selected day is retained when navigating away and back within the session", async ({ authenticatedPage: page }) => {
        const other = otherDayThisMonth()

        await page.goto("/")
        await page.locator(`.d-min-cell[data-date="${other}"]`).click()
        await expect(page.locator(`.d-min-cell[data-date="${other}"]`)).toHaveClass(/d-min-selected/)

        // Navigate to another page and back (full page loads — the dashboard script re-runs).
        await page.goto("/settings")
        await page.goto("/")

        // The previously chosen day is restored, not reset to today.
        await expect(page.locator(`.d-min-cell[data-date="${other}"]`)).toHaveClass(/d-min-selected/)
        await expect(page.locator(`.d-min-cell[data-date="${todayStr()}"]`)).not.toHaveClass(/d-min-selected/)
    })

    test("selected day resets to today after logout and login", async ({ authenticatedPage: page, testUser }) => {
        const other = otherDayThisMonth()

        await page.goto("/")
        await page.locator(`.d-min-cell[data-date="${other}"]`).click()
        await expect(page.locator(`.d-min-cell[data-date="${other}"]`)).toHaveClass(/d-min-selected/)

        // Log out (POST /logout → redirect to /login, which clears the retained day), then log back in.
        // On mobile the desktop logout button is hidden; open the hamburger menu first (mirrors auth.spec.ts).
        await Promise.all([
            page.waitForURL("/login"),
            (async (): Promise<void> => {
                const hamburger = page.locator('button[aria-label="Toggle menu"]')
                if (await hamburger.isVisible()) {
                    await hamburger.click()
                    await page.locator('#mobile-menu form[action="/logout"] button').click()
                } else {
                    await page.locator('form[action="/logout"] button').first().click()
                }
            })(),
        ])
        await loginAs(page, testUser)

        // Fresh working session: back to the today default, not the previously chosen day.
        await page.goto("/")
        await expect(page.locator(`.d-min-cell[data-date="${todayStr()}"]`)).toHaveClass(/d-min-selected/)
        await expect(page.locator(`.d-min-cell[data-date="${other}"]`)).not.toHaveClass(/d-min-selected/)
    })
})

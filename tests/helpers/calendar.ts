import type { Page } from "@playwright/test"
import { expect } from "@playwright/test"

// Pages the dashboard calendar until it draws `isoDate`, and does nothing when it already does.
//
// The grid is 42 cells: every day of the month it is showing, `(weekday of the 1st - week start + 7) % 7`
// leading days of the previous month, and whatever trailing days of the next fill the rest. The number of
// leading cells is a property of the CALENDAR DATE, so a relative offset drifts in and out of view over a
// month and disappears at the start of one - on 2026-09-01 (a Tuesday, Monday-first week) the grid began
// at 2026-08-31 and `pastDateStr(2)` had no cell at all, which failed 16 specs that had nothing to do with
// the calendar. A date is always a primary cell of its own month, so paging there removes the dependency
// for the specs that need a genuinely past day rather than merely a different one.
//
// This LEAVES the calendar on that month. A spec that afterwards clicks today's cell has to ask for
// today's month too, and one asserting a cache HIT should use `otherDaysThisMonth` instead, since paging
// fetches the month it lands on. Call it after the `goto`/`reload` that draws the grid: every full load
// resets the view to today's month.
export async function showMonthOf(page: Page, isoDate: string): Promise<void> {
    const target = isoDate.slice(0, 7)

    // The 15th cell is inside the shown month whatever the alignment: at most 6 cells lead it and never
    // fewer than 28 days follow them, so index 14 always falls between the two. Re-read each pass because
    // the whole grid is replaced by the click.
    for (let guard = 0; guard < 24; guard++) {
        const shown = await page.locator(".d-min-cell").nth(14).getAttribute("data-date")
        if (shown === null || shown.slice(0, 7) === target) {
            break
        }
        await page.locator(shown.slice(0, 7) > target ? "#cal-prev" : "#cal-next").click()
    }

    await expect(page.locator(`.d-min-cell[data-date="${isoDate}"]`)).toHaveCount(1)
}

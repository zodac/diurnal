import type { Locator, Page } from "@playwright/test"
import { expect } from "@playwright/test"

// The dashboard's round-trips, as pathname patterns. Matching the pathname (not the whole URL) keeps these
// independent of the query strings the calendar feeds carry and of any BASE_PATH prefix a deployment adds.

/** A day-logger mutation: the increment/decrement/set/delete POST that swaps the day panel back in. */
export const LOG_MUTATION = /\/internal\/logs\/[^/]+\/[^/]+\/(increment|decrement|set|delete)$/

/*
 * There is deliberately NO pattern for loading a day into the day-logger panel, and `clickAndWait` must not be used
 * for a calendar cell click. `dashboard.js` serves that panel from a client-side fragment cache
 * (`createFragmentCache`, keyed on the date), so clicking a day that has already been shown - or that the calendar
 * prefetched - issues NO request at all. Waiting for one then hangs until the 30s test timeout, which is exactly
 * what it did to six tests here when this was tried. Assert on the panel's CONTENT instead; that is true whether the
 * fragment came from the network or the cache.
 */

/** The `full` calendar style's event feed (the public API endpoint the grid reads). */
export const FULL_EVENTS = /\/api\/v1\/logs\/events$/

/** The `minimal`/`stacked` calendar styles' own feed. */
export const MINIMAL_EVENTS = /\/internal\/logs\/minimal-events$/

/**
 * Click something that triggers a server round-trip, and wait for that round-trip to finish.
 *
 * Every interaction on this page is asynchronous — a log button POSTs and swaps the panel, a calendar cell GETs its
 * day, and a mutation additionally re-fetches the month's events. Asserting straight after the click leaves all of
 * that inside Playwright's default 5s `expect` budget, which is ample idle and not ample when two workers x two
 * projects share one JVM; the suite runs with `retries: 0`, so one slow swap fails the whole gate. This is the same
 * guard `prefs.waitForSave` puts around the settings PATCH and `search.searchAndWait` around the debounced list.
 *
 * **The response's status is asserted too.** Without that, a 500 from the endpoint shows up as whatever assertion
 * came next timing out, which reads as flakiness; with it, the failure names the request that actually broke.
 *
 * @param page     the page under test
 * @param target   the element to click
 * @param endpoint pathname pattern of the request the click is expected to make
 */
export async function clickAndWait(page: Page, target: Locator, endpoint: RegExp): Promise<void> {
    const [response] = await Promise.all([
        page.waitForResponse(r => endpoint.test(new URL(r.url()).pathname)),
        target.click(),
    ])
    expect(response.ok(), `${response.request().method()} ${new URL(response.url()).pathname} returned ${response.status()}`)
        .toBe(true)
}

/**
 * The day-logger's count input for the first action listed in the panel — the value nearly every logging assertion
 * is about, resolved in one place so a markup change is one edit rather than a dozen.
 *
 * @param page the page under test
 * @returns the first logged action's count input
 */
export function firstLogCount(page: Page): Locator {
    return page.locator('#day-logger-panel [id^="log-"]').first().locator("input[name=count]")
}

/**
 * Assert the day-logger panel is loaded and showing a real day rather than its placeholder, and hand back the first
 * action's Increase button.
 *
 * Staged deliberately: a failure here says "the panel never loaded" instead of surfacing later as "the count never
 * reached 2", which is the same symptom for a quite different cause.
 *
 * @param page the page under test
 * @returns the first action's Increase button, once the panel is ready
 */
export async function readyDayLogger(page: Page): Promise<Locator> {
    const panel = page.locator("#day-logger-panel")
    await expect(panel, "the day-logger panel should be present").toBeVisible()
    await expect(panel, "the day-logger panel should have loaded a day, not its placeholder")
        .not.toContainText("Click a day to log actions")
    const increase = panel.getByLabel("Increase").first()
    await expect(increase, "the first action's Increase button should be ready").toBeEnabled()
    return increase
}

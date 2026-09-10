import type { Page } from "@playwright/test"

// The list endpoints the two debounced search boxes swap their results in from. Pathname only: the
// real URL also carries `?page=1&q=…`, which varies per keystroke.
export const ACTIONS_LIST_PATH = "/internal/actions/list"
export const NOTES_LIST_PATH = "/internal/notes/list"

/**
 * Type into a debounced HTMX search box and wait for the list swap it triggers.
 *
 * `partials/search-input.html` fires on `input changed delay:300ms`, so a `fill()` starts a 300ms timer, THEN issues a
 * request, THEN swaps the list in. Asserting straight after the fill gives all three stages Playwright's default 5s
 * `expect` budget, which is ample on an idle machine and not ample when two workers x two projects share one JVM — and
 * the suite runs with `retries: 0`, so one slow swap fails the whole gate.
 *
 * This is the same guard `prefs.waitForSave` puts around the settings PATCH, whose own comment records the unwaited
 * version as "the root cause of the previous flakiness". The search lists never got the same treatment.
 *
 * **The fill must actually change the value.** The trigger is `input CHANGED`, so re-filling a box with the text it
 * already holds fires no request and this would wait for a response that never comes. Clearing a non-empty box IS a
 * change and does fire one.
 *
 * @param page     the page under test
 * @param selector the search input's selector
 * @param term     the text to type (may be empty, to clear a non-empty box)
 * @param listPath the pathname of the list endpoint the box swaps from
 */
export async function searchAndWait(page: Page, selector: string, term: string, listPath: string): Promise<void> {
    await Promise.all([
        page.waitForResponse(response => new URL(response.url()).pathname === listPath),
        page.locator(selector).fill(term),
    ])
}

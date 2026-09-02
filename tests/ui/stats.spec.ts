import type { APIRequestContext, Locator, Page } from "@playwright/test"
import { test, expect } from "../helpers/fixtures"
import { todayStr, pastDateStr } from "../helpers/dates"

// Find an action's id by name, paging the public listing (it is paginated by the user's page-size
// preference, so a single page cannot be assumed to hold every action).
async function findActionIdByName(apiCtx: APIRequestContext, name: string): Promise<string> {
    for (let page = 1; ; page++) {
        const res = await apiCtx.get(`/api/v1/actions?page=${page}`)
        expect(res.ok(), `could not list actions: HTTP ${res.status()}`).toBe(true)
        const body = await res.json()
        const found = body.items.find((action: { name: string }) => action.name === name)
        if (found !== undefined) {
            return found.id
        }
        expect(page, `no action named "${name}" exists`).toBeLessThan(body.totalPages)
    }
}

test.describe("Stats page", () => {
    test("no logged actions shows empty state", async ({ page }) => {
        const { setupTestUser } = await import("../helpers/fixtures")
        await setupTestUser(page, {
            email: `e2e-stats-empty-${Date.now()}@example.com`,
            password: "test_password123",
            displayName: "Stats Empty",
        })
        await page.goto("/stats")
        await expect(page.locator("body")).toContainText(/no actions|no logs/i)
    })

    test("logged actions show stats cards with streak and total", async ({ authenticatedPage: page }) => {
        const apiCtx = page.context().request
        // Create an action and log it today
        await apiCtx.post("/internal/actions", { form: { name: "StatsAction", colour: "#6366f1" } })
        // Get the action ID from the actions list to build the log URL
        await page.goto("/actions")
        const actionIdMatch = await page.locator('#action-list [id^="action-"]').first().getAttribute("id")
        const actionId = actionIdMatch?.replace("action-", "")

        if (actionId !== undefined) {
            const today = todayStr()
            await apiCtx.post(`/internal/logs/${today}/${actionId}/increment`)
        }

        await page.goto("/stats")
        await expect(page.locator("body")).toContainText("StatsAction")
        await expect(page.locator("body")).toContainText(/streak|total/i)
    })

    test("stats pagination: next and previous navigate pages", async ({ authenticatedPage: page }) => {
        const apiCtx = page.context().request
        const today = todayStr()

        // Create and log 11 actions to exceed one page. Created through the public API so the id comes back
        // as JSON: scraping it out of the returned HTML fragment (as this did) breaks silently the moment
        // the row markup changes — the regex matches nothing, the `if (match)` skips the logging, and the
        // test goes on to assert against actions that were never logged.
        // The names are fixed because the assertion needs a known set spanning two pages, so a re-run
        // against the same database finds them already there. A 409 is therefore expected rather than
        // fatal — the same tolerance the "CaptionFit" test below applies — and the existing action is
        // looked up instead. Asserting `created.ok()` outright made a second `npm test` against a live
        // instance fail on "could not create action 1: HTTP 409".
        for (let i = 1; i <= 11; i++) {
            const name = `StatsPageAction${i.toString().padStart(2, "0")}`
            const created = await apiCtx.post("/api/v1/actions", { data: { name, colour: "#6366f1" } })
            let id: string
            if (created.status() === 409) {
                id = await findActionIdByName(apiCtx, name)
            } else {
                expect(created.ok(), `could not create action ${i}: HTTP ${created.status()}`).toBe(true)
                id = (await created.json()).id as string
            }
            await apiCtx.put(`/api/v1/logs/${today}/${id}`, { data: { count: 1 } })
        }

        // The Stats list paginates on the user's page-size preference, and the frequency-graph test below
        // widens that preference to 100 and leaves it there. Every test here shares one user, so on a
        // re-run against the same database the widened value is already in force, every subject fits on a
        // single page, and there is no "Next" to click — the test then failed on a missing link rather
        // than on anything it is about. Pin the size this test needs instead of depending on the default
        // still being in force.
        await apiCtx.patch("/internal/settings", { form: { pageSize: "5" } })

        await page.goto("/stats")
        await expect(page.locator("body")).toContainText("Next")
        await page.locator('a:has-text("Next")').first().click()
        await expect(page.locator("body")).toContainText("Previous")
    })

    // The rename cap (StatField.MAX_LABEL_LENGTH) sits just above the longest BUILT-IN label, so
    // what it promises is a claim about geometry and can only be checked by rendering it:
    //   • a max-length name of ordinary wording costs at most ONE caption line more than the built-ins
    //     beside it (not zero: the cap is deliberately a couple of characters longer than the longest
    //     built-in label, so at a width where those fit on one line a full-length name can take two), and
    //   • even a max-length name of the widest glyph with no spaces stays inside its tile (that one is
    //     `break-words` on `.stat-tile dt` doing the work — the cap alone cannot bound rendered width).
    // Both names are built from the cap, read off the rename input's own maxlength, so this test follows
    // the cap rather than restating it.
    test("a maximum-length stat name stays within its tile", async ({ authenticatedPage: page }) => {
        const apiCtx = page.context().request
        // The public API returns the created action as JSON, so its id needs no scraping out of markup.
        // A 409 here means a previous run already created it, which is fine — find it in the listing.
        const created = await apiCtx.post("/api/v1/actions", { data: { name: "CaptionFit", colour: "#6366f1" } })
        const actionId = created.ok()
            ? (await created.json()).id
            : await findActionIdByName(apiCtx, "CaptionFit")
        const today = todayStr()
        await apiCtx.put(`/api/v1/logs/${today}/${actionId}`, { data: { count: 1 } })

        await page.goto("/settings")
        const maxLength = Number(await page.locator("#stats-fields-list .stats-field-input").first().getAttribute("maxlength"))
        expect(maxLength).toBeGreaterThan(0)

        // Ordinary wording of the built-ins' character mix, versus the widest-glyph worst case.
        const typical = "Average count per week month days".slice(0, maxLength).trim()
        const worstCase = "W".repeat(maxLength)

        // Built by hand rather than with the `form` option: the picker posts one statsOrder/statsLabel
        // pair PER ROW (repeated keys), which `form` cannot express — it takes one value per name.
        const body = new URLSearchParams()
        for (const [key, label] of [["current-streak", typical], ["longest-streak", worstCase], ["last-performed", ""]]) {
            body.append("statsOrder", key)
            body.append("statsLabel", label)
            body.append("statsEnabled", key)
        }
        const saved = await apiCtx.patch("/internal/settings", {
            headers: { "content-type": "application/x-www-form-urlencoded" },
            data: body.toString(),
        })
        expect(saved.status()).toBe(204)

        // 1920 is the strictest width, not the widest-looking risk: its caption box is roomy enough that
        // the built-in labels stop wrapping, so a caption has only one line to fit in. 1280 is checked too
        // (its built-ins wrap, so it is the looser case), against both extreme font settings.
        for (const width of [1920, 1280]) {
            for (const font of ["nova", "dyslexic"]) {
                await apiCtx.patch("/internal/settings", { form: { font } })
                await page.setViewportSize({ width, height: 800 })
                await page.goto("/stats")

                const captions = await page.locator(".stat-tile dt").evaluateAll((els: HTMLElement[]) => els.map(el => ({
                    text: el.textContent ?? "",
                    height: el.getBoundingClientRect().height,
                    // `globalThis` (not a bare `getComputedStyle`) satisfies eslint's no-undef without browser
                    // globals in the lint config; in the browser it resolves to window.getComputedStyle.
                    lineHeight: Number.parseFloat(globalThis.getComputedStyle(el).lineHeight),
                    overflows: el.scrollWidth > el.clientWidth,
                })))
                const renamed = captions.find(c => c.text === typical)
                const widest = captions.find(c => c.text === worstCase)
                const builtIns = captions.filter(c => c.text !== typical && c.text !== worstCase)
                const deepestBuiltIn = Math.max(...builtIns.map(c => c.height))
                const oneLine = renamed?.lineHeight ?? 0

                expect(renamed, `renamed caption missing at ${width}px/${font}`).toBeDefined()
                expect(widest, `worst-case caption missing at ${width}px/${font}`).toBeDefined()
                expect(oneLine, `caption line height at ${width}px/${font}`).toBeGreaterThan(0)
                // Ordinary wording costs at most one caption line more than the built-ins already do...
                expect(renamed?.height, `more than a line deeper than the built-ins at ${width}px/${font}`)
                    .toBeLessThanOrEqual(deepestBuiltIn + oneLine)
                // ...and NO name, however wide its glyphs, escapes its tile sideways.
                expect(renamed?.overflows, `renamed caption overflows at ${width}px/${font}`).toBe(false)
                expect(widest?.overflows, `worst-case caption overflows at ${width}px/${font}`).toBe(false)
            }
        }

        await apiCtx.patch("/internal/settings", { form: { font: "nova" } })
    })

    test("frequency graph: opens, toggles period, steps windows, and compares actions", async ({ authenticatedPage: page }) => {
        const apiCtx = page.context().request
        const today = todayStr()
        // The "Earlier" step is only offered back as far as the charted action's first logged entry, so
        // GraphAlpha gets some history - otherwise that button is (correctly) disabled and untestable.
        const backThen = pastDateStr(70)

        // Two logged actions, so one can be charted and the other offered as a comparison. Ids are read
        // back through the public API rather than scraped out of the create fragment, which returns the
        // whole (possibly pre-populated) list and so cannot identify the row that was just added.
        for (const name of ["GraphAlpha", "GraphBeta"]) {
            await apiCtx.post("/internal/actions", { form: { name, colour: "#6366f1" } })
            const listed = await (await apiCtx.get(`/api/v1/actions?q=${name}`)).json()
            const created = listed.items.find((item: { name: string }) => item.name === name)
            expect(created, `${name} should have been created`).toBeDefined()
            await apiCtx.post(`/internal/logs/${today}/${created.id}/increment`)
            if (name === "GraphAlpha") {
                await apiCtx.post(`/internal/logs/${backThen}/${created.id}/increment`)
            }
        }

        // Every test in this spec shares one user, so earlier ones have already left enough actions to
        // push these two off the first page. Widen the page size rather than paginating to find them.
        await apiCtx.patch("/internal/settings", { form: { pageSize: "100" } })

        await page.goto("/stats")
        const modal = page.locator("#stats-chart-modal")
        await expect(modal).toHaveClass(/hidden/)

        // Opening from a card titles the dialog and draws the current month.
        await page.locator("[data-chart-name='GraphAlpha']").click()
        await expect(modal).not.toHaveClass(/hidden/)
        await expect(page.locator("#stats-chart-title")).not.toBeEmpty()
        await expect(page.locator(".chart-wrap")).toHaveAttribute("data-chart-shown-period", "month")
        await expect(page.locator(".chart-plot .chart-col").first()).toBeVisible()

        // Stepping back lands on the previous window; "Later" is available again from there.
        const shownAt = await page.locator(".chart-wrap").getAttribute("data-chart-shown-at")
        await page.locator("button[data-chart-at]").first().click()
        await expect(page.locator(".chart-wrap")).not.toHaveAttribute("data-chart-shown-at", shownAt ?? "")
        await page.locator("button[data-chart-at]").last().click()
        await expect(page.locator(".chart-wrap")).toHaveAttribute("data-chart-shown-at", shownAt ?? "")

        // Compare: the picker offers the OTHER action, and picking it adds a removable second series.
        await page.locator("[data-chart-compare-open]").click()
        await expect(page.locator("#chart-compare-panel")).not.toHaveClass(/hidden/)
        await page.locator("#chart-candidate-search").fill("GraphBeta")
        await expect(page.locator(".chart-candidate")).toHaveCount(1)
        await page.locator(".chart-candidate").first().click()
        await expect(page.locator(".chart-chip")).toHaveCount(2)
        await expect(page.locator(".chart-wrap")).not.toHaveAttribute("data-chart-shown-compare", "")
        // Every column now carries one bar per charted action.
        await expect(page.locator(".chart-plot .chart-col").first().locator(".chart-bar")).toHaveCount(2)

        // Removing the comparison puts it back to a single series.
        await page.locator(".chart-chip-remove").first().click()
        await expect(page.locator(".chart-chip")).toHaveCount(1)
        await expect(page.locator(".chart-plot .chart-col").first().locator(".chart-bar")).toHaveCount(1)

        // The period toggle re-fetches the fragment as a year of months, re-anchored onto the year the
        // shown month sat in.
        await page.locator('button[data-chart-period="year"]').click()
        await expect(page.locator(".chart-wrap")).toHaveAttribute("data-chart-shown-period", "year")
        await expect(page.locator(".chart-plot .chart-col")).toHaveCount(12)

        // Flipping back lands on the month that was showing, NOT that year's January: every month window
        // visited is remembered against its year (stats.js `monthsShown`), so the toggle round-trips.
        await page.locator('button[data-chart-period="month"]').click()
        await expect(page.locator(".chart-wrap")).toHaveAttribute("data-chart-shown-at", shownAt ?? "")

        // The same round trip from a window that is not the current month, so the assertion above cannot
        // be satisfied by a January fallback that happens to match a run in January.
        await page.locator("button[data-chart-at]").first().click()
        await expect(page.locator(".chart-wrap")).not.toHaveAttribute("data-chart-shown-at", shownAt ?? "")
        const stepped = await page.locator(".chart-wrap").getAttribute("data-chart-shown-at")
        await page.locator('button[data-chart-period="year"]').click()
        await expect(page.locator(".chart-wrap")).toHaveAttribute("data-chart-shown-period", "year")
        await page.locator('button[data-chart-period="month"]').click()
        await expect(page.locator(".chart-wrap")).toHaveAttribute("data-chart-shown-at", stepped ?? "")

        // Escape closes the dialog.
        await page.keyboard.press("Escape")
        await expect(modal).toHaveClass(/hidden/)
    })
})

// The notes subject on the Stats page: a card like any other, pinned first, and chartable alongside an
// action on the shared frequency graph.
test.describe("Stats page – notes", () => {
    // A unique name per run, so creating it always succeeds and its id comes straight back from the API —
    // these specs share a user and DB, so the paginated Actions page cannot be used to find an action by
    // position, and a fixed name would 409 on the second run leaving nothing to read the id from.
    const ACTION_NAME = `NoteChartAction${Date.now()}`
    let actionId: string | undefined

    test.beforeEach(async ({ authenticatedPage: page }) => {
        // An action logged today, and notes on two days, so both subjects have data to chart.
        const apiCtx = page.context().request
        if (actionId === undefined) {
            const created = await apiCtx.post("/api/v1/actions", { data: { name: ACTION_NAME, colour: "#6366f1" } })
            actionId = (await created.json()).id as string
        }
        await apiCtx.put(`/api/v1/logs/${todayStr()}/${actionId}`, { data: { count: 1 } })
        for (const day of [todayStr(), pastDateStr(1)]) {
            await apiCtx.put(`/api/v1/notes/${day}`, { data: { content: `Journal for ${day}` } })
        }
    })

    test("the notes card is pinned first, ahead of every action", async ({ authenticatedPage: page }) => {
        await page.goto("/stats")
        await expect(page.locator("#stats-list .card").first().locator("h3")).toHaveText("Notes")
    })

    // Cards are located by TITLE, never by index: these specs share a user and DB with the rest of the
    // suite, so how many action cards exist (and in what order) is not this test's to know.
    function cardTitled(page: Page, title: string): Locator {
        return page.locator("#stats-list .card").filter({ has: page.locator(`h3:text-is("${title}")`) })
    }

    test("the notes card renders the same tile chrome as an action", async ({ authenticatedPage: page }) => {
        await page.goto("/stats")
        // Only the MANDATORY tile can be asserted by name: which optional stats are shown is a per-user
        // preference, and these specs share one user with settings.spec.ts, which reorders and disables them.
        const notesCard = cardTitled(page, "Notes")
        const actionCard = cardTitled(page, ACTION_NAME)

        await expect(notesCard).toContainText("Last performed")
        await expect(notesCard.locator("dl dt")).not.toHaveCount(0)
        // The two cards are the same shape: whatever tiles the user has enabled, both render them.
        expect(await notesCard.locator("dl dt").count()).toBe(await actionCard.locator("dl dt").count())
    })

    test("notes can be charted, and an action compared into the same graph", async ({ authenticatedPage: page }) => {
        await page.goto("/stats")
        await cardTitled(page, "Notes").locator("[data-chart-subject]").click()

        const modal = page.locator("#stats-chart-modal")
        await expect(modal).toBeVisible()
        await expect(page.locator("#stats-chart-title")).toHaveText("Notes")
        await expect(page.locator("#stats-chart-body")).toContainText("Notes")

        // The compare picker offers the actions alongside; adding one puts both series on the same graph.
        await page.locator("#stats-chart-body").getByText("Compare to").click()
        await page.locator("[data-chart-add]").filter({ hasText: ACTION_NAME }).click()
        await expect(page.locator("#stats-chart-body")).toContainText(ACTION_NAME)
        await expect(page.locator("#stats-chart-body")).toContainText("Notes")
    })

    test("the compare picker offers notes when the graph was opened from an action", async ({ authenticatedPage: page }) => {
        await page.goto("/stats")
        await cardTitled(page, ACTION_NAME).locator("[data-chart-subject]").click()
        await expect(page.locator("#stats-chart-title")).toHaveText(ACTION_NAME)

        await page.locator("#stats-chart-body").getByText("Compare to").click()
        // Notes are offered ahead of the actions, so they are the first row of the picker.
        await expect(page.locator("[data-chart-add]").first()).toContainText("Notes")
    })
})

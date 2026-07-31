import { test, expect } from "../helpers/fixtures"

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
            const today = new Date().toISOString().slice(0, 10)
            await apiCtx.post(`/internal/logs/${today}/${actionId}/increment`)
        }

        await page.goto("/stats")
        await expect(page.locator("body")).toContainText("StatsAction")
        await expect(page.locator("body")).toContainText(/streak|total/i)
    })

    test("stats pagination: next and previous navigate pages", async ({ authenticatedPage: page }) => {
        const apiCtx = page.context().request
        const today = new Date().toISOString().slice(0, 10)

        // Create and log 11 actions to exceed one page
        for (let i = 1; i <= 11; i++) {
            const createResp = await apiCtx.post("/internal/actions", {
                form: { name: `StatsPageAction${i.toString().padStart(2, "0")}`, colour: "#6366f1" },
            })
            // Extract action id from the returned HTML fragment
            const html = await createResp.text()
            const match = html.match(/id="action-([^"]+)"/)
            if (match) {
                await apiCtx.post(`/internal/logs/${today}/${match[1]}/increment`)
            }
        }

        await page.goto("/stats")
        await expect(page.locator("body")).toContainText("Next")
        await page.locator('a:has-text("Next")').first().click()
        await expect(page.locator("body")).toContainText("Previous")
    })

    // The rename cap (ActionStatField.MAX_LABEL_LENGTH) sits just above the longest BUILT-IN label, so
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
        const createResp = await apiCtx.post("/internal/actions", { form: { name: "CaptionFit", colour: "#6366f1" } })
        const match = (await createResp.text()).match(/id="action-([^"]+)"/)
        const today = new Date().toISOString().slice(0, 10)
        if (match) {
            await apiCtx.post(`/internal/logs/${today}/${match[1]}/increment`)
        }

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
})

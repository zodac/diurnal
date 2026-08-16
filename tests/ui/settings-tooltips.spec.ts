import { test, expect } from "../helpers/fixtures"

/* global window -- referenced inside the in-browser page.evaluate callback below */

// ── Touch tooltips (the stats picker's descriptions) ──────────────────────────
// A tooltip has TWO reveal paths and they need separate tests, because a bug in either is invisible to
// a test of the other:
//   • the GESTURE (JS): the long-press handlers add `.tip-open`. Asserted below on the class, since the
//     class is precisely what the handlers own.
//   • the CSS: `.group:hover` reveals the bubble, gated on `@media (hover: hover)`. That gate is the
//     whole story on a phone — without it the browser's sticky :hover (applied to whatever was last
//     touched) opened the bubble on a plain press, and left it open while the page scrolled, with no
//     `.tip-open` anywhere for the gesture handlers to clear. A class assertion cannot see that, which
//     is why the second test below asserts the bubble's VISIBILITY on a device reporting no hover.
// The press itself goes through CDP: Playwright's touchscreen API only taps, it never HOLDS.
test.describe("Stats picker tooltips (touch)", () => {
    test.use({ hasTouch: true })

    const HELD_PAST_THRESHOLD = 700    // the handlers' long-press threshold is 500ms
    const HELD_BELOW_THRESHOLD = 150

    test("a long press opens a stat's description; a scroll under the finger does not", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        const label = page.locator("#stats-fields-list li[data-key='biggest-gap'] .stats-field-label")
        await label.scrollIntoViewIfNeeded()

        const cdp = await page.context().newCDPSession(page)
        const box = await label.boundingBox()
        const point = { x: (box?.x ?? 0) + 4, y: (box?.y ?? 0) + (box?.height ?? 0) / 2 }
        const press = (): Promise<unknown> =>
            cdp.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [point] })
        const release = (): Promise<unknown> =>
            cdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] })
        const scroll = (): Promise<void> => page.evaluate(() => window.scrollBy(0, 60))

        // Held still past the threshold: the description opens.
        await press()
        await page.waitForTimeout(HELD_PAST_THRESHOLD)
        await expect(label, "a long press opens the description").toHaveClass(/tip-open/)

        // Scrolling with it open dismisses it, exactly as a tap elsewhere does.
        await release()
        await scroll()
        await expect(label, "a scroll dismisses an open description").not.toHaveClass(/tip-open/)

        // Held, but the page scrolls under the finger: that is a scroll, not a press, so nothing opens.
        await press()
        await page.waitForTimeout(HELD_BELOW_THRESHOLD)
        await scroll()
        await page.waitForTimeout(HELD_PAST_THRESHOLD)
        await expect(label, "a scroll under the finger cancels the press").not.toHaveClass(/tip-open/)
        await release()
    })
})

// The CSS half of the pair: on a device that reports no hover capability, the ONLY thing that may
// reveal a description is the long-press. This is emulated rather than inherited from the project so
// the guard runs under both projects — the desktop one has hover, where the rule is meant to apply.
test.describe("Stats picker tooltips (no-hover device)", () => {
    // `isMobile` is what makes Chromium report `(hover: none)`/`(pointer: coarse)`; `hasTouch` supplies the
    // gesture. Spelled out rather than spread from `devices[...]`, which also carries `defaultBrowserType`
    // and cannot be applied per-describe ("forces a new worker").
    test.use({ viewport: { width: 360, height: 780 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true })

    test("sticky :hover alone reveals nothing; a long press still does", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        expect(await page.evaluate(() => window.matchMedia("(hover: none)").matches),
            "emulation must actually report a hover-less pointer, or this proves nothing").toBe(true)

        const label = page.locator("#stats-fields-list li[data-key='biggest-gap'] .stats-field-label")
        const bubble = page.locator("#stats-field-desc-biggest-gap")
        await label.scrollIntoViewIfNeeded()

        // A phone applies a sticky :hover to whatever it last touched; hovering stands in for that.
        // Well past the 500ms dwell + 150ms fade, so a reveal would have happened by now.
        await label.hover()
        await page.waitForTimeout(900)
        await expect(bubble, "no-hover device must not reveal a tooltip from :hover").toBeHidden()

        const box = await label.boundingBox()
        const point = { x: (box?.x ?? 0) + 4, y: (box?.y ?? 0) + (box?.height ?? 0) / 2 }
        const cdp = await page.context().newCDPSession(page)
        await cdp.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [point] })
        await page.waitForTimeout(700)
        await expect(bubble, "the long press is the one thing that must still work").toBeVisible()
        await cdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] })

        await page.evaluate(() => window.scrollBy(0, 60))
        await expect(bubble, "a scroll dismisses it").toBeHidden()
    })
})

import type { Locator, Page } from "@playwright/test"
import { test, expect, setupTestUser } from "../helpers/fixtures"

/* global document -- referenced inside the in-browser page evaluate callbacks below */

// ── Truncated-text tooltips (`data-tip-full`, app.js) ─────────────────────────
// The mechanism has parts that fail independently, so each gets its own assertion:
//   • it must REVEAL the whole string when the layout clipped it (the point of the feature);
//   • it must reveal NOTHING when the string fits, since the reveal is decided by a live measurement
//     rather than by the template — a bubble repeating text already fully on screen is pure noise;
//   • the bubble must CONTAIN whatever it shows. That is a claim about rendered geometry and can only
//     be checked by rendering it: the strings here are user text with no guaranteed spaces, and a
//     max-length name typed as one unbroken run shipped once with the border stranded at its width cap
//     while the text ran off the page (fixed with `overflow-wrap: anywhere`).
// The reveal itself is asserted on both of its paths, hover and long-press, because a bug in either is
// invisible to a test of the other — and the hover path is deliberately gated on `(hover: hover)`, so
// it cannot be asserted at all under the mobile project.
const BUBBLE = ".app-tooltip-float"

// 95 characters, just inside TextFields.ACTION_NAME_MAX_LENGTH (100).
const LONG_NAME = "Ludicrously long action name that no single stats card in this layout could ever show in full!!"
// The same length with nowhere to wrap — the shape that broke the bubble's box.
const UNBREAKABLE_NAME = `W${"w".repeat(94)}`
const SHORT_NAME = "Run"

// The day panel lists every action for the selected day, so this needs no logging; the Stats page is
// where a subject has to have been logged to appear.
async function createAction(page: Page, name: string): Promise<string> {
    const created = await page.context().request.post("/api/v1/actions", { data: { name, colour: "#6366f1" } })
    expect(created.ok(), `could not create action "${name}": HTTP ${created.status()}`).toBe(true)
    const { id } = await created.json()
    return id
}

async function freshUser(page: Page, label: string): Promise<void> {
    // A fresh account per test: these hover whichever marked element comes first, so a shared user
    // would have them landing on whatever an earlier test happened to leave there.
    await setupTestUser(page, {
        email: `e2e-truncation-${label}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@example.com`,
        password: "test_password123",
        displayName: "Truncation Tooltips",
    })
}

// The day panel's action name, the host the bug was reported against.
function dayPanelName(page: Page): Locator {
    return page.locator("#day-logger-panel [data-tip-full]").first()
}

// Hovering the name itself: on a narrow viewport the row's +/− controls overlap it, and Playwright's
// actionability check would then refuse the hover. The tooltip only needs the pointer over the text.
async function hoverName(page: Page): Promise<void> {
    await dayPanelName(page).hover({ position: { x: 4, y: 4 }, force: true })
}

test.describe("Truncated-text tooltips (hover)", () => {
    test.skip(({ isMobile }) => isMobile === true, "the hover reveal is gated on (hover: hover) — touch is covered by the long-press test below")

    test("a clipped action name reveals its full text on hover, and hides again on leaving", async ({ page }) => {
        await freshUser(page, "hover")
        await createAction(page, LONG_NAME)
        await page.goto("/")

        const name = dayPanelName(page)
        await expect(name, "the day panel renders the action").toBeVisible()
        // The premise of the test: the name really is clipped by its own box. Asserted rather than
        // assumed, so a future layout change that stops truncating fails here plainly instead of
        // turning into a mystery about the tooltip.
        const clipped = await name.evaluate((el: HTMLElement) => el.scrollWidth > el.clientWidth + 1)
        expect(clipped, "the day panel's action row is too narrow for the name").toBe(true)

        await hoverName(page)
        const bubble = page.locator(BUBBLE)
        await expect(bubble, "hovering a clipped name reveals the whole name").toBeVisible()
        await expect(bubble).toHaveText(LONG_NAME)

        // Hover-out closes it: the bubble is shared and fixed-positioned, so a stale one would follow
        // the user around the page.
        await page.locator("#dashboard-main").hover({ position: { x: 1, y: 1 } })
        await expect(bubble, "moving off the name hides the bubble").toBeHidden()
    })

    test("a name that fits reveals no tooltip at all", async ({ page }) => {
        await freshUser(page, "fits")
        await createAction(page, SHORT_NAME)
        await page.goto("/")

        await expect(dayPanelName(page), "the day panel renders the action").toBeVisible()
        await hoverName(page)
        // Comfortably past the `--tooltip-delay` dwell app.js waits out before revealing.
        await page.waitForTimeout(900)
        await expect(page.locator(BUBBLE), "a name that fits its box gets no bubble").toBeHidden()
    })

    test("an unbreakable max-length name stays inside the bubble's border", async ({ page }) => {
        await freshUser(page, "unbreakable")
        await createAction(page, UNBREAKABLE_NAME)
        await page.goto("/")

        await hoverName(page)
        const bubble = page.locator(BUBBLE)
        await expect(bubble, "hovering a clipped name reveals the whole name").toBeVisible()

        // The text's own rendered rectangle against the bubble's border box. `scrollWidth`/`clientWidth`
        // alone would not catch this: the overflow is the point, and what matters is that there is none.
        const fits = await bubble.evaluate((el: HTMLElement) => {
            const box = el.getBoundingClientRect()
            const range = document.createRange()
            range.selectNodeContents(el)
            const text = range.getBoundingClientRect()
            return text.left >= box.left - 1 && text.right <= box.right + 1
                && text.top >= box.top - 1 && text.bottom <= box.bottom + 1
        })
        expect(fits, "a name with nowhere to wrap is wrapped inside the bubble, not spilled out of it").toBe(true)
    })
})

// The touch half. The press goes through CDP because Playwright's touchscreen API only taps, never
// HOLDS — the same reason settings-tooltips.spec.ts drives its long press this way.
test.describe("Truncated-text tooltips (touch)", () => {
    test.use({ hasTouch: true })

    const HELD_PAST_THRESHOLD = 700    // the handler's long-press threshold is 500ms

    test("a long press on a clipped action name opens the shared bubble", async ({ page }) => {
        await freshUser(page, "touch")
        await createAction(page, LONG_NAME)
        await page.goto("/")

        const name = dayPanelName(page)
        await expect(name, "the day panel renders the action").toBeVisible()
        await name.scrollIntoViewIfNeeded()

        const cdp = await page.context().newCDPSession(page)
        const box = await name.boundingBox()
        const point = { x: (box?.x ?? 0) + 4, y: (box?.y ?? 0) + (box?.height ?? 0) / 2 }
        await cdp.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [point] })
        await page.waitForTimeout(HELD_PAST_THRESHOLD)

        const bubble = page.locator(BUBBLE)
        await expect(bubble, "a long press opens the full name").toBeVisible()
        await expect(bubble).toHaveText(LONG_NAME)

        // A tap elsewhere dismisses it, the same as any other tooltip in the app.
        await cdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] })
        await cdp.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ x: 5, y: 5 }] })
        await cdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] })
        await expect(bubble, "a press elsewhere dismisses the bubble").toBeHidden()
    })
})

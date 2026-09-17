import { test, expect } from "../helpers/fixtures"
import { todayStr } from "../helpers/dates"

// Ctrl+S, and the hint that tells anyone it is there. Split from notes.spec.ts - which covers the box
// itself and sits at the max-lines bound - for the same reason note-drafts.spec.ts is its own file.
// The shortcut goes through the SAME saveNote() the Save button's click handler calls, so what is worth
// asserting here is the keyboard path and the tooltip's lifetime; the write rules themselves are already
// pinned next door.
test.describe("Dashboard - note Ctrl+S", () => {
    // A unique note per run: the specs share one user and DB, so a fixed string could already be saved on a
    // re-run, leaving the box (correctly) not dirty and Save inert.
    function freshNote(): string {
        return `Shortcut body ${Date.now()}`
    }

    test.beforeEach(async ({ authenticatedPage: page }) => {
        await page.goto("/")
        await page.evaluate(async (day: string) => {
            await fetch(`/api/v1/notes/${day}`, { method: "DELETE" })
        }, todayStr())
    })

    test("Ctrl+S saves from the keyboard, and sends nothing when there is nothing to save", async ({ authenticatedPage: page }) => {
        const body = freshNote()
        await page.goto("/")

        const writes: string[] = []
        page.on("request", r => {
            if (r.method() === "POST" && new URL(r.url()).pathname.startsWith("/internal/notes/")) {
                writes.push(r.url())
            }
        })

        // The shortcut is the Save button, not a second way to write: an untouched box must not invent a request.
        await page.locator("#note-input").press("ControlOrMeta+s")
        await expect(page.locator("#note-status")).toHaveText("")
        expect(writes, "Ctrl+S on a clean box must send nothing").toHaveLength(0)

        await page.locator("#note-input").fill(body)
        await page.locator("#note-input").press("ControlOrMeta+s")
        await expect(page.locator("#note-status")).toHaveText("Saved")
        await expect(page.locator("#note-save")).toBeDisabled()

        // Bound to the panel, so it answers wherever focus sits inside the box - here on Undo, not the textarea.
        await page.locator("#note-input").fill(`${body} again`)
        await expect(page.locator("#note-status")).toHaveText("Unsaved changes")
        await page.locator("#note-undo").press("ControlOrMeta+s")
        await expect(page.locator("#note-status")).toHaveText("Saved")

        await page.reload()
        await expect(page.locator("#note-input")).toHaveValue(`${body} again`)
    })

    test("the Save button offers the Ctrl+S hint only while it is live", async ({ authenticatedPage: page, isMobile }) => {
        test.skip(isMobile === true, "the hover reveal is gated on (hover: hover) - a touch device has no Ctrl key either")
        await page.goto("/")
        const save = page.locator("#note-save")
        const tip = save.locator(".app-tooltip")

        // Nothing written yet, so Save is inert - and an inert button must not advertise a keystroke that
        // would do nothing. `force` because the disabled button is `pointer-events: none`, which is exactly
        // what keeps the bubble shut: the normal actionability check could never pass, so it is skipped and
        // the real pointer is put over the button anyway.
        await expect(save).toBeDisabled()
        await save.hover({ force: true })
        await page.waitForTimeout(900) // comfortably past the --tooltip-delay dwell and the fade
        await expect(tip, "an inert Save must not offer the shortcut").toBeHidden()

        // Dirty the box and it comes alive, hint and all.
        await page.locator("#note-input").fill(freshNote())
        await expect(save).toBeEnabled()
        await save.hover()
        await expect(tip, "hovering a live Save reveals the shortcut").toBeVisible()
        await expect(tip).toHaveText("CTRL+S")
    })
})

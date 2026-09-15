import type { Page } from "@playwright/test"
import { test, expect } from "../helpers/fixtures"
import { todayStr } from "../helpers/dates"

// Note attachments: the upload, the pill the note box draws behind an embedded file's [[name]] token, the hover
// card's rename/delete, and the notes page's attachments table.
//
// The storage and write rules are covered by the ITs; what only a browser can answer is here — that a dropped
// file really uploads, that the token lands in the textarea as an ordinary unsaved edit, that the mirror layer
// draws a pill in the right place, and that hovering one opens a card with working controls.
test.describe("Note attachments", () => {
    // A unique name per run: the specs share one user and one database, and a day's names are unique, so a fixed
    // one would come back as "photo (2).png" on the second run and every name assertion would drift.
    function freshName(): string {
        return `shot-${Date.now()}.png`
    }

    // A REAL one-pixel PNG, not a handful of arbitrary bytes: the hover card previews an image by loading it, and a
    // broken one has no intrinsic size at all - so the <img> would collapse to zero height and "the preview is
    // showing" could never be asserted. Seventy bytes keeps the upload instant either way.
    const ONE_PIXEL_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    async function attach(page: Page, name: string): Promise<void> {
        await page.locator("#note-attach-input").setInputFiles({
            name,
            mimeType: "image/png",
            buffer: Buffer.from(ONE_PIXEL_PNG, "base64"),
        })
    }

    // A REAL tenth-of-a-second WAV, built rather than pasted as a kilobyte of base64. The card's player has to load
    // something the browser genuinely decodes, and 8-bit PCM is the one audio format no build of Chromium lacks a
    // codec for - the mp3/AAC decoders are exactly what a plain Chromium omits.
    function toneWav(): Buffer {
        const rate = 8000
        const samples = 800
        const data = Buffer.alloc(samples)
        for (let i = 0; i < samples; i++) {
            data[i] = 128 + Math.round(60 * Math.sin((2 * Math.PI * 440 * i) / rate))
        }
        const header = Buffer.alloc(44)
        header.write("RIFF", 0)
        header.writeUInt32LE(36 + data.length, 4)
        header.write("WAVE", 8)
        header.write("fmt ", 12)
        header.writeUInt32LE(16, 16)     // PCM header length
        header.writeUInt16LE(1, 20)      // format: PCM
        header.writeUInt16LE(1, 22)      // channels: mono
        header.writeUInt32LE(rate, 24)
        header.writeUInt32LE(rate, 28)   // byte rate: 8-bit mono, so one byte per sample
        header.writeUInt16LE(1, 32)      // block align
        header.writeUInt16LE(8, 34)      // bits per sample
        header.write("data", 36)
        header.writeUInt32LE(data.length, 40)
        return Buffer.concat([header, data])
    }

    async function attachSound(page: Page, name: string): Promise<void> {
        await page.locator("#note-attach-input").setInputFiles({ name, mimeType: "audio/wav", buffer: toneWav() })
    }

    // The pill lives in a layer that takes no pointer events (the mirror sits BEHIND the textarea - see app.css), so
    // `.hover()` would wait forever for an element that can never receive events. What the user's pointer actually
    // lands on is the textarea, and note.js works out which pill that is by measuring - so the spec does the same,
    // aiming real mouse coordinates at the pill's own box. The click is what a touch device relies on - and it also
    // PINS the card, which is what keeps it open for the rest of whichever test called this.
    async function openCardFor(page: Page, name: string): Promise<void> {
        const pill = page.locator(`#note-highlights .note-chip[data-attachment-name="${name}"]`)
        // The FIRST client rect, not the bounding box: the note box is narrow, so a token readily wraps across two
        // lines - and the union of the two fragments has its centre in the empty gap between them, where there is no
        // pill to hit. note.js hit-tests the same per-fragment rects, so this is aiming where a reader would.
        const rect = await pill.evaluate((element: Element) => {
            const first = element.getClientRects()[0]
            return { x: first.x, y: first.y, width: first.width, height: first.height }
        })
        await page.mouse.move(rect.x + rect.width / 2, rect.y + rect.height / 2)
        await page.mouse.click(rect.x + rect.width / 2, rect.y + rect.height / 2)
        await expect(page.locator("#note-attachment-card")).toBeVisible()
    }

    async function clearDay(page: Page, date: string): Promise<void> {
        await page.evaluate(async (day: string) => {
            await fetch(`/api/v1/notes/${day}`, { method: "DELETE" })
        }, date)
    }

    test.beforeEach(async ({ authenticatedPage: page }) => {
        await page.goto("/")
        // Clearing the day takes its attachments with it, which is exactly the reset these specs want.
        await clearDay(page, todayStr())
        await page.reload()
    })

    test("attaching a file embeds its token in the note as an unsaved edit", async ({ authenticatedPage: page }) => {
        const name = freshName()
        await attach(page, name)

        // The upload writes the FILE; the note is written by the user pressing Save like any other change.
        await expect(page.locator("#note-input")).toHaveValue(`[[${name}]]`)
        await expect(page.locator("#note-status")).toHaveText("Unsaved changes")
        await expect(page.locator("#note-save")).toBeEnabled()

        await page.locator("#note-save").click()
        await expect(page.locator("#note-status")).toHaveText("Saved")

        await page.reload()
        await expect(page.locator("#note-input")).toHaveValue(`[[${name}]]`)
    })

    test("an embedded file is drawn as a pill, and a token naming nothing is not", async ({ authenticatedPage: page }) => {
        const name = freshName()
        await attach(page, name)
        await expect(page.locator(`#note-highlights .note-chip[data-attachment-name="${name}"]`)).toHaveCount(1)

        // A note is prose and may hold brackets: only a token naming a file the day actually holds is a pill.
        await page.locator("#note-input").fill("[[not a real file.png]]")
        await expect(page.locator("#note-highlights .note-chip")).toHaveCount(0)
    })

    test("opening an embedded file's card allows it to be renamed", async ({ authenticatedPage: page }) => {
        const name = freshName()
        await attach(page, name)
        await page.locator("#note-save").click()
        await expect(page.locator("#note-status")).toHaveText("Saved")

        await openCardFor(page, name)
        await expect(page.locator("#note-attachment-name")).toHaveText(name)
        // The file is an image, so the card offers a preview rather than only a download - and the thumbnail is a
        // link to the file itself, which is the only way to see it at its own size (the card is deliberately small).
        await expect(page.locator("#note-attachment-preview")).toBeVisible()
        const previewLink = page.locator("#note-attachment-preview-link")
        await expect(previewLink).toHaveAttribute("target", "_blank")
        await expect(previewLink).toHaveAttribute("rel", "noopener noreferrer")
        await expect(previewLink).toHaveAttribute("href", /\/internal\/note-attachments\/.*\/file$/)

        await page.locator("#note-attachment-rename").click()
        await page.locator("#note-attachment-rename-input").fill("renamed.png")
        await page.locator("#note-attachment-rename-input").press("Enter")

        // The stored name and the note's own token are rewritten together, so the box reads the new one back.
        await expect(page.locator("#note-input")).toHaveValue("[[renamed.png]]")
        await expect(page.locator('#note-highlights .note-chip[data-attachment-name="renamed.png"]')).toHaveCount(1)
    })

    test("removing an embedded file takes its token out of the note", async ({ authenticatedPage: page }) => {
        const name = freshName()
        await page.locator("#note-input").fill("Before ")
        await attach(page, name)
        await page.locator("#note-save").click()
        await expect(page.locator("#note-status")).toHaveText("Saved")

        await openCardFor(page, name)
        // Remove asks first, in place - the destructive button on the left and Cancel where Remove itself sat, so a
        // double-click dismisses rather than deletes.
        await page.locator("#note-attachment-delete").click()
        await expect(page.locator("#note-attachment-confirm")).toBeVisible()
        await page.locator("#note-attachment-delete-cancel").click()
        await expect(page.locator("#note-attachment-confirm")).toBeHidden()
        await expect(page.locator("#note-input")).toHaveValue(`Before [[${name}]]`)

        await page.locator("#note-attachment-delete").click()
        await page.locator("#note-attachment-delete-confirm").click()

        // Only the token goes - the prose around it is the user's own writing and is left exactly as it was.
        await expect(page.locator("#note-input")).toHaveValue("Before")
        await expect(page.locator("#note-highlights .note-chip")).toHaveCount(0)
    })

    test("saving a note that no longer names a file removes it", async ({ authenticatedPage: page }) => {
        const name = freshName()
        await attach(page, name)
        await page.locator("#note-save").click()
        await expect(page.locator("#note-status")).toHaveText("Saved")

        // Deleting the token is how a file is removed by editing rather than through the hover card.
        await page.locator("#note-input").fill("Nothing embedded any more.")
        await page.locator("#note-save").click()
        await expect(page.locator("#note-status")).toHaveText("Saved")

        await page.reload()
        await expect(page.locator("#note-highlights .note-chip")).toHaveCount(0)
        const remaining = await page.evaluate(async (day: string) => {
            const resp = await fetch(`/api/v1/notes/${day}/attachments`)
            return (await resp.json()).attachments.length
        }, todayStr())
        expect(remaining).toBe(0)
    })

    test("the Settings export offers to leave attachments out, and only once there are some", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        // The account starts each run with the day cleared, so whether the box is there depends on the other days -
        // what this asserts is the LINK behaviour, which is the half a server test cannot see.
        const name = freshName()
        await page.goto("/")
        await attach(page, name)
        await page.locator("#note-save").click()
        await expect(page.locator("#note-status")).toHaveText("Saved")

        await page.goto("/settings")
        const checkbox = page.locator("#data-export-attachments")
        await expect(checkbox).toBeVisible()
        await expect(checkbox).toBeChecked()

        // Checked is the rendered link untouched: an export is a backup, so it carries everything unless asked not to.
        await expect(page.locator("#data-export-link")).toHaveAttribute("href", "/api/v1/data/export")
        await checkbox.uncheck()
        await expect(page.locator("#data-export-link")).toHaveAttribute("href", "/api/v1/data/export?attachments=false")
        await checkbox.check()
        await expect(page.locator("#data-export-link")).toHaveAttribute("href", "/api/v1/data/export")
    })

    test("the notes page lists attachments in their own table, searched by filename", async ({ authenticatedPage: page }) => {
        const name = freshName()
        await attach(page, name)
        await page.locator("#note-save").click()
        await expect(page.locator("#note-status")).toHaveText("Saved")

        await page.goto("/notes")
        const rows = page.locator("#note-attachments-tbody tr.note-attachment-row")
        await expect(rows.filter({ hasText: name })).toHaveCount(1)
        // The day carries the paperclip in the notes table above, since that day now holds a file.
        await expect(page.locator("#notes-tbody tr.note-row .note-row-clip").first()).toBeVisible()

        // The table's own box searches FILENAMES and swaps over HTMX, leaving the page's ?q= (the notes search)
        // alone - which is why nothing here asserts on the URL.
        await page.locator("#note-attachment-search-input").fill(name)
        await expect(rows.filter({ hasText: name })).toHaveCount(1)
        await expect(page).toHaveURL(/\/notes$/)

        // A term matching no FILE empties this table even though the word is in the note itself.
        await page.locator("#note-attachment-search-input").fill("no-such-file-anywhere")
        await expect(page.locator("#note-attachments-empty-row")).toBeVisible()
        await expect(rows).toHaveCount(0)

        // And the name is a link to the file itself, opening in a new tab so the page is not lost.
        await page.locator("#note-attachment-search-input").fill(name)
        const link = rows.filter({ hasText: name }).locator(".note-attachment-link")
        await expect(link).toHaveAttribute("target", "_blank")
        await expect(link).toHaveAttribute("href", new RegExp(`/internal/note-attachments/${todayStr()}/[0-9a-f-]+/file$`))
    })

    test("hovering a file gives a transient card and clicking one pins it", async ({ authenticatedPage: page }) => {
        const name = freshName()
        await attach(page, name)
        await page.locator("#note-save").click()
        await expect(page.locator("#note-status")).toHaveText("Saved")

        const card = page.locator("#note-attachment-card")
        const closeButton = page.locator("#note-attachment-close")
        const pill = page.locator(`#note-highlights .note-chip[data-attachment-name="${name}"]`)
        const rect = await pill.evaluate((element: Element) => {
            const first = element.getClientRects()[0]
            return { x: first.x, y: first.y, width: first.width, height: first.height }
        })
        const centre = { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 }

        // HOVER alone: the card opens, dashed and with no way out but the pointer itself.
        await page.mouse.move(centre.x, centre.y)
        await expect(card).toBeVisible()
        await expect(card).not.toHaveClass(/note-attachment-card-pinned/)
        await expect(closeButton).toBeHidden()

        // Moving the pointer off the note box takes it away again.
        await page.mouse.move(4, 4)
        await expect(card).toBeHidden()

        // CLICK: the same card, pinned - solid, with a close button, and now indifferent to where the pointer goes.
        await page.mouse.move(centre.x, centre.y)
        await page.mouse.click(centre.x, centre.y)
        await expect(card).toHaveClass(/note-attachment-card-pinned/)
        await expect(closeButton).toBeVisible()
        await page.mouse.move(4, 4)
        await expect(card).toBeVisible()

        // And the close button is the way out, leaving the card unpinned for the next hover.
        await closeButton.click()
        await expect(card).toBeHidden()
        await expect(card).not.toHaveClass(/note-attachment-card-pinned/)
    })

    test("a sound file gets a player in the hover card, and an image still gets its thumbnail", async ({ authenticatedPage: page }) => {
        const name = `memo-${Date.now()}.wav`
        await attachSound(page, name)
        await page.locator("#note-save").click()
        await expect(page.locator("#note-status")).toHaveText("Saved")

        await openCardFor(page, name)
        const player = page.locator("#note-attachment-player")
        await expect(player).toBeVisible()
        // The src is the server's own URL for the file, set only for a sound file - the element is inert until then.
        await expect(player).toHaveAttribute("src", new RegExp(`/internal/note-attachments/${todayStr()}/[0-9a-f-]+/file$`))
        // The two previews are alternatives, never both: this one is audio, so the thumbnail stays away.
        await expect(page.locator("#note-attachment-preview-link")).toBeHidden()

        // And the browser really did accept it - readyState only leaves HAVE_NOTHING once the media loads, which is
        // why the fixture is a genuine PCM wav rather than a handful of bytes with the right extension.
        await player.evaluate((el: HTMLAudioElement) => el.load())
        await expect.poll(async () => player.evaluate((el: HTMLAudioElement) => el.readyState)).toBeGreaterThan(0)
    })

    test("a file over the size limit is refused before anything is uploaded", async ({ authenticatedPage: page }) => {
        // The reported failure was a 700 MB file that uploaded for a minute and then died with ERR_CONNECTION_RESET:
        // the body was refused at the HTTP layer, so there was no response to render a message from. The check now
        // happens before the first byte, which is why this asserts that NO request was made at all.
        let attempted = false
        page.on("request", (request) => {
            if (request.method() === "POST" && request.url().includes("note-attachments")) { attempted = true }
        })

        // One byte over the 25 MB default. Allocated rather than read from disk so the spec carries no fixture.
        const oversized = Buffer.alloc(25 * 1024 * 1024 + 1, 1)
        await page.locator("#note-attach-input").setInputFiles({
            name: `too-big-${Date.now()}.bin`,
            mimeType: "application/octet-stream",
            buffer: oversized,
        })

        const error = page.locator("#note-error")
        await expect(error).toContainText(/too large/i)
        // And it NAMES the ceiling, so the refusal is something the user can act on.
        await expect(error).toContainText(/25 MB/)
        expect(attempted).toBe(false)
        await expect(page.locator("#note-progress-row")).toBeHidden()
    })

    test("an upload in progress can be cancelled, and leaves nothing behind", async ({ authenticatedPage: page }) => {
        // Held open server-side so the progress row is reliably on screen to be cancelled.
        await page.route("**/internal/note-attachments/**", async (route) => {
            await new Promise((resolve) => setTimeout(resolve, 5000))
            try { await route.continue() } catch { /* already aborted, which is what this test does */ }
        })

        const name = freshName()
        await attach(page, name)
        await expect(page.locator("#note-progress-row")).toBeVisible()
        await page.locator("#note-attach-cancel").click()

        await expect(page.locator("#note-progress-row")).toBeHidden()
        // No token written, no chip drawn, and no error banner - a cancellation is not a failure to report.
        await expect(page.locator("#note-input")).not.toHaveValue(new RegExp(name))
        await expect(page.locator("#note-error")).toBeEmpty()
        await page.unroute("**/internal/note-attachments/**")
    })

    test("a renamed attachment keeps its uploaded file name, and is findable under either", async ({ authenticatedPage: page }) => {
        const name = freshName()
        const renamed = `Berlin ${Date.now()}`
        await attach(page, name)
        await page.locator("#note-save").click()
        await expect(page.locator("#note-status")).toHaveText("Saved")

        // Rename through the hover card, which is the only way a user can: the note's own token is rewritten with it.
        await openCardFor(page, name)
        await page.locator("#note-attachment-rename").click()
        await page.locator("#note-attachment-rename-input").fill(renamed)
        await page.locator("#note-attachment-rename-input").press("Enter")
        await expect(page.locator("#note-input")).toHaveValue(new RegExp(`\\[\\[${renamed}\\]\\]`))

        await page.goto("/notes")
        const row = page.locator("#note-attachments-tbody tr.note-attachment-row").filter({ hasText: renamed })
        await expect(row).toHaveCount(1)
        // Both columns, side by side: what the note calls it, and what it actually is.
        await expect(row.locator(".note-attachment-link")).toHaveText(new RegExp(renamed))
        await expect(row.locator(".note-attachment-file")).toHaveText(name)

        // Searching the name it was UPLOADED under still finds it, which is the whole reason the second column exists.
        await page.locator("#note-attachment-search-input").fill(name)
        await expect(page.locator("#note-attachments-tbody tr.note-attachment-row").filter({ hasText: renamed })).toHaveCount(1)
        // And the run that matched is marked, in the column it matched in - which is how the row explains itself.
        await expect(page.locator("#note-attachments-tbody mark.note-mark").first()).toBeVisible()

        // And so does searching the name it now has.
        await page.locator("#note-attachment-search-input").fill(renamed)
        await expect(page.locator("#note-attachments-tbody tr.note-attachment-row").filter({ hasText: name })).toHaveCount(1)
    })
})

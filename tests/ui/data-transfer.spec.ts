import { closeSync, ftruncateSync, openSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import type { Page } from "@playwright/test"
import { test, expect } from "../helpers/fixtures"
import { selectTile } from "../helpers/prefs"

/* global window -- referenced inside page.evaluate callbacks, which run in the browser */

// Drive the file input the way a user does. Playwright's setInputFiles needs the bytes on the Node
// side, so the archive is downloaded through the page's own session first and handed back in.
async function chooseArchive(page: Page, archive: Buffer, name = "diurnal-export.zip"): Promise<void> {
    await Promise.all([
        page.waitForResponse(r => new URL(r.url()).pathname === "/internal/data/import/preview"),
        page.locator("#data-import-file").setInputFiles({ name, mimeType: "application/zip", buffer: archive }),
    ])
}

// The export endpoint, fetched with the page's cookie so it is the same bytes the Export button yields.
async function exportArchive(page: Page): Promise<Buffer> {
    const base64 = await page.evaluate(async () => {
        const resp = await fetch("/api/v1/data/export")
        const bytes = new Uint8Array(await resp.arrayBuffer())
        let binary = ""
        bytes.forEach(b => { binary += String.fromCharCode(b) })
        return window.btoa(binary)
    })
    return Buffer.from(base64, "base64")
}

test.describe("Settings → Data", () => {
    test("export downloads a dated archive", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")

        const [download] = await Promise.all([
            page.waitForEvent("download"),
            page.locator("#data-export-link").click(),
        ])

        // Stamped to the second, in the user's own timezone, so two exports on one day do not collide.
        expect(download.suggestedFilename()).toMatch(/^diurnal-export-\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}\.zip$/)
    })

    test("choosing an archive previews it without writing anything, and confirming imports it", async ({ authenticatedPage: page }) => {
        // Seed one action so the account has something for the import to replace.
        await page.goto("/actions")
        await page.evaluate(async () => {
            await fetch("/api/v1/actions", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ name: "Before Import", colour: "#e11d48" }),
            })
        })

        await page.goto("/settings")
        const archive = await exportArchive(page)
        await chooseArchive(page, archive)

        // Nothing is written yet — the preview states what would change and waits for a decision.
        const panel = page.locator("#import-panel")
        await expect(panel).toContainText("This archive holds")
        await expect(page.locator("#data-import-confirm")).toBeVisible()

        await Promise.all([
            page.waitForResponse(r => new URL(r.url()).pathname === "/internal/data/import" && r.status() === 200),
            page.locator("#data-import-confirm").click(),
        ])
        await expect(panel).toContainText("Imported")

        // The archive was this account's own export, so the action it held survives the replace.
        await page.goto("/actions")
        await expect(page.getByText("Before Import")).toBeVisible()
    })

    test("importing an archive restores the settings it carries, and reloads the page onto them", async ({ authenticatedPage: page }) => {
        // Take a backup with the theme set one way, change it, then restore: the archive has to put the exported value back. The reload is the
        // point of the test as much as the value is - theme is an <html> class resolved at render time, so without it the page would keep showing
        // the theme the import has just replaced, and the next save on this page would write the stale value back.
        await page.goto("/settings")
        await selectTile(page, "theme", "dark")
        const archive = await exportArchive(page)

        await page.goto("/settings")
        await selectTile(page, "theme", "light")
        await page.reload()
        await expect(page.locator("html")).not.toHaveClass(/dark/)

        await chooseArchive(page, archive)
        await expect(page.locator("#import-panel")).toContainText("It also restores the settings")
        await Promise.all([
            page.waitForResponse(r => new URL(r.url()).pathname === "/internal/data/import" && r.status() === 200),
            page.locator("#data-import-confirm").click(),
        ])

        await expect(page.locator("html")).toHaveClass(/dark/)
        await expect(page.locator('input[name="theme"][value="dark"]')).toBeChecked()
        // Carried across the reload in sessionStorage: it is the only confirmation the import worked, and losing it to the reload would leave an
        // import that changed nothing visible looking like one that did nothing at all.
        await expect(page.locator("#import-panel")).toContainText("Your settings were restored as well.")
    })

    // The rail is the only thing on the card that says the archive is going anywhere at all, and it earns its
    // place on an archive carrying attachments, where the upload is seconds rather than milliseconds. It sits
    // OUTSIDE #import-panel deliberately: settings.js replaces that element wholesale with every answer, so a
    // rail inside it would be destroyed by the very response it was showing the wait for.
    test("the upload shows a progress rail, and takes it away once the answer lands", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        const archive = await exportArchive(page)

        const rail = page.locator("#data-import-progress-row")
        await expect(rail).toBeHidden()

        // The answer is held until the rail has been asserted: against a local server the whole exchange is over
        // in a few milliseconds, and nothing can be scheduled inside that.
        let release = (): void => { /* replaced synchronously by the executor below */ }
        const answered = new Promise<void>(resolve => { release = resolve })
        await page.route("**/internal/data/import/preview", async route => {
            await answered
            await route.continue()
        })

        await page.locator("#data-import-file").setInputFiles({
            name: "diurnal-export.zip",
            mimeType: "application/zip",
            buffer: archive,
        })
        await expect(rail).toBeVisible()
        release()

        await expect(page.locator("#data-import-confirm")).toBeVisible()
        await expect(rail).toBeHidden()
    })

    test("cancelling a preview clears the panel and writes nothing", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        const archive = await exportArchive(page)
        await chooseArchive(page, archive)

        await expect(page.locator("#data-import-confirm")).toBeVisible()
        await page.locator("#data-import-cancel").click()

        await expect(page.locator("#data-import-confirm")).toHaveCount(0)
        await expect(page.locator("#import-panel")).toBeEmpty()
    })

    // A body over quarkus.http.limits.max-body-size is refused by the HTTP layer itself with an EMPTY 413 that
    // never reaches the application, so this refusal has to be worded by the page before the file is read. It
    // used to be swapped in as-is, which replaced #import-panel with nothing at all: no banner, no panel, and a
    // card that stayed inert until a reload.
    test("a file larger than the upload limit is refused in the panel, without being uploaded", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")

        const limit = Number(await page.locator("#data-import-file").getAttribute("data-max-upload-bytes"))
        expect(limit, "the page must publish the upload bound it checks against").toBeGreaterThan(0)

        // Sparse, so the bytes cost nothing to produce - only the size the input reports matters here.
        const oversized = join(tmpdir(), "diurnal-oversized-import.zip")
        const handle = openSync(oversized, "w")
        ftruncateSync(handle, limit + 1)
        closeSync(handle)

        let uploads = 0
        page.on("request", request => {
            if (new URL(request.url()).pathname.startsWith("/internal/data/import")) {
                uploads += 1
            }
        })

        try {
            await page.locator("#data-import-file").setInputFiles(oversized)
            await expect(page.locator("#import-panel .banner-error")).toContainText("too large to import")
            await expect(page.locator("#data-import-confirm")).toHaveCount(0)
            expect(uploads, "an oversized file must be refused without posting it").toBe(0)

            // The panel survives, so the card still works for a file that IS acceptable.
            await chooseArchive(page, await exportArchive(page))
            await expect(page.locator("#import-panel")).toContainText("This archive holds")
        } finally {
            rmSync(oversized, { force: true })
        }
    })

    test("a file that is not an archive is refused in the panel, leaving no confirm step", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")

        await chooseArchive(page, Buffer.from("date,content\nnot a zip\n", "utf8"), "notes.csv")

        // The refusal is a handled outcome shown in place — not an unhandled failure that leaves the card in
        // the previous state. (The browser still logs the 422 as a failed resource load, which it does for any
        // 4xx however it was requested, so that is not something a test can assert fetch-vs-htmx on.)
        await expect(page.locator("#import-panel")).toContainText("not a ZIP archive")
        await expect(page.locator("#data-import-confirm")).toHaveCount(0)
    })
})

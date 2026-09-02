import { test, expect } from "../helpers/fixtures"
import { waitForSave } from "../helpers/prefs"

// Geometry of one stats-picker row, measured RELATIVE to the row so page scrolling cannot mask a shift.
interface ElementBox { left: number, top: number, height: number }
interface RowGeometry { rowHeight: number, text: ElementBox | null, button: ElementBox | null }

// The Settings "Action stats" picker: the per-stat rename editor (Rename -> input -> Save/Cancel) and how
// it coexists with the row's other gestures. Kept out of settings.spec.ts because that file is at its
// max-lines cap; the fixture gives this spec its own user, so nothing here is shared with it.
test.describe("Settings page - stats picker", () => {
    test("renaming a stat persists, and clearing the name restores the built-in one", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        const row = page.locator("#stats-fields-list li[data-key='current-streak']")
        const caption = row.locator(".stats-field-caption")
        const checked = await row.locator("input[type='checkbox']").isChecked()

        // Rename is revealed on hover only, and Playwright hit-tests before moving the mouse, so fire its
        // click directly (the same caveat settings.spec.ts's Display Name Edit button carries).
        await row.locator(".stats-field-rename-btn").dispatchEvent("click")
        const input = row.locator(".stats-field-input")
        await expect(input).toBeVisible()
        // The editor pre-fills with the stat's CURRENT caption whether or not it has been renamed, so
        // an un-renamed stat opens on its built-in name rather than on an empty field.
        await expect(input).toHaveValue("Current streak")
        await expect(input).toHaveAttribute("placeholder", "Current streak")

        await input.fill("Days in row")
        await waitForSave(page, row.locator(".stats-field-save-btn").click())
        await expect(caption).toHaveText("Days in row")

        await page.reload()
        await expect(caption).toHaveText("Days in row")
        // Renaming is not a toggle: the stat's shown/hidden state is untouched by it.
        expect(await row.locator("input[type='checkbox']").isChecked()).toBe(checked)

        // A renamed stat opens on ITS name, not the built-in one.
        await row.locator(".stats-field-rename-btn").dispatchEvent("click")
        await expect(row.locator(".stats-field-input")).toHaveValue("Days in row")

        // Clearing the field restores the built-in name.
        await row.locator(".stats-field-input").fill("")
        await waitForSave(page, row.locator(".stats-field-save-btn").click())
        await expect(caption).toHaveText("Current streak")
        await page.reload()
        await expect(caption).toHaveText("Current streak")
    })

    test("saving a pre-filled built-in name is not a rename", async ({ authenticatedPage: page }) => {
        // Because the editor pre-fills with the current caption, opening an un-renamed stat and saving it
        // untouched submits the built-in label. That must NOT be stored as a custom name, or the stat would
        // stop tracking the catalogue if its label were ever re-worded.
        await page.goto("/settings")
        const row = page.locator("#stats-fields-list li[data-key='total-count']")

        await row.locator(".stats-field-rename-btn").dispatchEvent("click")
        await expect(row.locator(".stats-field-input")).toHaveValue("Total count")
        await row.locator(".stats-field-save-btn").click()
        await expect(row.locator(".stats-field-input")).toBeHidden()
        await expect(row.locator(".stats-field-caption")).toHaveText("Total count")

        const me = await page.context().request.get("/api/v1/users/me")
        const body = await me.json() as { preferences: { statsFields: Array<{ key: string, label: string | null }> | null } }
        const stored = body.preferences.statsFields?.find(f => f.key === "total-count")
        expect(stored?.label ?? null).toBeNull()
    })

    test("opening the rename editor and saving unchanged sends no request", async ({ authenticatedPage: page }) => {
        // Every other control on this page auto-saves on `change`, so it would be easy for the rename
        // editor to fire a PATCH just for being opened and closed. It must not: an unchanged commit is a
        // no-op, for an un-renamed stat (pre-filled with its built-in name) and a renamed one alike.
        await page.goto("/settings")

        // The stat this test renames, restored to its built-in name first. These specs share a user and
        // database, so a previous run — or a previous failure part-way through this very test — can leave
        // it already renamed, and re-committing a name a stat ALREADY holds is a deliberate no-op that
        // fires no PATCH. The waitForSave below would then wait for a save that is never coming, and the
        // test died on the 30s timeout rather than on anything it asserts. Done BEFORE the counter is
        // attached, so the PATCH that restoring may itself fire is not counted.
        const renamed = page.locator("#stats-fields-list li[data-key='best-year']")
        if ((await renamed.locator(".stats-field-caption").textContent())?.trim() !== "Best year") {
            await renamed.locator(".stats-field-rename-btn").dispatchEvent("click")
            await renamed.locator(".stats-field-input").fill("")
            await waitForSave(page, renamed.locator(".stats-field-save-btn").click())
            await expect(renamed.locator(".stats-field-caption")).toHaveText("Best year")
        }

        let saves = 0
        page.on("request", (r) => {
            if (new URL(r.url()).pathname === "/internal/settings" && r.method() === "PATCH") { saves++ }
        })

        // An un-renamed stat: the field is pre-filled with the built-in name, so committing it is a no-op.
        const untouched = page.locator("#stats-fields-list li[data-key='best-month']")
        await untouched.locator(".stats-field-rename-btn").dispatchEvent("click")
        await expect(untouched.locator(".stats-field-input")).toHaveValue("Best month")
        await untouched.locator(".stats-field-save-btn").click()
        await expect(untouched.locator(".stats-field-input")).toBeHidden()

        // Cancelling is a no-op too, even after typing.
        await untouched.locator(".stats-field-rename-btn").dispatchEvent("click")
        await untouched.locator(".stats-field-input").fill("Discarded")
        await untouched.locator(".stats-field-cancel-btn").click()
        await expect(untouched.locator(".stats-field-input")).toBeHidden()

        // Give any stray request time to be issued before asserting none was.
        await page.waitForTimeout(300)
        expect(saves, "an unchanged rename must not PATCH").toBe(0)

        // Now a REAL rename saves exactly once... (`renamed` is the row restored to its built-in name above)
        await renamed.locator(".stats-field-rename-btn").dispatchEvent("click")
        await renamed.locator(".stats-field-input").fill("Top year")
        await waitForSave(page, renamed.locator(".stats-field-save-btn").click())
        await expect(renamed.locator(".stats-field-caption")).toHaveText("Top year")
        expect(saves, "a real rename saves once").toBe(1)

        // ...and re-committing THAT name unchanged is a no-op as well.
        await renamed.locator(".stats-field-rename-btn").dispatchEvent("click")
        await expect(renamed.locator(".stats-field-input")).toHaveValue("Top year")
        await renamed.locator(".stats-field-save-btn").click()
        await expect(renamed.locator(".stats-field-input")).toBeHidden()
        await page.waitForTimeout(300)
        expect(saves, "re-committing an unchanged custom name must not PATCH").toBe(1)
    })

    test("switching a stat row to edit mode moves neither the text nor the button", async ({ authenticatedPage: page }) => {
        // The caption and its editor are siblings in one slot (the caption carries the input's border and
        // padding as transparent, and both share a line box), and Rename/Save share a fixed-width actions
        // slot — so a row must be pixel-identical either side of the flip. Measured relative to the row so
        // page scrolling cannot mask a shift.
        await page.setViewportSize({ width: 1280, height: 900 })
        await page.goto("/settings")
        const row = page.locator("#stats-fields-list li[data-key='current-streak']")

        const geometry = async (): Promise<RowGeometry> => row.evaluate((li: HTMLElement): RowGeometry => {
            const rowRect = li.getBoundingClientRect()
            const shown = (selector: string): Element | null => {
                const el = li.querySelector(selector)
                return el !== null && el.getClientRects().length > 0 ? el : null
            }
            const rel = (el: Element | null): ElementBox | null => {
                if (el === null) { return null }
                const r = el.getBoundingClientRect()
                return { left: Math.round(r.left - rowRect.left), top: Math.round(r.top - rowRect.top), height: Math.round(r.height) }
            }
            return {
                rowHeight: Math.round(rowRect.height),
                // Whichever of the pair is currently rendered: caption or editor, Rename or Save.
                text: rel(shown(".stats-field-caption") ?? shown(".stats-field-input")),
                button: rel(shown(".stats-field-rename-btn") ?? shown(".stats-field-save-btn")),
            }
        })

        const read = await geometry()
        await row.locator(".stats-field-rename-btn").dispatchEvent("click")
        await expect(row.locator(".stats-field-input")).toBeVisible()
        const edit = await geometry()

        expect(edit.rowHeight, "the row must not change height in edit mode").toBe(read.rowHeight)
        expect(edit.text, "the name must not move as the caption becomes an input").toEqual(read.text)
        expect(edit.button, "Save must render exactly where Rename was").toEqual(read.button)
        // Cancel is the extra control, and it sits beside Save rather than displacing it.
        const cancel = row.locator(".stats-field-cancel-btn")
        await expect(cancel).toBeVisible()
        expect((await cancel.boundingBox())?.x ?? 0, "Cancel sits after Save").toBeGreaterThan(
            (await row.locator(".stats-field-save-btn").boundingBox())?.x ?? 0)

        await cancel.click()
        expect(await geometry(), "cancelling restores the read-mode geometry exactly").toEqual(read)
    })

    test("cancelling a rename keeps the previous name and saves nothing", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        const row = page.locator("#stats-fields-list li[data-key='total-count']")

        await row.locator(".stats-field-rename-btn").dispatchEvent("click")
        await row.locator(".stats-field-input").fill("Never saved")
        await row.locator(".stats-field-cancel-btn").click()

        await expect(row.locator(".stats-field-input")).toBeHidden()
        await expect(row.locator(".stats-field-caption")).toHaveText("Total count")
        await page.reload()
        await expect(row.locator(".stats-field-caption")).toHaveText("Total count")
    })

    test("a stat cannot be shown or hidden while its name is being edited", async ({ authenticatedPage: page }) => {
        // The whole row is a toggle target, so while its rename editor is open a click aimed at the editor
        // that lands a pixel outside it would flip the stat (and save that) behind the user's back. The row
        // is frozen until Save or Cancel.
        await page.goto("/settings")
        let saves = 0
        page.on("request", (r) => {
            if (new URL(r.url()).pathname === "/internal/settings" && r.method() === "PATCH") { saves++ }
        })

        const row = page.locator("#stats-fields-list li[data-key='total-days']")
        const checkbox = row.locator("input[type='checkbox']")
        const enabled = await checkbox.isChecked()

        await row.locator(".stats-field-rename-btn").dispatchEvent("click")
        await expect(row.locator(".stats-field-input")).toBeVisible()

        // The row's own padding, beside the editor — the gesture that toggles a resting row.
        await row.click({ position: { x: 4, y: 4 } })
        expect(await checkbox.isChecked(), "clicking a row mid-rename must not toggle it").toBe(enabled)

        // ...and space on the checkbox, which toggles it NATIVELY (the script's keyboard path), is frozen too.
        await checkbox.focus()
        await page.keyboard.press("Space")
        expect(await checkbox.isChecked(), "space mid-rename must not toggle the stat").toBe(enabled)

        // Give any stray save time to be issued before asserting none was.
        await page.waitForTimeout(300)
        expect(saves, "a swallowed toggle must not PATCH").toBe(0)

        // Cancelling releases the row: the very same click now toggles it, and it persists.
        await row.locator(".stats-field-cancel-btn").click()
        await waitForSave(page, row.click({ position: { x: 4, y: 4 } }))
        expect(await checkbox.isChecked(), "cancelling the rename releases the toggle").toBe(!enabled)
        await page.reload()
        expect(await checkbox.isChecked(), "the released toggle saved").toBe(!enabled)

        // Leave the stat as this spec's other tests found it.
        await waitForSave(page, row.click({ position: { x: 4, y: 4 } }))
    })

    test("a stat cannot be reordered while its name is being edited, and its icons say so", async ({ authenticatedPage: page }) => {
        // The row's other gesture: a drag from the handle. Frozen for the same reason as the toggle — a
        // press aimed at the editor must not move the stat — and both frozen controls fade to show it.
        // The drag is driven through page.mouse because the handler is Pointer Events, not HTML5 DnD.
        await page.goto("/settings")
        const list = page.locator("#stats-fields-list")
        const order = (): Promise<Array<string | undefined>> =>
            list.locator("li").evaluateAll((els: HTMLElement[]) => els.map(el => el.dataset.key))
        const row = list.locator("li[data-key='first-performed']")
        const rowHeight = (await row.boundingBox())?.height ?? 0
        expect(rowHeight, "the row must be laid out before it can be dragged").toBeGreaterThan(0)

        const dragDownTwoRows = async (): Promise<void> => {
            const handle = await row.locator(".stats-field-handle").boundingBox()
            const x = (handle?.x ?? 0) + (handle?.width ?? 0) / 2
            const y = (handle?.y ?? 0) + (handle?.height ?? 0) / 2
            await page.mouse.move(x, y)
            await page.mouse.down()
            await page.mouse.move(x, y + (rowHeight * 2), { steps: 10 })
            await page.mouse.up()
        }
        // The computed opacity of a row control, as a number (1 = not faded).
        const opacityOf = (selector: string): Promise<number> => row.locator(selector).evaluate(
            // `globalThis` (not a bare getComputedStyle) satisfies eslint's no-undef without browser globals.
            (el: HTMLElement) => Number(globalThis.getComputedStyle(el).opacity))

        const resting = await order()
        await row.locator(".stats-field-rename-btn").dispatchEvent("click")
        await expect(row.locator(".stats-field-input")).toBeVisible()

        expect(await opacityOf(".stats-field-handle"), "the drag handle fades while renaming").toBeLessThan(1)
        expect(await opacityOf("input[type='checkbox']"), "the checkbox fades while renaming").toBeLessThan(1)

        await dragDownTwoRows()
        expect(await order(), "dragging a row mid-rename must not reorder it").toEqual(resting)

        // Cancelling releases the row: the very same drag now reorders it, and saves that.
        await row.locator(".stats-field-cancel-btn").click()
        expect(await opacityOf(".stats-field-handle"), "the handle is restored on cancel").toBe(1)
        expect(await opacityOf("input[type='checkbox']"), "the checkbox is restored on cancel").toBe(1)
        await waitForSave(page, dragDownTwoRows())
        const moved = await order()
        expect(moved, "cancelling the rename releases the drag").not.toEqual(resting)
        await page.reload()
        expect(await order(), "the released reorder saved").toEqual(moved)
    })
})

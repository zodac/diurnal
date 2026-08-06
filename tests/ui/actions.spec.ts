import type { Request } from "@playwright/test"
import { test, expect } from "../helpers/fixtures"

/* global window -- referenced inside in-browser page.waitForFunction callbacks */

// Unique-name helper: the DB unique constraint on (user_id, name) forbids two live actions
// with the same name for one user. Using a run-scoped counter + timestamp guarantees every
// test in this run gets a brand-new name that can't collide with another still-live action.
let _seq = 0
const _RUN = Date.now()

function unique(base: string): string {
    return `${base}_${_RUN}_${++_seq}`
}

test.describe("Actions page", () => {
    test.beforeEach(async ({ authenticatedPage: page }) => {
        // Delete all actions before each test so each test starts with a clean list.
        for (let pass = 0; pass < 10; pass++) {
            await page.goto("/actions")
            const items = await page.locator('#action-list [id^="action-"]').all()
            if (items.length === 0) {break}
            for (const item of items) {
                const id = (await item.getAttribute("id"))?.replace("action-", "")
                if (id !== undefined) {
                    await page.evaluate(async (actionId: string) => {
                        await fetch(`/internal/actions/${actionId}/delete`, { method: "POST" })
                    }, id)
                }
            }
        }
    })

    test("create action appears in list", async ({ authenticatedPage: page }) => {
        const name = unique("Running")
        await page.goto("/actions")
        await page.waitForFunction(() => typeof (window as {htmx?: unknown}).htmx !== "undefined")
        await page.fill('input[name="name"]', name)
        await Promise.all([
            page.waitForResponse(r => r.url().endsWith("/actions") && r.request().method() === "POST"),
            page.locator('form[hx-post="/internal/actions"] button[type="submit"]').click(),
        ])
        await expect(page.locator("#action-list")).toContainText("Running")
    })

    // The form is served with a suggestion already in the picker, so a new action is distinguishable on the
    // calendar even from a user who never touches the control. #64748b is the neutral slate that used to be
    // the initial value (ActionValidation.DEFAULT_COLOUR) - nothing is created in it any more.
    test("the new-action colour picker opens on a randomised colour, not the neutral grey", async ({ authenticatedPage: page }) => {
        await page.goto("/actions")
        const picker = page.locator('#new-action-form input[name="colour"]')

        await expect(picker).toHaveValue(/^#[0-9a-f]{6}$/)
        await expect(picker).not.toHaveValue("#64748b")
    })

    // A successful add resets the form, which would restore the colour the new action now owns. The page
    // script draws a fresh suggestion instead, so the "already randomised" state holds for every add.
    test("adding an action re-randomises the picker away from the colour just used", async ({ authenticatedPage: page }) => {
        const name = unique("Rerandomised")
        await page.goto("/actions")
        await page.waitForFunction(() => typeof (window as {htmx?: unknown}).htmx !== "undefined")
        const picker = page.locator('#new-action-form input[name="colour"]')
        const used = await picker.inputValue()

        await page.fill('input[name="name"]', name)
        await Promise.all([
            page.waitForResponse(r => r.url().endsWith("/internal/actions/random-colour")),
            page.locator('form[hx-post="/internal/actions"] button[type="submit"]').click(),
        ])

        await expect(page.locator("#action-list")).toContainText(name)
        await expect(picker).not.toHaveValue(used)
    })

    test("randomise button fills the colour picker with a new colour", async ({ authenticatedPage: page }) => {
        await page.goto("/actions")
        const picker = page.locator('#new-action-form input[name="colour"]')
        const before = await picker.inputValue()

        // The picker already shows a suggestion, and the suggester excludes the colours the user has SAVED -
        // not the one merely on display - so a single draw may legitimately return the value already there.
        // Click until it moves; a button that does nothing fails on the attempts running out.
        let after = before
        for (let attempt = 0; attempt < 10 && after === before; attempt++) {
            await Promise.all([
                page.waitForResponse(r => r.url().endsWith("/internal/actions/random-colour")),
                page.locator("[data-random-colour]").click(),
            ])
            after = await picker.inputValue()
        }

        expect(after).not.toBe(before)
        expect(after).toMatch(/^#[0-9a-f]{6}$/)
    })

    test("randomised colour is used by the created action", async ({ authenticatedPage: page }) => {
        const name = unique("Randomised")
        await page.goto("/actions")
        await page.waitForFunction(() => typeof (window as {htmx?: unknown}).htmx !== "undefined")
        await Promise.all([
            page.waitForResponse(r => r.url().endsWith("/internal/actions/random-colour")),
            page.locator("[data-random-colour]").click(),
        ])
        const colour = await page.locator('#new-action-form input[name="colour"]').inputValue()

        await page.fill('input[name="name"]', name)
        await Promise.all([
            page.waitForResponse(r => r.url().endsWith("/actions") && r.request().method() === "POST"),
            page.locator('form[hx-post="/internal/actions"] button[type="submit"]').click(),
        ])

        await expect(page.locator("#action-list")).toContainText(name)
        await expect(page.locator(`#action-list [id^="action-"] input[value="${colour}"]`).first()).toHaveCount(1)
    })

    test("create action with duplicate name shows error", async ({ authenticatedPage: page }) => {
        const name = unique("Cycling")
        await page.goto("/actions")
        await page.waitForFunction(() => typeof (window as {htmx?: unknown}).htmx !== "undefined")
        await page.fill('input[name="name"]', name)
        await Promise.all([
            page.waitForResponse(r => r.url().endsWith("/actions") && r.request().method() === "POST"),
            page.locator('form[hx-post="/internal/actions"] button[type="submit"]').click(),
        ])
        await expect(page.locator("#action-list")).toContainText("Cycling")

        // Submit again with the same name
        await page.fill('input[name="name"]', name)
        await Promise.all([
            page.waitForResponse(r => r.url().endsWith("/actions") && r.request().method() === "POST"),
            page.locator('form[hx-post="/internal/actions"] button[type="submit"]').click(),
        ])
        await expect(page.locator("#action-error")).toBeVisible()
        await expect(page.locator("#action-error")).toContainText(/already exists/i)
    })

    test("create action with an invisible character in the name shows error", async ({ authenticatedPage: page }) => {
        // A zero-width space renders as nothing, so this name is indistinguishable on screen from the plain one. The shared text
        // pipeline rejects it rather than storing two names that look identical; the UI must surface that as the usual banner.
        const name = `${unique("Swimming")}\u200B`
        await page.goto("/actions")
        await page.waitForFunction(() => typeof (window as {htmx?: unknown}).htmx !== "undefined")
        await page.fill('input[name="name"]', name)
        await Promise.all([
            page.waitForResponse(r => r.url().endsWith("/actions") && r.request().method() === "POST"),
            page.locator('form[hx-post="/internal/actions"] button[type="submit"]').click(),
        ])
        await expect(page.locator("#action-error")).toBeVisible()
        await expect(page.locator("#action-error")).toContainText(/invisible or text-direction/i)
        await expect(page.locator("#action-list")).not.toContainText("Swimming")
    })

    test("create action with an emoji in the name is accepted and rendered", async ({ authenticatedPage: page }) => {
        const name = `${unique("Gym")} 💪`
        await page.goto("/actions")
        await page.waitForFunction(() => typeof (window as {htmx?: unknown}).htmx !== "undefined")
        await page.fill('input[name="name"]', name)
        await Promise.all([
            page.waitForResponse(r => r.url().endsWith("/actions") && r.request().method() === "POST"),
            page.locator('form[hx-post="/internal/actions"] button[type="submit"]').click(),
        ])
        await expect(page.locator("#action-list")).toContainText("💪")
    })

    test("edit action: inline form appears and updates name in-place", async ({ authenticatedPage: page }) => {
        const origName = unique("OldName")
        const newName = unique("NewName")
        await page.goto("/actions")
        await page.waitForFunction(() => typeof (window as {htmx?: unknown}).htmx !== "undefined")
        await page.fill('input[name="name"]', origName)
        await Promise.all([
            page.waitForResponse(r => r.url().endsWith("/actions") && r.request().method() === "POST"),
            page.locator('form[hx-post="/internal/actions"] button[type="submit"]').click(),
        ])
        await expect(page.locator("#action-list")).toContainText("OldName")

        // Hover over the item to reveal the Edit button, then click it
        const item = page.locator('#action-list [id^="action-"]').filter({ hasText: origName })
        const itemId = await item.getAttribute("id")
        await item.hover()
        await item.getByRole("button", { name: "Edit" }).click()
        // HTMX swaps the div outerHTML with the edit form (same ID, no longer has visible "OldName" text)
        const editForm = page.locator(`#${itemId}`)
        await expect(editForm.locator('input[name="name"]')).toBeVisible()

        await editForm.locator('input[name="name"]').fill(newName)
        await editForm.locator('button[type="submit"]').click()

        await expect(page.locator("#action-list")).toContainText("NewName")
        await expect(page.locator("#action-list")).not.toContainText("OldName")
    })

    test("edit action: the name column does not shift when the row enters edit state", async ({ authenticatedPage: page }) => {
        const name = unique("Steady")
        await page.goto("/actions")
        await page.waitForFunction(() => typeof (window as {htmx?: unknown}).htmx !== "undefined")
        await page.fill('input[name="name"]', name)
        await Promise.all([
            page.waitForResponse(r => r.url().endsWith("/actions") && r.request().method() === "POST"),
            page.locator('form[hx-post="/internal/actions"] button[type="submit"]').click(),
        ])

        // The colour column is sized for its widest (edit) state, so revealing the picker and the
        // randomise button must not push the name along. Its LEFT EDGE is the assertion (the cell's
        // own width legitimately changes on a narrow viewport, where the long name text gives way to a
        // w-full input that can shrink). Measured as offsetLeft WITHIN the table, not as a viewport
        // box: on a narrow viewport the table overflows its .overflow-x-auto wrap, and focusing the
        // edit input scrolls that wrap sideways - a scroll, not a shift, which a viewport-relative x
        // would report as a spurious move.
        const item = page.locator('#action-list [id^="action-"]').filter({ hasText: name })
        const nameCell = item.locator("td").nth(1)
        const cellOffset = (): Promise<number> => nameCell.evaluate((cell: HTMLElement) => cell.offsetLeft)
        const readOffset = await cellOffset()
        await item.hover()
        await item.getByRole("button", { name: "Edit" }).click()
        await expect(item.locator('input[name="name"]')).toBeVisible()

        expect(await cellOffset()).toBe(readOffset)
    })

    test("edit action: randomise button recolours the row", async ({ authenticatedPage: page }) => {
        const name = unique("Recoloured")
        await page.goto("/actions")
        await page.waitForFunction(() => typeof (window as {htmx?: unknown}).htmx !== "undefined")
        await page.fill('input[name="name"]', name)
        await Promise.all([
            page.waitForResponse(r => r.url().endsWith("/actions") && r.request().method() === "POST"),
            page.locator('form[hx-post="/internal/actions"] button[type="submit"]').click(),
        ])

        const item = page.locator('#action-list [id^="action-"]').filter({ hasText: name })
        const itemId = await item.getAttribute("id")
        await item.hover()
        await item.getByRole("button", { name: "Edit" }).click()
        const editRow = page.locator(`#${itemId}`)
        const picker = editRow.locator('input[name="colour"]')
        const before = await picker.inputValue()

        await Promise.all([
            page.waitForResponse(r => r.url().endsWith("/internal/actions/random-colour")),
            editRow.getByRole("button", { name: "Random colour" }).click(),
        ])
        const randomised = await picker.inputValue()
        expect(randomised).not.toBe(before)

        // The randomised value is a real change, so Save posts it and the row comes back in the new colour.
        await Promise.all([
            page.waitForResponse(r => r.url().includes(`/internal/actions/${itemId?.replace("action-", "")}`) && r.request().method() === "POST"),
            editRow.locator('button[type="submit"]').click(),
        ])
        await expect(page.locator(`#${itemId} input[name="colour"]`)).toHaveValue(randomised)
    })

    test("edit action: saving with no change makes no request and restores view", async ({ authenticatedPage: page }) => {
        const origName = unique("Unchanged")
        await page.goto("/actions")
        await page.waitForFunction(() => typeof (window as {htmx?: unknown}).htmx !== "undefined")
        await page.fill('input[name="name"]', origName)
        await Promise.all([
            page.waitForResponse(r => r.url().endsWith("/actions") && r.request().method() === "POST"),
            page.locator('form[hx-post="/internal/actions"] button[type="submit"]').click(),
        ])
        await expect(page.locator("#action-list")).toContainText(origName)

        const item = page.locator('#action-list [id^="action-"]').filter({ hasText: origName })
        const itemId = await item.getAttribute("id")
        await item.hover()
        await item.getByRole("button", { name: "Edit" }).click()
        const editForm = page.locator(`#${itemId}`)
        await expect(editForm.locator('input[name="name"]')).toBeVisible()

        // Save without touching name or colour → no POST should fire, and the row returns to view state.
        let posted = false
        const watch = (r: Request): void => {
            if (r.url().endsWith(`/actions/${itemId?.replace("action-", "")}`) && r.method() === "POST") {posted = true}
        }
        page.on("request", watch)
        await editForm.locator('button[type="submit"]').click()
        // Give any (unwanted) request a chance to fire, then assert none did.
        await page.waitForTimeout(300)
        page.off("request", watch)
        expect(posted).toBe(false)
        // Back in view state (no swap, same row): the edit input is hidden and the name text shows.
        await expect(editForm.locator('input[name="name"]')).toBeHidden()
        await expect(item.locator("[data-dt-view]").filter({ hasText: origName })).toBeVisible()
    })

    test("delete action: confirm panel appears then removes action", async ({ authenticatedPage: page }) => {
        const name = unique("ToDelete")
        await page.goto("/actions")
        await page.waitForFunction(() => typeof (window as {htmx?: unknown}).htmx !== "undefined")
        await page.fill('input[name="name"]', name)
        await Promise.all([
            page.waitForResponse(r => r.url().endsWith("/actions") && r.request().method() === "POST"),
            page.locator('form[hx-post="/internal/actions"] button[type="submit"]').click(),
        ])
        await expect(page.locator("#action-list")).toContainText("ToDelete")

        const item = page.locator('#action-list [id^="action-"]').filter({ hasText: name })
        await item.hover()
        await item.getByRole("button", { name: "Delete" }).click()

        // Confirm delete button appears inside the same element
        await expect(item).toContainText(/delete|confirm/i)
        await item.locator("button").filter({ hasText: /delete|yes|confirm/i }).click()

        await expect(page.locator("#action-list")).not.toContainText("ToDelete")
    })

    test("arming delete on a second action clears the pending confirm on the first", async ({ authenticatedPage: page }) => {
        const first = unique("FirstArmed")
        const second = unique("SecondArmed")
        await page.goto("/actions")
        await page.evaluate(async (names: string[]) => {
            for (const name of names) {
                const params = new URLSearchParams({ name, colour: "#6366f1" })
                await fetch("/internal/actions", {
                    method: "POST",
                    headers: { "Content-Type": "application/x-www-form-urlencoded" },
                    body: params.toString(),
                })
            }
        }, [first, second])
        await page.reload()

        const firstItem = page.locator('#action-list [id^="action-"]').filter({ hasText: first })
        const secondItem = page.locator('#action-list [id^="action-"]').filter({ hasText: second })

        // Arm delete on the first row → its confirm prompt shows.
        await firstItem.hover()
        await firstItem.getByRole("button", { name: "Delete" }).click()
        await expect(firstItem).toContainText(/Delete this action\?/i)

        // Now arm delete on the second row → the first must revert to its normal (un-armed) state.
        await secondItem.hover()
        await secondItem.getByRole("button", { name: "Delete" }).click()
        await expect(secondItem).toContainText(/Delete this action\?/i)
        await expect(firstItem).not.toContainText(/Delete this action\?/i)
        // Both actions still exist — nothing was actually deleted.
        await expect(firstItem).toBeVisible()
        await expect(secondItem).toBeVisible()
    })

    test("editing a row clears a pending edit/delete on the previously-selected row", async ({ authenticatedPage: page }) => {
        const first = unique("First_Sel")
        const second = unique("Second_Sel")
        await page.goto("/actions")
        await page.evaluate(async (names: string[]) => {
            for (const name of names) {
                const params = new URLSearchParams({ name, colour: "#6366f1" })
                await fetch("/internal/actions", {
                    method: "POST",
                    headers: { "Content-Type": "application/x-www-form-urlencoded" },
                    body: params.toString(),
                })
            }
        }, [first, second])
        await page.reload()

        const firstItem = page.locator('#action-list [id^="action-"]').filter({ hasText: first })
        const secondItem = page.locator('#action-list [id^="action-"]').filter({ hasText: second })

        // Open the inline edit form on the first row.
        await firstItem.hover()
        await firstItem.getByRole("button", { name: "Edit" }).click()
        await expect(firstItem.locator('input[name="name"]')).toBeVisible()

        // Open edit on the second row → the first row's edit form must revert to its view state.
        await secondItem.hover()
        await secondItem.getByRole("button", { name: "Edit" }).click()
        await expect(secondItem.locator('input[name="name"]')).toBeVisible()
        await expect(firstItem.locator('input[name="name"]')).toBeHidden()
        await expect(firstItem).toContainText(first)
    })

    test("pagination: Next and Previous navigate between pages", async ({ authenticatedPage: page }) => {
        // Create 11 actions to exceed the default page size of 10
        const prefix = unique("PagAction")
        await page.goto("/actions")
        for (let i = 1; i <= 11; i++) {
            await page.evaluate(async ({ name, colour }: { name: string; colour: string }) => {
                const params = new URLSearchParams({ name, colour })
                await fetch("/internal/actions", {
                    method: "POST",
                    headers: { "Content-Type": "application/x-www-form-urlencoded" },
                    body: params.toString(),
                })
            }, { name: `${prefix}_${i.toString().padStart(2, "0")}`, colour: "#6366f1" })
        }

        await page.goto("/actions")
        await expect(page.locator("#action-list")).toContainText("Next")

        await page.locator("#action-list").getByText("Next").click()
        await expect(page.locator("#action-list")).toContainText("Previous")
    })

    test("search filters action list case-insensitively", async ({ authenticatedPage: page }) => {
        const morningRun = unique("Morning Run")
        const eveningWalk = unique("Evening Walk")
        await page.goto("/actions")
        await page.evaluate(async ({ morning, evening }: { morning: string; evening: string }) => {
            for (const name of [morning, evening]) {
                const params = new URLSearchParams({ name, colour: "#6366f1" })
                await fetch("/internal/actions", {
                    method: "POST",
                    headers: { "Content-Type": "application/x-www-form-urlencoded" },
                    body: params.toString(),
                })
            }
        }, { morning: morningRun, evening: eveningWalk })
        await page.reload()

        await page.fill('input[placeholder*="Search"], input[name="q"]', "MORNING")
        // HTMX fires on input — wait for the list to update
        await expect(page.locator("#action-list")).toContainText("Morning Run")
        await expect(page.locator("#action-list")).not.toContainText("Evening Walk")

        // Clear search restores full list
        await page.fill('input[placeholder*="Search"], input[name="q"]', "")
        await expect(page.locator("#action-list")).toContainText("Evening Walk")
    })

    test("empty account hides the search bar and table; first action reveals them, deleting the last hides them again", async ({ authenticatedPage: page }) => {
        // beforeEach deleted every action, so the account starts empty.
        await page.goto("/actions")
        await page.waitForFunction(() => typeof (window as {htmx?: unknown}).htmx !== "undefined")
        await expect(page.locator("#search-input")).toBeHidden()
        await expect(page.locator(".dt-table")).toBeHidden()
        // The New action form is always available.
        await expect(page.locator('form[hx-post="/internal/actions"]')).toBeVisible()

        // Adding the first action reveals the search bar and table.
        const name = unique("FirstAction")
        await page.fill('input[name="name"]', name)
        await Promise.all([
            page.waitForResponse(r => r.url().endsWith("/actions") && r.request().method() === "POST"),
            page.locator('form[hx-post="/internal/actions"] button[type="submit"]').click(),
        ])
        await expect(page.locator("#search-input")).toBeVisible()
        await expect(page.locator(".dt-table")).toBeVisible()
        await expect(page.locator("#action-list")).toContainText(name)

        // Deleting the last action hides the search bar and table again.
        const item = page.locator('#action-list [id^="action-"]').filter({ hasText: name })
        await item.hover()
        await item.getByRole("button", { name: "Delete" }).click()
        await item.locator("button").filter({ hasText: /delete|yes|confirm/i }).click()
        await expect(page.locator("#actions-section")).toBeHidden()
        await expect(page.locator("#search-input")).toBeHidden()
    })

    test("search with no matches keeps the (empty) table visible while actions still exist", async ({ authenticatedPage: page }) => {
        const name = unique("Meditate")
        await page.goto("/actions")
        await page.waitForFunction(() => typeof (window as {htmx?: unknown}).htmx !== "undefined")
        await page.fill('input[name="name"]', name)
        await Promise.all([
            page.waitForResponse(r => r.url().endsWith("/actions") && r.request().method() === "POST"),
            page.locator('form[hx-post="/internal/actions"] button[type="submit"]').click(),
        ])
        await expect(page.locator("#action-list")).toContainText(name)

        // A search matching no action keeps the table visible (the user still has actions).
        await page.fill("#search-input", "zzz-no-such-action-zzz")
        await expect(page.locator(".dt-table")).toBeVisible()
        await expect(page.locator("#search-input")).toBeVisible()
        await expect(page.locator("#action-list")).toContainText(/no actions match/i)
    })
})

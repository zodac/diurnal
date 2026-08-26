import type { Page } from "@playwright/test"
import { test, expect } from "../helpers/fixtures"
import { establishNumericPref, pickComboOption, waitForSave } from "../helpers/prefs"

/* global getComputedStyle, document -- referenced inside the in-browser page.evaluate callbacks below */

// Theme and calendar style are chosen from preview tiles backed by hidden radio inputs. Tests in
// a spec share one user, so a value may already be selected; we check the radio and always dispatch
// `change` so the htmx save fires regardless. Page size is now preset pills + a number field, driven
// directly in each test, and every dropdown is the hand-rolled listbox (see prefs.ts's pickComboOption).
async function selectTile(page: Page, name: string, value: string): Promise<void> {
    await waitForSave(page, page.locator(`input[name="${name}"][value="${value}"]`).evaluate(
        (el: HTMLInputElement) => {
            el.checked = true
            el.dispatchEvent(new Event("change", { bubbles: true }))
        }))
}

// Open the Display Name field's edit mode by its Edit button. The button is scoped to
// #display-name-view because the Account card now also has a Password field with its own Edit
// button. It is revealed only on hover (opacity + pointer-events), and Playwright runs its
// pre-click hit-test *before* moving the mouse, so a plain .click() can deadlock on the container
// ("<div id=display-name-view> intercepts pointer events"). dispatchEvent fires the button's
// onclick (startEditDisplayName) directly — exactly the behaviour under test — with no hit-test.
async function clickDisplayNameEdit(page: Page): Promise<void> {
    await page.locator("#display-name-view").getByRole("button", { name: "Edit" }).dispatchEvent("click")
}

// Open the Password field's edit mode. Same hover-reveal caveat as the Display Name Edit button, so
// fire the onclick (startEditPassword) directly rather than hit-testing a hover-hidden button.
async function clickPasswordEdit(page: Page): Promise<void> {
    await page.locator("#password-view").getByRole("button", { name: "Edit" }).dispatchEvent("click")
}

test.describe("Settings page", () => {
    test("select dark theme persists across reload", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await selectTile(page, "theme", "dark")

        await page.reload()
        await expect(page.locator("html")).toHaveClass(/dark/)
        await expect(page.locator('input[name="theme"][value="dark"]')).toBeChecked()
    })

    test("select light theme persists across reload and removes dark class", async ({ authenticatedPage: page }) => {
        // Set to dark first
        await page.goto("/settings")
        await selectTile(page, "theme", "dark")
        await page.reload()

        // Now switch to light
        await page.goto("/settings")
        await selectTile(page, "theme", "light")

        await page.reload()
        await expect(page.locator("html")).not.toHaveClass(/dark/)
        await expect(page.locator('input[name="theme"][value="light"]')).toBeChecked()
    })

    test("select system theme persists across reload", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await selectTile(page, "theme", "dark")
        await page.reload()

        await page.goto("/settings")
        await selectTile(page, "theme", "system")

        await page.reload()
        await expect(page.locator('input[name="theme"][value="system"]')).toBeChecked()
    })

    test("theme picker offers exactly System, Light, and Dark tiles", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        const values = await page.locator('input[name="theme"]').evaluateAll(
            els => els.map(e => (e as HTMLInputElement).value))
        expect(values).toEqual(["system", "light", "dark"])
        const labels = await page.locator('[role="radiogroup"][aria-label="Theme"] .preview-label').allInnerTexts()
        expect(labels).toEqual(["System", "Light", "Dark"])
    })

    test("font picker offers exactly Nova, Standard, and OpenDyslexic tiles", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        const values = await page.locator('input[name="font"]').evaluateAll(
            els => els.map(e => (e as HTMLInputElement).value))
        expect(values).toEqual(["nova", "standard", "dyslexic"])
        const labels = await page.locator('[role="radiogroup"][aria-label="Font"] .preview-label').allInnerTexts()
        expect(labels).toEqual(["Nova", "Standard", "OpenDyslexic"])
    })

    test("select OpenDyslexic font applies font-dyslexic and persists across reload", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await selectTile(page, "font", "dyslexic")
        // Live toggle sets the class immediately (before any reload).
        await expect(page.locator("html")).toHaveClass(/font-dyslexic/)

        await page.reload()
        await expect(page.locator("html")).toHaveClass(/font-dyslexic/)
        await expect(page.locator("html")).not.toHaveClass(/font-nova/)
        await expect(page.locator('input[name="font"][value="dyslexic"]')).toBeChecked()
    })

    test("select Standard font clears font-dyslexic and font-nova", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await selectTile(page, "font", "dyslexic")
        await page.reload()

        await page.goto("/settings")
        await selectTile(page, "font", "standard")

        await page.reload()
        await expect(page.locator("html")).not.toHaveClass(/font-dyslexic/)
        await expect(page.locator("html")).not.toHaveClass(/font-nova/)
        await expect(page.locator('input[name="font"][value="standard"]')).toBeChecked()
    })

    test("change page size to 25 via preset pill persists", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await establishNumericPref(page, "pageSizePresets", "pageSize", "25")

        await page.goto("/settings")
        await expect(page.locator("#pageSize")).toHaveValue("25")
    })

    test("page size offers preset pills for the standard options", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        const values = await page.locator("#pageSizePresets .num-pref-pill").evaluateAll(pills =>
            pills.map(p => (p as HTMLElement).dataset.value))
        expect(values).toEqual(["5", "10", "25", "50"])
    })

    test("change decimal places to 2 via preset pill persists", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await establishNumericPref(page, "decimalPlacesPresets", "decimalPlaces", "2")

        await page.goto("/settings")
        await expect(page.locator("#decimalPlaces")).toHaveValue("2")
    })

    // Regression: pages used to be served `Cache-Control: no-cache`, which lets the browser STORE the
    // response — and a history navigation deliberately skips revalidation, so Back repainted the page
    // exactly as it was BEFORE the save. The database was right, the page had shown the new value, and
    // then Back silently contradicted both. Pages are `no-store` now (see application.properties), which
    // is the only directive that makes every Back a real request.
    test("a saved preference is still shown after navigating away and pressing Back", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await establishNumericPref(page, "decimalPlacesPresets", "decimalPlaces", "0")

        // Re-load so the browser has a stored copy of /settings showing the OLD value to fall back on.
        await page.goto("/settings")
        await establishNumericPref(page, "decimalPlacesPresets", "decimalPlaces", "2")

        await page.goto("/stats")
        await page.goBack()

        await expect(page.locator("#decimalPlaces")).toHaveValue("2")
        await expect(page.locator("#decimalPlacesPresets .num-pref-pill-active")).toHaveAttribute("data-value", "2")
    })

    test("decimal places offers a preset pill for every accepted value", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        const values = await page.locator("#decimalPlacesPresets .num-pref-pill").evaluateAll(pills =>
            pills.map(p => (p as HTMLElement).dataset.value))
        expect(values).toEqual(["0", "1", "2"])
    })

    test("decimal places has no stepper: the pills are the only input", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        const row = page.locator("#decimal-places-row")

        // No fine-tune stepper, and the field itself is hidden, so 0/1/2 cannot be escaped from the UI
        // (the server rejects anything else regardless - see SettingsIT).
        await expect(row.locator(".num-pref-stepper")).toHaveCount(0)
        await expect(page.locator("#decimalPlaces")).toHaveAttribute("type", "hidden")

        // The row explains where the preference is applied.
        await expect(row.locator(".help-text")).toContainText("statistics")
    })

    test("entering an invalid page size is rejected, shows an error, and keeps the previous value", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        // Establish a known-good value first (25 via its preset pill).
        await establishNumericPref(page, "pageSizePresets", "pageSize", "25")

        // The Preferences card's status indicator (shared with the "Saved" flash).
        const indicator = page.locator(".card", { has: page.locator("#page-size-row") }).locator("[data-saved]")
        const field = page.locator("#pageSize")

        // Type an out-of-range value and commit it (blur fires the change → save).
        await field.fill("0")
        await Promise.all([
            page.waitForResponse(r =>
                r.url().includes("/internal/settings")
                && r.request().method() === "PATCH"
                && r.status() === 422),
            field.blur(),
        ])

        // The error is shown in red and states the valid range; the field reverts to the last good value.
        await expect(indicator).toHaveClass(/text-danger/)
        await expect(indicator).toContainText(/between 1 and 100/)
        await expect(field).toHaveValue("25")

        // And the rejected value was never persisted.
        await page.goto("/settings")
        await expect(page.locator("#pageSize")).toHaveValue("25")
    })

    test("settings page shows account display name and email", async ({ authenticatedPage: page, testUser }) => {
        await page.goto("/settings")
        // Display name may differ from testUser.displayName if a prior test run changed it without
        // restoring — verify it is present and non-empty; the email is immutable and checked exactly.
        await expect(page.locator("#display-name-text")).not.toBeEmpty()
        await expect(page.locator("body")).toContainText(testUser.email)
    })

    test("display name is read-only by default with an Edit button", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await expect(page.locator("#account-form")).toBeHidden()
        await expect(page.locator("#display-name-view").getByRole("button", { name: "Edit" })).toBeVisible()
    })

    test("clicking Edit shows the input with Save and Cancel buttons", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await clickDisplayNameEdit(page)
        await expect(page.locator("#account-form")).toBeVisible()
        await expect(page.locator("#display-name-view")).toBeHidden()
        await expect(page.getByRole("button", { name: "Save" })).toBeVisible()
        await expect(page.getByRole("button", { name: "Cancel" })).toBeVisible()
    })

    test("Cancel restores read mode without saving", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        // Capture the current name before editing — it may differ from testUser.displayName if a
        // previous test run changed it. Cancel must restore exactly what was there before.
        const nameBefore = await page.locator("#display-name-text").textContent() ?? ""
        await clickDisplayNameEdit(page)
        await page.fill('input[name="displayName"]', "Should Not Save")
        await page.getByRole("button", { name: "Cancel" }).click()
        await expect(page.locator("#account-form")).toBeHidden()
        await expect(page.locator("#display-name-text")).toHaveText(nameBefore)
    })

    test("update display name persists across reload", async ({ authenticatedPage: page, testUser }) => {
        await page.goto("/settings")
        await clickDisplayNameEdit(page)
        await page.fill('input[name="displayName"]', "Updated Name")
        await Promise.all([
            page.waitForResponse(r => new URL(r.url()).pathname === "/internal/settings" && r.request().method() === "PATCH"),
            page.getByRole("button", { name: "Save" }).click(),
        ])
        await expect(page.locator("#display-name-text")).toHaveText("Updated Name")

        await page.reload()
        await expect(page.locator("#display-name-text")).toHaveText("Updated Name")

        // Restore the original display name so subsequent viewports (e.g. mobile-chrome)
        // see the expected testUser.displayName rather than this test's intermediate value.
        await clickDisplayNameEdit(page)
        await page.fill('input[name="displayName"]', testUser.displayName)
        await Promise.all([
            page.waitForResponse(r => new URL(r.url()).pathname === "/internal/settings" && r.request().method() === "PATCH"),
            page.getByRole("button", { name: "Save" }).click(),
        ])
        await expect(page.locator("#display-name-text")).toHaveText(testUser.displayName)
    })

    test("saving an unchanged display name sends no request", async ({ authenticatedPage: page }) => {
        // Opening the editor and saving what is already stored is not a change, so it must never reach
        // the backend (mirroring the stats rename editor below) — the editor just closes.
        await page.goto("/settings")
        let saves = 0
        page.on("request", (r) => {
            if (new URL(r.url()).pathname === "/internal/settings" && r.method() === "PATCH") { saves++ }
        })

        await clickDisplayNameEdit(page)
        await page.getByRole("button", { name: "Save" }).click()
        await expect(page.locator("#account-form")).toBeHidden()
        // Give any stray request time to be issued before asserting none was.
        await page.waitForTimeout(300)
        expect(saves, "an unchanged display name must not PATCH").toBe(0)

        // Typing and re-typing the SAME value is still no change.
        const stored = await page.locator("#display-name-text").textContent() ?? ""
        await clickDisplayNameEdit(page)
        await page.fill('input[name="displayName"]', "Something else")
        await page.fill('input[name="displayName"]', stored)
        await page.getByRole("button", { name: "Save" }).click()
        await expect(page.locator("#account-form")).toBeHidden()
        await page.waitForTimeout(300)
        expect(saves, "re-typing the stored display name must not PATCH").toBe(0)
    })

    test("the Edit button sits in the slot its edit-mode Save will occupy", async ({ authenticatedPage: page }) => {
        // Both rows swap a single Edit for Save + Cancel, so the read state reserves the trailing Cancel
        // slot: Edit and Save must land on the same pixel, and the cursor never has to move.
        await page.setViewportSize({ width: 1280, height: 900 })
        await page.goto("/settings")

        const nameEdit = await page.locator("#display-name-view").getByRole("button", { name: "Edit" }).boundingBox()
        await clickDisplayNameEdit(page)
        const nameSave = await page.locator("#account-form").getByRole("button", { name: "Save" }).boundingBox()
        expect(nameSave?.x, "Display Name: Save lands on the Edit button").toBeCloseTo(nameEdit?.x ?? -1, 0)

        const pwEdit = await page.locator("#password-view").getByRole("button", { name: "Edit" }).boundingBox()
        await clickPasswordEdit(page)
        const pwNext = await page.locator("#password-current-form").getByRole("button", { name: "Next" }).boundingBox()
        expect(pwNext?.x, "Password: Next lands on the Edit button").toBeCloseTo(pwEdit?.x ?? -1, 0)
    })

    test("email is displayed read-only and not in a form input", async ({ authenticatedPage: page, testUser }) => {
        await page.goto("/settings")
        await expect(page.locator('input[name="email"]')).toHaveCount(0)
        await expect(page.locator("body")).toContainText(testUser.email)
    })

    // Advance step 1 → 2: the current password is verified server-side, so Next only advances once
    // /internal/settings/password/verify accepts it. Waits on the new-password step becoming visible.
    async function passwordStep1Next(page: Page, currentPassword: string): Promise<void> {
        await page.fill("#currentPassword", currentPassword)
        await page.locator("#password-current-form").getByRole("button", { name: "Next" }).click()
        await expect(page.locator("#password-new-form")).toBeVisible()
    }

    // A path-exact match for the final (mutating) POST, since /internal/settings/password/verify shares the prefix.
    function isPasswordCommit(url: string): boolean {
        return new URL(url).pathname === "/internal/settings/password"
    }

    // The password change starts by asking for the CURRENT password (a hijacked session cannot silently
    // reset it), then walks new → re-enter, each step in the same slot so the row never reflows.
    test("password change asks for current password first, then new and confirm, in one slot", async ({ authenticatedPage: page, testUser }) => {
        await page.goto("/settings")
        await clickPasswordEdit(page)

        // Step 1: the current password, before anything else is offered.
        await expect(page.locator("#password-current-form")).toBeVisible()
        await expect(page.locator("#password-new-form")).toBeHidden()
        await expect(page.locator("#password-confirm-form")).toBeHidden()
        await passwordStep1Next(page, testUser.password)

        // Step 2: the new password takes the same slot; the current-password input is gone.
        await expect(page.locator("#password-current-form")).toBeHidden()
        await page.fill("#newPassword", "brand_new_secret")
        await page.locator("#password-new-form").getByRole("button", { name: "Next" }).click()

        // Step 3: re-enter, again in the same slot.
        await expect(page.locator("#password-new-form")).toBeHidden()
        await expect(page.locator("#password-confirm-form")).toBeVisible()

        // Cancel returns to the read view without saving.
        await page.locator("#password-confirm-form").getByRole("button", { name: "Cancel" }).click()
        await expect(page.locator("#password-view")).toBeVisible()
        await expect(page.locator("#password-confirm-form")).toBeHidden()
    })

    test("a wrong current password is rejected at step 1 without advancing", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await clickPasswordEdit(page)

        // The Next click posts to /internal/settings/password/verify, which rejects the wrong current password.
        await page.fill("#currentPassword", "definitely-not-the-password")
        await Promise.all([
            page.waitForResponse(r => r.url().includes("/internal/settings/password/verify")
                && r.request().method() === "POST" && r.status() === 422),
            page.locator("#password-current-form").getByRole("button", { name: "Next" }).click(),
        ])

        // The user never leaves step 1 — the new-password step is never offered, and nothing was saved.
        await expect(page.locator("#password-current-form")).toBeVisible()
        await expect(page.locator("#password-new-form")).toBeHidden()
        await expect(page.locator("#password-confirm-form")).toBeHidden()
    })

    test("re-entering the current password at step 2 is blocked, with a requirement row saying so", async ({ authenticatedPage: page, testUser }) => {
        // A "change" to the password already in use is not a change: the server rejects it, so the client
        // never lets the user reach the confirm step for it — the requirement is stated up front in the
        // popover and gates the Next button, exactly like the length rules.
        await page.goto("/settings")
        await clickPasswordEdit(page)
        await passwordStep1Next(page, testUser.password)

        const rule = page.locator('[data-pw-tooltip][data-pw-for="newPassword"] [data-pw-type="differsFrom"]')
        await expect(rule).toContainText("Different from your current password")

        const next = page.locator("#password-new-form").getByRole("button", { name: "Next" })
        await page.fill("#newPassword", testUser.password)
        await expect(rule).toHaveClass(/text-danger/)
        await expect(next).toBeDisabled()

        // Any other value satisfies it, and the step advances again.
        await page.fill("#newPassword", "something_genuinely_new")
        await expect(rule).toHaveClass(/text-success/)
        await expect(next).toBeEnabled()

        await page.locator("#password-new-form").getByRole("button", { name: "Cancel" }).click()
        await expect(page.locator("#password-view")).toBeVisible()
    })

    test("changing the password with the correct current password persists, then restores it", async ({ authenticatedPage: page, testUser }) => {
        const newPassword = "e2e_rotated_secret_1"

        // Rotate the password using the real current one.
        await page.goto("/settings")
        await clickPasswordEdit(page)
        await passwordStep1Next(page, testUser.password)
        await page.fill("#newPassword", newPassword)
        await page.locator("#password-new-form").getByRole("button", { name: "Next" }).click()
        await page.fill("#confirmPassword", newPassword)
        await Promise.all([
            page.waitForResponse(r => isPasswordCommit(r.url())
                && r.request().method() === "POST" && r.status() === 200),
            page.locator("#password-confirm-form").getByRole("button", { name: "Save" }).click(),
        ])
        await expect(page.locator("#password-view")).toBeVisible()

        // Restore the original password so the shared fixture user still logs in for later tests. The
        // step-1 verify here also proves the just-rotated password is now the accepted "current" one.
        await clickPasswordEdit(page)
        await passwordStep1Next(page, newPassword)
        await page.fill("#newPassword", testUser.password)
        await page.locator("#password-new-form").getByRole("button", { name: "Next" }).click()
        await page.fill("#confirmPassword", testUser.password)
        await Promise.all([
            page.waitForResponse(r => isPasswordCommit(r.url())
                && r.request().method() === "POST" && r.status() === 200),
            page.locator("#password-confirm-form").getByRole("button", { name: "Save" }).click(),
        ])
        await expect(page.locator("#password-view")).toBeVisible()
    })

    test("calendar style picker offers exactly Full, Minimal, and Stacked tiles", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        const values = await page.locator('input[name="calendarView"]').evaluateAll(
            els => els.map(e => (e as HTMLInputElement).value))
        expect(values).toEqual(["full", "minimal", "stacked"])
        const labels = await page.locator('[role="radiogroup"][aria-label="Calendar style"] .preview-label').allInnerTexts()
        expect(labels).toEqual(["Full", "Minimal", "Stacked"])
    })

    test("select minimal calendar style persists across reload", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await selectTile(page, "calendarView", "minimal")

        await page.reload()
        await expect(page.locator('input[name="calendarView"][value="minimal"]')).toBeChecked()
    })

    test("select full calendar style persists across reload", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await selectTile(page, "calendarView", "minimal")

        await page.goto("/settings")
        await selectTile(page, "calendarView", "full")

        await page.reload()
        await expect(page.locator('input[name="calendarView"][value="full"]')).toBeChecked()
    })

    // The week-start preference moves BOTH halves of the calendar: the column header (rendered server-side by
    // DayLabels.weekdayAbbreviations) and the grid's own leading-cell offset (dashboard.js, off data-week-start).
    // Asserting the first cell's actual date is what catches the two drifting apart — a header alone would still
    // read correctly while every cell sat one column out.
    test("week start: picking a day re-anchors the calendar, and Automatic follows the language", async ({ authenticatedPage: page }) => {
        const firstColumn = page.locator(".d-min-dow-header span").first()
        const firstCellDay = async (): Promise<number> => {
            const date = await page.locator(".d-min-cell").first().getAttribute("data-date")
            return new Date(`${date}T00:00:00Z`).getUTCDay()
        }

        // The suite's account is pinned to en-GB, whose CLDR convention is Monday — so the untouched
        // preference is Automatic, and the calendar starts on Monday without anything being chosen.
        await page.goto("/settings")
        await expect(page.locator("#weekStart")).toHaveValue("")
        await expect(page.locator("#weekStart-button")).toHaveText("Automatic (Monday)")
        await expect(page.locator('#weekStart-list .combo-option[data-value=""]')).toHaveText("Automatic (Monday)")
        await page.goto("/")
        await expect(firstColumn).toHaveText("Mon")
        expect(await firstCellDay()).toBe(1)

        await page.goto("/settings")
        await pickComboOption(page, "weekStart", "sunday")
        await page.goto("/")
        await expect(firstColumn).toHaveText("Sun")
        expect(await firstCellDay()).toBe(0)

        // Back to Automatic (the blank option), so the shared account leaves this test as it found it.
        await page.goto("/settings")
        await pickComboOption(page, "weekStart", "")
        await page.goto("/")
        await expect(firstColumn).toHaveText("Mon")
        expect(await firstCellDay()).toBe(1)
    })

    // Every dropdown on the page is the same listbox, so the "five rows then scroll" bound is asserted on
    // the longest one (15 timezones) and its absence on a list that fits (the 5 languages). The scrollbar
    // itself is checked through computed style rather than a screenshot ON PURPOSE: headless Chromium
    // paints no scrollbar at all — verified against a deliberately red one — so a screenshot proves
    // nothing here. It is asserted to equal the PAGE's own scrollbar rather than any literal colour:
    // the panel must use the app's single scrollbar definition (app.css's `html` block), whatever that
    // is and whichever theme is active, not a second style of scrollbar.
    test("dropdowns: a list longer than five options scrolls, with the panel's own scrollbar", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")

        await page.click("#timezone-button")
        const timezone = await page.locator("#timezone-list").evaluate((el: HTMLElement) => ({
            rows: el.querySelectorAll(".combo-option").length,
            clientHeight: el.clientHeight,
            scrollHeight: el.scrollHeight,
            gutter: el.offsetWidth - el.clientWidth,
            scrollbarColor: getComputedStyle(el).scrollbarColor,
            scrollbarWidth: getComputedStyle(el).scrollbarWidth,
            pageScrollbarColor: getComputedStyle(document.documentElement).scrollbarColor,
            pageScrollbarWidth: getComputedStyle(document.documentElement).scrollbarWidth,
        }))
        expect(timezone.rows).toBeGreaterThan(5)
        // Five rows of --combo-row-h (2.25rem = 36px), and more list below them.
        expect(timezone.clientHeight).toBe(180)
        expect(timezone.scrollHeight).toBeGreaterThan(timezone.clientHeight)
        expect(timezone.scrollbarColor).toBe(timezone.pageScrollbarColor)
        expect(timezone.scrollbarWidth).toBe(timezone.pageScrollbarWidth)
        // A desktop scrollbar takes space, and `scrollbar-gutter: stable` reserves it up front so the panel
        // (sized to its widest option) does not have it eaten later. A TOUCH device instead overlays the
        // scrollbar - no gutter, and no custom colours honoured - which is the platform norm and not
        // something the page should fight, so the reservation is asserted only where one exists.
        if (test.info().project.name !== "mobile-chrome") {
            expect(timezone.gutter).toBeGreaterThan(0)
        }
        await page.keyboard.press("Escape")

        // Exactly five languages today, so that list shows whole and never scrolls.
        await page.click("#language-button")
        const language = await page.locator("#language-list").evaluate((el: HTMLElement) => ({
            rows: el.querySelectorAll(".combo-option").length,
            clientHeight: el.clientHeight,
            scrollHeight: el.scrollHeight,
        }))
        expect(language.rows).toBe(5)
        expect(language.scrollHeight).toBe(language.clientHeight)
        await page.keyboard.press("Escape")
    })

    // The language picker is the one Settings dropdown with a filter box (every dropdown on the page is the same
    // hand-rolled listbox; only this one passes `search`), so its filtering, its keyboard commit and — most of
    // all — the fact that it still saves through the same PATCH the old <select> did are only covered here.
    // Restores en-GB at the end: the spec shares one account, and every other test here asserts English wording.
    test("language: the picker filters on either name and saves the pick", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        const shown = page.locator("#language-list .combo-option:visible")

        await page.click("#language-button")
        await expect(page.locator("#language-panel")).toBeVisible()
        await expect(shown).toHaveCount(5)

        // Alphabetical by the ENGLISH name (Language.pickerOrder), not the enum's declaration order — which is
        // why Arabic leads a list whose default is English.
        // Plain strings, not regexes: toHaveText only collapses an element's whitespace for a STRING expectation, and
        // each option's markup wraps its label onto its own line.
        await expect(shown).toHaveText([
            "العربية (Arabic)",
            "English (UK)",
            "English (US)",
            "日本語 (Japanese)",
            "Español (Spanish)",
        ])

        // The ENGLISH name is a filter term, not just a label — "Spanish" appears nowhere else on the option.
        await page.fill("#language-search", "spanish")
        await expect(shown).toHaveCount(1)
        await expect(shown.first()).toContainText("Español")

        // ... and so is the autonym, with or without its accents (a keyboard without the ñ still gets there).
        await page.fill("#language-search", "espanol")
        await expect(shown).toHaveCount(1)
        await expect(shown.first()).toContainText("Español")

        // A non-Latin script matches as typed.
        await page.fill("#language-search", "日本")
        await expect(shown).toHaveCount(1)
        await expect(shown.first()).toContainText("日本語")

        await page.fill("#language-search", "zzz")
        await expect(shown).toHaveCount(0)
        await expect(page.locator("#language-no-matches")).toBeVisible()

        // Enter commits the first (here, only) match: the value is saved and settings.js reloads the page into
        // the new language, which is what makes `lang` the assertion that the PATCH really happened.
        await page.fill("#language-search", "spanish")
        await page.keyboard.press("Enter")
        await expect(page.locator("html")).toHaveAttribute("lang", "es-ES")
        await expect(page.locator("#language-button")).toContainText("Español")

        // Back to English (UK) by clicking the option, so the shared account leaves this test as it found it.
        await page.click("#language-button")
        await page.locator("#language-option-en-GB").click()
        await expect(page.locator("html")).toHaveAttribute("lang", "en-GB")
        await expect(page.locator("#language-button")).toContainText("English (UK)")
    })

    test("clicking a preview tile info button opens the full-size dashboard preview", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await expect(page.locator("#preview-modal")).toBeHidden()

        // The (!) affordance sits outside the radio label, so it opens the modal without changing the value.
        await page.locator('[role="radiogroup"][aria-label="Theme"] .preview-info').first().click()
        await expect(page.locator("#preview-modal")).toBeVisible()
        // The modal builds one <img> per gallery tile inside #preview-modal-imgs (cycled by the arrows
        // rather than re-fetched). The System tile is first, so its image leads. Previews are WebP named
        // page-{nova,standard}-{full,minimal,stacked}-{theme}, per font + calendar style + viewport
        // (`-mobile` variant), so allow any font/style and the optional `-mobile` suffix.
        //
        // The /img/settings/full/ prefix is the point of the assertion, not incidental: the lightbox must
        // take the tile's `data-full` image, NOT the small one the tile itself paints. Both files share a
        // base name, so without pinning the directory this passes even if the lightbox regresses to the
        // thumbnail and shows a ~185px-wide image blown up to 1024.
        await expect(page.locator("#preview-modal-imgs img").first())
            .toHaveAttribute("src", /\/img\/settings\/full\/page-(nova|standard)-(full|minimal|stacked)-system(-mobile)?\.webp/)

        // Escape closes it.
        await page.keyboard.press("Escape")
        await expect(page.locator("#preview-modal")).toBeHidden()
    })

    test("re-opening a picker's preview re-uses its images instead of re-requesting them", async ({ authenticatedPage: page }) => {
        // Theme -> close -> Font -> close -> Theme must issue NOTHING on that last open. The lightbox
        // images are content-hashed and served `immutable`, so an HTTP-cache hit would usually spare the
        // network anyway - this pins the stronger, structural guarantee (settings.js previewImgCache keeps
        // the <img> elements), which is what holds when the cache has evicted them or is disabled. The
        // request counter is the assertion because it is the only thing that distinguishes "re-used" from
        // "re-fetched from cache"; note the previews 404 in this tier (they exist only in a Docker build),
        // which if anything makes a re-request MORE likely, since a 404 is not cacheable.
        const requested: string[] = []
        page.on("request", (r) => { if (r.url().includes("/img/settings/full/")) {requested.push(r.url())} })

        await page.goto("/settings")
        const openPicker = async (label: string): Promise<void> => {
            await page.locator(`[role="radiogroup"][aria-label="${label}"] .preview-info`).first().click()
            await expect(page.locator("#preview-modal")).toBeVisible()
        }
        const close = async (): Promise<void> => {
            await page.keyboard.press("Escape")
            await expect(page.locator("#preview-modal")).toBeHidden()
        }

        await openPicker("Theme")
        expect(requested.length, "opening a picker should request its full-size previews").toBeGreaterThan(0)
        await close()

        await openPicker("Font")
        await close()
        const afterFont = requested.length

        await openPicker("Theme")
        // Settle briefly so a late request would still be counted before the assertion.
        await page.waitForTimeout(500)
        expect(requested.length, "re-opening the Theme picker must not request any image again").toBe(afterFont)
        await close()
    })

    test("log out everywhere arms an in-place confirm, not a native dialog", async ({ authenticatedPage: page }) => {
        // Fail if a native confirm()/alert() ever appears — the whole point is to avoid it.
        let dialogFired = false
        page.on("dialog", (d) => { dialogFired = true; void d.dismiss() })

        await page.goto("/settings")
        const view = page.locator("#logout-all-view")
        const confirm = page.locator("#logout-all-confirm")
        await expect(view).toBeVisible()
        await expect(confirm).toBeHidden()

        // Arming reveals the confirm state (destructive action + Cancel); no dialog is shown.
        await view.getByRole("button", { name: "Log out everywhere" }).click()
        await expect(confirm).toBeVisible()
        await expect(confirm.getByRole("button", { name: "Cancel" })).toBeVisible()
        await expect(view).toBeHidden()
        expect(dialogFired).toBe(false)

        // Cancel restores the resting view without logging out.
        await confirm.getByRole("button", { name: "Cancel" }).click()
        await expect(confirm).toBeHidden()
        await expect(view).toBeVisible()
    })

    test("confirming log out everywhere ends the session and returns to login", async ({ authenticatedPage: page }) => {
        await page.goto("/settings")
        await page.locator("#logout-all-view").getByRole("button", { name: "Log out everywhere" }).click()
        await Promise.all([
            page.waitForURL(/\/login/),
            page.locator("#logout-all-confirm").getByRole("button", { name: "Log out everywhere" }).click(),
        ])

        // The session is revoked, so a protected page bounces straight back to /login.
        await page.goto("/")
        await expect(page).toHaveURL(/\/login/)
    })
})

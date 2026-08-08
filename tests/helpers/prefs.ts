import type { Page } from "@playwright/test"

// Every preference (and the display name) auto-saves via an HTMX PATCH to the single consolidated
// /internal/settings endpoint, each control submitting just its own field on `change` (the
// per-section page sizes submit their whole panel). That PATCH is asynchronous, so a test MUST wait
// for it to finish before reloading/navigating — otherwise the reload races the save and reads the
// stale value (the root cause of the previous flakiness).
export async function waitForSave(page: Page, action: Promise<unknown>): Promise<void> {
    await Promise.all([
        page.waitForResponse(r => new URL(r.url()).pathname === "/internal/settings" && r.request().method() === "PATCH"),
        action,
    ])
}

// Set a numeric preference (page size / decimal places) to `value` via its preset pill,
// tolerating the case where it is ALREADY that value. Clicking a preset for the current value is
// a deliberate no-op that fires no PATCH (settings.js `commit`), so `waitForSave` would hang —
// only wait for a save when the value actually changes.
export async function establishNumericPref(
    page: Page, presetsId: string, fieldId: string, value: string,
): Promise<void> {
    const alreadySet = (await page.locator(`#${fieldId}`).inputValue()) === value
    const click = page.locator(`#${presetsId} .num-pref-pill[data-value="${value}"]`).click()
    if (alreadySet) {
        await click
    } else {
        await waitForSave(page, click)
    }
}

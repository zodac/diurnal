import { test, expect } from '../helpers/fixtures';

// Unique-name helper: chromium and mobile-chrome share the same user + DB, so any name
// archived by chromium's beforeEach cleanup can't be recreated (DB unique constraint on
// (user_id, name) ignores the archived flag).  Using a run-scoped counter + timestamp
// guarantees every test in this run gets a brand-new name that was never archived before.
let _seq = 0;
const _RUN = Date.now();
function unique(base: string): string {
  return `${base}_${_RUN}_${++_seq}`;
}

test.describe('Actions page', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    // Archive all active actions before each test so each test starts with a clean list.
    for (let pass = 0; pass < 10; pass++) {
      await page.goto('/actions');
      const items = await page.locator('#action-list [id^="action-"]').all();
      if (items.length === 0) break;
      for (const item of items) {
        const id = (await item.getAttribute('id'))?.replace('action-', '');
        if (id) {
          await page.evaluate(async (actionId: string) => {
            await fetch(`/actions/${actionId}/delete`, { method: 'POST' });
          }, id);
        }
      }
    }
  });

  test('create action appears in list', async ({ authenticatedPage: page }) => {
    const name = unique('Running');
    await page.goto('/actions');
    await page.waitForFunction(() => typeof (window as any).htmx !== 'undefined');
    await page.fill('input[name="name"]', name);
    await Promise.all([
      page.waitForResponse(r => r.url().endsWith('/actions') && r.request().method() === 'POST'),
      page.locator('form[hx-post="/actions"] button[type="submit"]').click(),
    ]);
    await expect(page.locator('#action-list')).toContainText('Running');
  });

  test('create action with duplicate name shows error', async ({ authenticatedPage: page }) => {
    const name = unique('Cycling');
    await page.goto('/actions');
    await page.waitForFunction(() => typeof (window as any).htmx !== 'undefined');
    await page.fill('input[name="name"]', name);
    await Promise.all([
      page.waitForResponse(r => r.url().endsWith('/actions') && r.request().method() === 'POST'),
      page.locator('form[hx-post="/actions"] button[type="submit"]').click(),
    ]);
    await expect(page.locator('#action-list')).toContainText('Cycling');

    // Submit again with the same name
    await page.fill('input[name="name"]', name);
    await Promise.all([
      page.waitForResponse(r => r.url().endsWith('/actions') && r.request().method() === 'POST'),
      page.locator('form[hx-post="/actions"] button[type="submit"]').click(),
    ]);
    await expect(page.locator('#action-error')).toBeVisible();
    await expect(page.locator('#action-error')).toContainText(/already exists/i);
  });

  test('edit action: inline form appears and updates name in-place', async ({ authenticatedPage: page }) => {
    const origName = unique('OldName');
    const newName = unique('NewName');
    await page.goto('/actions');
    await page.waitForFunction(() => typeof (window as any).htmx !== 'undefined');
    await page.fill('input[name="name"]', origName);
    await Promise.all([
      page.waitForResponse(r => r.url().endsWith('/actions') && r.request().method() === 'POST'),
      page.locator('form[hx-post="/actions"] button[type="submit"]').click(),
    ]);
    await expect(page.locator('#action-list')).toContainText('OldName');

    // Hover over the item to reveal the Edit button, then click it
    const item = page.locator('#action-list [id^="action-"]').filter({ hasText: origName });
    const itemId = await item.getAttribute('id');
    await item.hover();
    await item.getByRole('button', { name: 'Edit' }).click();
    // HTMX swaps the div outerHTML with the edit form (same ID, no longer has visible "OldName" text)
    const editForm = page.locator(`#${itemId}`);
    await expect(editForm.locator('input[name="name"]')).toBeVisible();

    await editForm.locator('input[name="name"]').fill(newName);
    await editForm.locator('button[type="submit"]').click();

    await expect(page.locator('#action-list')).toContainText('NewName');
    await expect(page.locator('#action-list')).not.toContainText('OldName');
  });

  test('delete action: confirm panel appears then removes action', async ({ authenticatedPage: page }) => {
    const name = unique('ToDelete');
    await page.goto('/actions');
    await page.waitForFunction(() => typeof (window as any).htmx !== 'undefined');
    await page.fill('input[name="name"]', name);
    await Promise.all([
      page.waitForResponse(r => r.url().endsWith('/actions') && r.request().method() === 'POST'),
      page.locator('form[hx-post="/actions"] button[type="submit"]').click(),
    ]);
    await expect(page.locator('#action-list')).toContainText('ToDelete');

    const item = page.locator('#action-list [id^="action-"]').filter({ hasText: name });
    await item.hover();
    await item.getByRole('button', { name: 'Delete' }).click();

    // Confirm delete button appears inside the same element
    await expect(item).toContainText(/delete|confirm/i);
    await item.locator('button').filter({ hasText: /delete|yes|confirm/i }).click();

    await expect(page.locator('#action-list')).not.toContainText('ToDelete');
  });

  test('pagination: Next and Previous navigate between pages', async ({ authenticatedPage: page }) => {
    // Create 11 actions to exceed the default page size of 10
    const prefix = unique('PagAction');
    await page.goto('/actions');
    for (let i = 1; i <= 11; i++) {
      await page.evaluate(async ({ name, colour }: { name: string; colour: string }) => {
        const params = new URLSearchParams({ name, colour });
        await fetch('/actions', {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: params.toString(),
        });
      }, { name: `${prefix}_${i.toString().padStart(2, '0')}`, colour: '#6366f1' });
    }

    await page.goto('/actions');
    await expect(page.locator('#action-list')).toContainText('Next');

    await page.locator('#action-list').getByText('Next').click();
    await expect(page.locator('#action-list')).toContainText('Previous');
  });

  test('search filters action list case-insensitively', async ({ authenticatedPage: page }) => {
    const morningRun = unique('Morning Run');
    const eveningWalk = unique('Evening Walk');
    await page.goto('/actions');
    await page.evaluate(async ({ morning, evening }: { morning: string; evening: string }) => {
      for (const name of [morning, evening]) {
        const params = new URLSearchParams({ name, colour: '#6366f1' });
        await fetch('/actions', {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: params.toString(),
        });
      }
    }, { morning: morningRun, evening: eveningWalk });
    await page.reload();

    await page.fill('input[placeholder*="Search"], input[name="q"]', 'MORNING');
    // HTMX fires on input — wait for the list to update
    await expect(page.locator('#action-list')).toContainText('Morning Run');
    await expect(page.locator('#action-list')).not.toContainText('Evening Walk');

    // Clear search restores full list
    await page.fill('input[placeholder*="Search"], input[name="q"]', '');
    await expect(page.locator('#action-list')).toContainText('Evening Walk');
  });
});

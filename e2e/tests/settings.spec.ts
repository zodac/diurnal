import { test, expect } from '../helpers/fixtures';

// Preferences auto-save via an htmx POST to /settings fired on the control's `change` event. That
// POST is asynchronous, so a test MUST wait for it to finish before reloading/navigating —
// otherwise the reload races the save and reads the stale value (the root cause of the previous
// flakiness).
async function waitForSave(page, action: Promise<unknown>) {
  await Promise.all([
    page.waitForResponse(r => r.url().endsWith('/settings') && r.request().method() === 'POST'),
    action,
  ]);
}

// Theme and calendar style are chosen from preview tiles backed by hidden radio inputs. Tests in
// a spec share one user, so a value may already be selected; we check the radio and always dispatch
// `change` so the htmx save fires regardless (mirroring how Playwright's selectOption behaved on the
// old <select>). page size is still a real <select>, selected via selectOption.
async function selectTile(page, name: string, value: string) {
  await waitForSave(page, page.locator(`input[name="${name}"][value="${value}"]`).evaluate(
    (el: HTMLInputElement) => { el.checked = true; el.dispatchEvent(new Event('change', { bubbles: true })); }));
}

async function selectOption(page, selector: string, value: string) {
  await waitForSave(page, page.selectOption(selector, value));
}

test.describe('Settings page', () => {
  test('select dark theme persists across reload', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    await selectTile(page, 'theme', 'dark');

    await page.reload();
    await expect(page.locator('html')).toHaveClass(/dark/);
    await expect(page.locator('input[name="theme"][value="dark"]')).toBeChecked();
  });

  test('select light theme persists across reload and removes dark class', async ({ authenticatedPage: page }) => {
    // Set to dark first
    await page.goto('/settings');
    await selectTile(page, 'theme', 'dark');
    await page.reload();

    // Now switch to light
    await page.goto('/settings');
    await selectTile(page, 'theme', 'light');

    await page.reload();
    await expect(page.locator('html')).not.toHaveClass(/dark/);
    await expect(page.locator('input[name="theme"][value="light"]')).toBeChecked();
  });

  test('select system theme persists across reload', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    await selectTile(page, 'theme', 'dark');
    await page.reload();

    await page.goto('/settings');
    await selectTile(page, 'theme', 'system');

    await page.reload();
    await expect(page.locator('input[name="theme"][value="system"]')).toBeChecked();
  });

  test('theme picker offers exactly System, Light, and Dark tiles', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    const values = await page.locator('input[name="theme"]').evaluateAll(
      els => els.map(e => (e as HTMLInputElement).value));
    expect(values).toEqual(['system', 'light', 'dark']);
    const labels = await page.locator('[role="radiogroup"][aria-label="Theme"] .preview-label').allInnerTexts();
    expect(labels).toEqual(['System', 'Light', 'Dark']);
  });

  test('change page size to 25 persists and affects action list', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    await selectOption(page, 'select[name="pageSize"]', '25');

    await page.goto('/settings');
    await expect(page.locator('select[name="pageSize"]')).toHaveValue('25');
  });

  test('page size select only offers valid options', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    const options = await page.locator('select[name="pageSize"] option').allTextContents();
    expect(options.map(Number)).toEqual(expect.arrayContaining([5, 10, 25, 50, 100]));
    expect(options).toHaveLength(5);
  });

  test('settings page shows account display name and email', async ({ authenticatedPage: page, testUser }) => {
    await page.goto('/settings');
    // Display name may differ from testUser.displayName if a prior test run changed it without
    // restoring — verify it is present and non-empty; the email is immutable and checked exactly.
    await expect(page.locator('#display-name-text')).not.toBeEmpty();
    await expect(page.locator('body')).toContainText(testUser.email);
  });

  test('display name is read-only by default with an Edit button', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    await expect(page.locator('#account-form')).toBeHidden();
    await expect(page.getByRole('button', { name: 'Edit' })).toBeVisible();
  });

  test('clicking Edit shows the input with Save and Cancel buttons', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    await page.getByRole('button', { name: 'Edit' }).click();
    await expect(page.locator('#account-form')).toBeVisible();
    await expect(page.locator('#display-name-view')).toBeHidden();
    await expect(page.getByRole('button', { name: 'Save' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Cancel' })).toBeVisible();
  });

  test('Cancel restores read mode without saving', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    // Capture the current name before editing — it may differ from testUser.displayName if a
    // previous test run changed it. Cancel must restore exactly what was there before.
    const nameBefore = await page.locator('#display-name-text').textContent() ?? '';
    await page.getByRole('button', { name: 'Edit' }).click();
    await page.fill('input[name="displayName"]', 'Should Not Save');
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.locator('#account-form')).toBeHidden();
    await expect(page.locator('#display-name-text')).toHaveText(nameBefore);
  });

  test('update display name persists across reload', async ({ authenticatedPage: page, testUser }) => {
    await page.goto('/settings');
    await page.getByRole('button', { name: 'Edit' }).click();
    await page.fill('input[name="displayName"]', 'Updated Name');
    await Promise.all([
      page.waitForResponse(r => r.url().includes('/settings/display-name') && r.request().method() === 'POST'),
      page.getByRole('button', { name: 'Save' }).click(),
    ]);
    await expect(page.locator('#display-name-text')).toHaveText('Updated Name');

    await page.reload();
    await expect(page.locator('#display-name-text')).toHaveText('Updated Name');

    // Restore the original display name so subsequent viewports (e.g. mobile-chrome)
    // see the expected testUser.displayName rather than this test's intermediate value.
    await page.getByRole('button', { name: 'Edit' }).click();
    await page.fill('input[name="displayName"]', testUser.displayName);
    await Promise.all([
      page.waitForResponse(r => r.url().includes('/settings/display-name') && r.request().method() === 'POST'),
      page.getByRole('button', { name: 'Save' }).click(),
    ]);
    await expect(page.locator('#display-name-text')).toHaveText(testUser.displayName);
  });

  test('email is displayed read-only and not in a form input', async ({ authenticatedPage: page, testUser }) => {
    await page.goto('/settings');
    await expect(page.locator('input[name="email"]')).toHaveCount(0);
    await expect(page.locator('body')).toContainText(testUser.email);
  });

  test('calendar style picker offers exactly Full, Minimal, and Stacked tiles', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    const values = await page.locator('input[name="calendarView"]').evaluateAll(
      els => els.map(e => (e as HTMLInputElement).value));
    expect(values).toEqual(['full', 'minimal', 'stacked']);
    const labels = await page.locator('[role="radiogroup"][aria-label="Calendar style"] .preview-label').allInnerTexts();
    expect(labels).toEqual(['Full', 'Minimal', 'Stacked']);
  });

  test('select minimal calendar style persists across reload', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    await selectTile(page, 'calendarView', 'minimal');

    await page.reload();
    await expect(page.locator('input[name="calendarView"][value="minimal"]')).toBeChecked();
  });

  test('select full calendar style persists across reload', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    await selectTile(page, 'calendarView', 'minimal');

    await page.goto('/settings');
    await selectTile(page, 'calendarView', 'full');

    await page.reload();
    await expect(page.locator('input[name="calendarView"][value="full"]')).toBeChecked();
  });

  test('clicking a preview tile info button opens the full-size dashboard preview', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    await expect(page.locator('#preview-modal')).toBeHidden();

    // The (!) affordance sits outside the radio label, so it opens the modal without changing the value.
    await page.locator('[role="radiogroup"][aria-label="Theme"] .preview-info').first().click();
    await expect(page.locator('#preview-modal')).toBeVisible();
    await expect(page.locator('#preview-modal-img')).toHaveAttribute('src', /theme-system\.png/);

    // Escape closes it.
    await page.keyboard.press('Escape');
    await expect(page.locator('#preview-modal')).toBeHidden();
  });
});

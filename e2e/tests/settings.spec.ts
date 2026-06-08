import { test, expect } from '../helpers/fixtures';

test.describe('Settings page', () => {
  test('select dark theme persists across reload', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    await page.selectOption('select[name="theme"]', 'dark');

    await page.reload();
    await expect(page.locator('html')).toHaveClass(/dark/);
    await expect(page.locator('select[name="theme"]')).toHaveValue('dark');
  });

  test('select light theme persists across reload and removes dark class', async ({ authenticatedPage: page }) => {
    // Set to dark first
    await page.goto('/settings');
    await page.selectOption('select[name="theme"]', 'dark');
    await page.reload();

    // Now switch to light
    await page.goto('/settings');
    await page.selectOption('select[name="theme"]', 'light');

    await page.reload();
    await expect(page.locator('html')).not.toHaveClass(/dark/);
    await expect(page.locator('select[name="theme"]')).toHaveValue('light');
  });

  test('select system theme persists across reload', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    await page.selectOption('select[name="theme"]', 'dark');
    await page.reload();

    await page.goto('/settings');
    await page.selectOption('select[name="theme"]', 'system');

    await page.reload();
    await expect(page.locator('select[name="theme"]')).toHaveValue('system');
  });

  test('theme select offers exactly system, light, and dark options', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    const options = await page.locator('select[name="theme"] option').allInnerTexts();
    expect(options).toEqual(['System Theme', 'Light', 'Dark']);
  });

  test('change page size to 25 persists and affects action list', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    await page.selectOption('select[name="pageSize"]', '25');

    await page.goto('/settings');
    await expect(page.locator('select[name="pageSize"]')).toHaveValue('25');
  });

  test('page size select only offers valid options', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    const options = await page.locator('select[name="pageSize"] option').allTextContents();
    expect(options.map(Number)).toEqual(expect.arrayContaining([10, 25, 50, 100]));
    expect(options).toHaveLength(4);
  });

  test('settings page shows account display name and email', async ({ authenticatedPage: page, testUser }) => {
    await page.goto('/settings');
    await expect(page.locator('#display-name-text')).toHaveText(testUser.displayName);
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

  test('Cancel restores read mode without saving', async ({ authenticatedPage: page, testUser }) => {
    await page.goto('/settings');
    await page.getByRole('button', { name: 'Edit' }).click();
    await page.fill('input[name="displayName"]', 'Should Not Save');
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.locator('#account-form')).toBeHidden();
    await expect(page.locator('#display-name-text')).toHaveText(testUser.displayName);
  });

  test('update display name persists across reload', async ({ authenticatedPage: page }) => {
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
  });

  test('email is displayed read-only and not in a form input', async ({ authenticatedPage: page, testUser }) => {
    await page.goto('/settings');
    await expect(page.locator('input[name="email"]')).toHaveCount(0);
    await expect(page.locator('body')).toContainText(testUser.email);
  });

  test('calendar style select offers exactly Full and Minimal options', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    const options = await page.locator('select[name="calendarView"] option').allInnerTexts();
    expect(options).toEqual(['Full', 'Minimal']);
  });

  test('select minimal calendar style persists across reload', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    await Promise.all([
      page.waitForResponse(r => r.url().includes('/settings') && r.request().method() === 'POST'),
      page.selectOption('select[name="calendarView"]', 'minimal'),
    ]);

    await page.reload();
    await expect(page.locator('select[name="calendarView"]')).toHaveValue('minimal');
  });

  test('select full calendar style persists across reload', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    await Promise.all([
      page.waitForResponse(r => r.url().includes('/settings') && r.request().method() === 'POST'),
      page.selectOption('select[name="calendarView"]', 'minimal'),
    ]);

    await page.goto('/settings');
    await Promise.all([
      page.waitForResponse(r => r.url().includes('/settings') && r.request().method() === 'POST'),
      page.selectOption('select[name="calendarView"]', 'full'),
    ]);

    await page.reload();
    await expect(page.locator('select[name="calendarView"]')).toHaveValue('full');
  });
});

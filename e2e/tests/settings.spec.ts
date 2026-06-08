import { test, expect } from '../helpers/fixtures';

test.describe('Settings page', () => {
  test('toggle dark mode on persists across reload', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    // Ensure dark mode is off first
    const checkbox = page.locator('input[name="darkMode"][value="true"]');
    if (await checkbox.isChecked()) {
      await checkbox.uncheck();
      await page.getByRole('button', { name: 'Save preferences' }).click();
    }

    await checkbox.check();
    await page.getByRole('button', { name: 'Save preferences' }).click();
    await expect(page.locator('body')).toContainText('Settings saved');

    await page.reload();
    await expect(page.locator('html')).toHaveClass(/dark/);
  });

  test('toggle dark mode off persists across reload', async ({ authenticatedPage: page }) => {
    // First turn it on
    await page.goto('/settings');
    const checkbox = page.locator('input[name="darkMode"][value="true"]');
    await checkbox.check();
    await page.getByRole('button', { name: 'Save preferences' }).click();

    // Now turn it off
    await page.goto('/settings');
    await page.locator('input[name="darkMode"][value="true"]').uncheck();
    await page.getByRole('button', { name: 'Save preferences' }).click();
    await expect(page.locator('body')).toContainText('Settings saved');

    await page.reload();
    await expect(page.locator('html')).not.toHaveClass(/dark/);
  });

  test('change page size to 25 persists and affects action list', async ({ authenticatedPage: page }) => {
    await page.goto('/settings');
    await page.selectOption('select[name="pageSize"]', '25');
    await page.getByRole('button', { name: 'Save preferences' }).click();
    await expect(page.locator('body')).toContainText('Settings saved');

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
    await expect(page.locator('body')).toContainText(testUser.email);
    await expect(page.locator('body')).toContainText(testUser.displayName);
  });
});

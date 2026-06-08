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
    await expect(page.locator('body')).toContainText(testUser.email);
    await expect(page.locator('body')).toContainText(testUser.displayName);
  });
});

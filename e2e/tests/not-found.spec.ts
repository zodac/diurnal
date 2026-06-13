import { test, expect } from '../helpers/fixtures';

test.describe('404 page', () => {
  test('unknown path unauthenticated shows 404 page', async ({ page }) => {
    const response = await page.goto('/this-path-does-not-exist');

    expect(response?.status()).toBe(404);
    await expect(page.locator('h1')).toContainText('Page Not Found');
  });

  test('unknown path authenticated shows 404 page', async ({ authenticatedPage: page }) => {
    const response = await page.goto('/this-path-does-not-exist');

    expect(response?.status()).toBe(404);
    await expect(page.locator('h1')).toContainText('Page Not Found');
  });
});

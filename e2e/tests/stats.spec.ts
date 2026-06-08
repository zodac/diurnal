import { test, expect } from '../helpers/fixtures';

test.describe('Stats page', () => {
  test('no logged actions shows empty state', async ({ page }) => {
    const { setupTestUser } = await import('../helpers/fixtures');
    await setupTestUser(page, {
      email: `e2e-stats-empty-${Date.now()}@example.com`,
      password: 'testpassword123',
      displayName: 'Stats Empty',
    });
    await page.goto('/stats');
    await expect(page.locator('body')).toContainText(/no actions|no logs/i);
  });

  test('logged actions show stats cards with streak and total', async ({ authenticatedPage: page }) => {
    const apiCtx = page.context().request;
    // Create an action and log it today
    await apiCtx.post('/actions', { form: { name: 'StatsAction', colour: '#6366f1' } });
    // Get the action ID from the actions list to build the log URL
    await page.goto('/actions');
    const actionIdMatch = await page.locator('#action-list [id^="action-"]').first().getAttribute('id');
    const actionId = actionIdMatch?.replace('action-', '');

    if (actionId) {
      const today = new Date().toISOString().slice(0, 10);
      await apiCtx.post(`/logs/${today}/${actionId}/increment`);
    }

    await page.goto('/stats');
    await expect(page.locator('body')).toContainText('StatsAction');
    await expect(page.locator('body')).toContainText(/streak|total/i);
  });

  test('stats pagination: next and previous navigate pages', async ({ authenticatedPage: page }) => {
    const apiCtx = page.context().request;
    const today = new Date().toISOString().slice(0, 10);

    // Create and log 11 actions to exceed one page
    for (let i = 1; i <= 11; i++) {
      const createResp = await apiCtx.post('/actions', {
        form: { name: `StatsPagAction${i.toString().padStart(2, '0')}`, colour: '#6366f1' },
      });
      // Extract action id from the returned HTML fragment
      const html = await createResp.text();
      const match = html.match(/id="action-([^"]+)"/);
      if (match) {
        await apiCtx.post(`/logs/${today}/${match[1]}/increment`);
      }
    }

    await page.goto('/stats');
    await expect(page.locator('body')).toContainText('Next');
    await page.locator('a:has-text("Next")').first().click();
    await expect(page.locator('body')).toContainText('Previous');
  });
});

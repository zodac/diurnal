import { test, expect } from '../helpers/fixtures';

// Unique action name for this test run — never archived, so no DB unique-constraint collision
// across repeated runs or across chromium/mobile-chrome sharing the same user+DB.
// Contains 'DashAction' so toContainText('DashAction') still matches.
const DASH_NAME = `DashAction${Date.now()}`;

// Helper: returns today's date as YYYY-MM-DD in local time
function todayStr(): string {
  return new Date().toISOString().slice(0, 10);
}

// Helper: returns a past date offset by -n days
function pastDateStr(daysAgo: number): string {
  const d = new Date();
  d.setDate(d.getDate() - daysAgo);
  return d.toISOString().slice(0, 10);
}

test.describe('Dashboard', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    // Create DASH_NAME if it doesn't exist yet (200 = created, 409 = already exists — both fine).
    // We never archive this action, so the unique constraint is never violated on re-runs.
    await page.goto('/actions');
    await page.evaluate(async (name: string) => {
      const params = new URLSearchParams({ name, colour: '#6366f1' });
      await fetch('/actions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: params.toString(),
      });
    }, DASH_NAME);

    // Navigate to dashboard and reset today's log count to 0 before each test.
    // Tests that increment/decrement need a clean starting state.
    await page.goto('/');
    await page.locator('#day-panel [id^="log-"]').first().waitFor({ timeout: 5000 }).catch(() => {});
    for (let i = 0; i < 10; i++) {
      const decBtn = page.locator('#day-panel').getByTitle('Decrease').first();
      if (await decBtn.isDisabled().catch(() => true)) break;
      await Promise.all([
        page.waitForResponse(r => r.url().includes('/logs/') && r.request().method() === 'POST'),
        decBtn.click(),
      ]);
    }
  });

  test('page load: today is pre-selected and day panel loads automatically', async ({ authenticatedPage: page }) => {
    await page.goto('/');
    // Today's cell should have the lt-day-selected class
    const todayCell = page.locator(`.fc-daygrid-day[data-date="${todayStr()}"]`);
    await expect(todayCell).toHaveClass(/lt-day-selected/);
    // Day panel should have loaded content (not the placeholder)
    await expect(page.locator('#day-panel')).not.toContainText('Click a day to log actions');
  });

  test('click a past date loads that day in the day panel', async ({ authenticatedPage: page }) => {
    await page.goto('/');
    const past = pastDateStr(3);
    const cell = page.locator(`.fc-daygrid-day[data-date="${past}"]`);
    await cell.click();
    await expect(page.locator('#day-panel')).toContainText('DashAction');
  });

  test('clicking a logged event dot loads the correct day panel', async ({ authenticatedPage: page }) => {
    // Log an action on today via the day panel first
    await page.goto('/');
    await page.locator('#day-panel').getByTitle('Increase').first().click();
    const countEl = page.locator('#day-panel [id^="log-"]').first().locator('.tabular-nums');
    await expect(countEl).toHaveText('1');

    // Navigate to yesterday — its count should be 0
    const past = pastDateStr(1);
    await page.locator(`.fc-daygrid-day[data-date="${past}"]`).click();
    await expect(page.locator('#day-panel [id^="log-"]').first().locator('.tabular-nums')).toHaveText('0');

    // Click the event dot on today's cell to navigate back
    const event = page.locator('.fc-event').first();
    await event.click();
    await expect(page.locator('#day-panel [id^="log-"]').first().locator('.tabular-nums')).toHaveText('1');
  });

  test('increment button increases count from 0 to 1', async ({ authenticatedPage: page }) => {
    await page.goto('/');
    const countEl = page.locator('#day-panel [id^="log-"]').first().locator('.tabular-nums');
    await expect(countEl).toHaveText('0');
    await page.locator('#day-panel').getByTitle('Increase').first().click();
    await expect(countEl).toHaveText('1');
  });

  test('increment twice reaches count 2', async ({ authenticatedPage: page }) => {
    await page.goto('/');
    const incrementBtn = page.locator('#day-panel').getByTitle('Increase').first();
    await incrementBtn.click();
    await incrementBtn.click();
    const countEl = page.locator('#day-panel [id^="log-"]').first().locator('.tabular-nums');
    await expect(countEl).toHaveText('2');
  });

  test('decrement from 1 reaches 0 and disables minus button', async ({ authenticatedPage: page }) => {
    await page.goto('/');
    await page.locator('#day-panel').getByTitle('Increase').first().click();
    await page.locator('#day-panel').getByTitle('Decrease').first().click();
    const countEl = page.locator('#day-panel [id^="log-"]').first().locator('.tabular-nums');
    await expect(countEl).toHaveText('0');
    await expect(page.locator('#day-panel').getByTitle('Decrease').first()).toBeDisabled();
  });

  test('decrement button is disabled when count is 0', async ({ authenticatedPage: page }) => {
    await page.goto('/');
    await expect(page.locator('#day-panel').getByTitle('Decrease').first()).toBeDisabled();
  });

  test('calendar dots refresh after logging an action', async ({ authenticatedPage: page }) => {
    await page.goto('/');
    const today = todayStr();
    // No event dots for today initially (before logging)
    const dotsBefore = await page.locator(`.fc-daygrid-day[data-date="${today}"] .fc-event`).count();
    // Log an action
    await page.locator('#day-panel').getByTitle('Increase').first().click();
    // FullCalendar refetches events after htmx:afterRequest — wait for the dot
    await expect(page.locator(`.fc-daygrid-day[data-date="${today}"] .fc-event`)).toHaveCount(Math.max(dotsBefore + 1, 1), { timeout: 5000 });
  });

  test('future date shows "future" message with no +/− buttons', async ({ authenticatedPage: page }) => {
    await page.goto('/');
    // Click on the next month's first day to get a future date
    await page.locator('.fc-next-button').click();
    const futureCell = page.locator('.fc-daygrid-day').filter({ has: page.locator('.fc-daygrid-day-number') }).first();
    await futureCell.click();
    await expect(page.locator('#day-panel')).toContainText(/future|cannot log/i);
    await expect(page.locator('#day-panel').getByTitle('Increase')).toHaveCount(0);
  });

  test('jump picker: calendar icon opens month grid, click closes it, Escape closes it', async ({ authenticatedPage: page }) => {
    await page.goto('/');
    const jumpBtn = page.locator('.lt-jump-btn');
    await jumpBtn.click();
    const popup = page.locator('.lt-months').first().locator('..');
    await expect(popup).not.toHaveClass(/hidden/);

    // Escape closes
    await page.keyboard.press('Escape');
    await expect(popup).toHaveClass(/hidden/);

    // Reopen and click outside to close
    await jumpBtn.click();
    await expect(popup).not.toHaveClass(/hidden/);
    await page.locator('h2').first().click(); // click outside
    await expect(popup).toHaveClass(/hidden/);
  });

  test('jump picker: year arrows change year label', async ({ authenticatedPage: page }) => {
    await page.goto('/');
    await page.locator('.lt-jump-btn').click();
    const yearLabel = page.locator('.lt-year');
    const originalYear = await yearLabel.textContent();

    await page.locator('button[data-y="1"]').click();
    await expect(yearLabel).not.toHaveText(originalYear!);
  });

  test('stats summary shows empty state when no logs', async ({ page, testUser }) => {
    // Use a fresh user with no logs
    const freshUser = { email: `e2e-dashboard-empty-${Date.now()}@example.com`, password: 'testpassword123', displayName: 'Empty' };
    const { setupTestUser } = await import('../helpers/fixtures');
    await setupTestUser(page, freshUser);
    await page.goto('/');
    await expect(page.locator('text=No actions logged yet')).toBeVisible();
  });
});

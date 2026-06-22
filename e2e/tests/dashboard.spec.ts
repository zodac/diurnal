import {Page} from "@playwright/test";
import {test, expect} from '../helpers/fixtures';

// Unique action name for this test run — never archived, so no DB unique-constraint collision
// across repeated runs or across chromium/mobile-chrome sharing the same user+DB.
// Contains 'DashAction' so toContainText('DashAction') still matches.
const DASH_NAME = `DashAction${Date.now()}`;

// Helper: today's date as YYYY-MM-DD in UTC — matches the server (app.timezone=UTC under -Dall).
function todayStr(): string {
    return new Date().toISOString().slice(0, 10);
}

// Helper: a past date offset by -n days, computed entirely in UTC. Using setUTCDate/getUTCDate
// (not the local setDate/getDate) keeps the arithmetic in the same zone as toISOString(), so a
// non-UTC host (e.g. NZST) near midnight can't shift the result by a day.
function pastDateStr(daysAgo: number): string {
    const d = new Date();
    d.setUTCDate(d.getUTCDate() - daysAgo);
    return d.toISOString().slice(0, 10);
}

// Calendar style is chosen from a preview tile backed by a hidden radio. Tests share one user, so
// the target value may already be selected — we set the radio and always dispatch `change` so the
// htmx auto-save POST fires regardless, then await it (the page must be on /settings).
async function setCalendarView(page: Page, value: string): Promise<void> {
    await Promise.all([
        page.waitForResponse(r => r.url().includes('/settings') && r.request().method() === 'POST'),
        page.locator(`input[name="calendarView"][value="${value}"]`).evaluate(
            (el: HTMLInputElement) => {
                el.checked = true;
                el.dispatchEvent(new Event('change', {bubbles: true}));
            }),
    ]);
}

test.describe('Dashboard', () => {
    test.beforeEach(async ({authenticatedPage: page}) => {
        // Create DASH_NAME if it doesn't exist yet (200 = created, 409 = already exists — both fine).
        // We never archive this action, so the unique constraint is never violated on re-runs.
        await page.goto('/actions');
        await page.evaluate(async (name: string) => {
            const params = new URLSearchParams({name, colour: '#6366f1'});
            await fetch('/actions', {
                method: 'POST',
                headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                body: params.toString(),
            });
        }, DASH_NAME);

        // Navigate to dashboard and reset today's log count to 0 before each test.
        // Tests that increment/decrement need a clean starting state.
        await page.goto('/');
        await page.locator('#day-panel [id^="log-"]').first().waitFor({timeout: 5000}).catch(() => {
        });
        for (let i = 0; i < 10; i++) {
            const decBtn = page.locator('#day-panel').getByTitle('Decrease').first();
            if (await decBtn.isDisabled().catch(() => true)) break;
            await Promise.all([
                page.waitForResponse(r => r.url().includes('/logs/') && r.request().method() === 'POST'),
                decBtn.click(),
            ]);
        }
    });

    test('page load: today is pre-selected and day panel loads automatically', async ({authenticatedPage: page}) => {
        await page.goto('/');
        // Today's cell should have the d-day-selected class
        const todayCell = page.locator(`.fc-daygrid-day[data-date="${todayStr()}"]`);
        await expect(todayCell).toHaveClass(/d-day-selected/);
        // Day panel should have loaded content (not the placeholder)
        await expect(page.locator('#day-panel')).not.toContainText('Click a day to log actions');
    });

    test('click a past date loads that day in the day panel', async ({authenticatedPage: page}) => {
        await page.goto('/');
        const past = pastDateStr(3);
        const cell = page.locator(`.fc-daygrid-day[data-date="${past}"]`);
        await cell.click();
        await expect(page.locator('#day-panel')).toContainText('DashAction');
    });

    test('clicking a logged event dot loads the correct day panel', async ({authenticatedPage: page}) => {
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

    test('increment button increases count from 0 to 1', async ({authenticatedPage: page}) => {
        await page.goto('/');
        const countEl = page.locator('#day-panel [id^="log-"]').first().locator('.tabular-nums');
        await expect(countEl).toHaveText('0');
        await page.locator('#day-panel').getByTitle('Increase').first().click();
        await expect(countEl).toHaveText('1');
    });

    test('increment twice reaches count 2', async ({authenticatedPage: page}) => {
        await page.goto('/');
        const incrementBtn = page.locator('#day-panel').getByTitle('Increase').first();
        await incrementBtn.click();
        await incrementBtn.click();
        const countEl = page.locator('#day-panel [id^="log-"]').first().locator('.tabular-nums');
        await expect(countEl).toHaveText('2');
    });

    test('decrement from 1 reaches 0 and disables minus button', async ({authenticatedPage: page}) => {
        await page.goto('/');
        await page.locator('#day-panel').getByTitle('Increase').first().click();
        await page.locator('#day-panel').getByTitle('Decrease').first().click();
        const countEl = page.locator('#day-panel [id^="log-"]').first().locator('.tabular-nums');
        await expect(countEl).toHaveText('0');
        await expect(page.locator('#day-panel').getByTitle('Decrease').first()).toBeDisabled();
    });

    test('decrement button is disabled when count is 0', async ({authenticatedPage: page}) => {
        await page.goto('/');
        await expect(page.locator('#day-panel').getByTitle('Decrease').first()).toBeDisabled();
    });

    test('calendar dots refresh after logging an action', async ({authenticatedPage: page}) => {
        await page.goto('/');
        const today = todayStr();
        // No event dots for today initially (before logging)
        const dotsBefore = await page.locator(`.fc-daygrid-day[data-date="${today}"] .fc-event`).count();
        // Log an action
        await page.locator('#day-panel').getByTitle('Increase').first().click();
        // FullCalendar refetches events after htmx:afterRequest — wait for the dot
        await expect(page.locator(`.fc-daygrid-day[data-date="${today}"] .fc-event`)).toHaveCount(Math.max(dotsBefore + 1, 1), {timeout: 5000});
    });

    test('future date shows "future" message with no +/− buttons', async ({authenticatedPage: page}) => {
        await page.goto('/');
        // Click on the next month's first day to get a future date
        await page.locator('#cal-next').click();
        const futureCell = page.locator('.fc-daygrid-day').filter({has: page.locator('.fc-daygrid-day-number')}).first();
        await futureCell.click();
        await expect(page.locator('#day-panel')).toContainText(/future|cannot log/i);
        await expect(page.locator('#day-panel').getByTitle('Increase')).toHaveCount(0);
    });

    test('jump picker: calendar icon opens month grid, click closes it, Escape closes it', async ({authenticatedPage: page}) => {
        await page.goto('/');
        const jumpBtn = page.locator('#cal-jump');
        await jumpBtn.click();
        const popup = page.locator('#cal-pop');
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

    test('jump picker: year arrows change year label', async ({authenticatedPage: page}) => {
        await page.goto('/');
        await page.locator('#cal-jump').click();
        const yearLabel = page.locator('.cal-pop-year');
        const originalYear = await yearLabel.textContent();

        await page.locator('button[data-y="1"]').click();
        await expect(yearLabel).not.toHaveText(originalYear!);
    });

    test('stats summary card is hidden when no actions are logged', async ({page}) => {
        // Use a fresh user with no logs
        const freshUser = {
            email: `e2e-dashboard-empty-${Date.now()}@example.com`,
            password: 'test_password123',
            displayName: 'Empty'
        };
        const {setupTestUser} = await import('../helpers/fixtures');
        await setupTestUser(page, freshUser);
        await page.goto('/');
        // The Stats summary card is only rendered once there are logged actions; with none, the
        // whole card (and its "View stats" CTA) is omitted rather than showing an empty state.
        await expect(page.locator('a:has-text("View stats for all actions")')).toHaveCount(0);
    });
});

// All calendar types that support month-view navigation. Add a new entry here
// when a new calendar type is introduced — the adjacent-month test below covers it automatically.
// Add new calendar view types here to automatically include them in the navigation test.
const ALL_CALENDAR_VIEWS: string[] = ['full', 'minimal', 'stacked'];

test.describe('Dashboard – Calendar navigation', () => {
    test.afterEach(async ({authenticatedPage: page}) => {
        await page.goto('/settings');
        await setCalendarView(page, 'full');
    });

    test('clicking an other-month date navigates the calendar to that month', async ({authenticatedPage: page}) => {
        for (const calendarView of ALL_CALENDAR_VIEWS) {
            await page.goto('/settings');
            await setCalendarView(page, calendarView);
            await page.goto('/');

            const otherCellSelector = calendarView === 'full'
                ? '.fc-daygrid-day.fc-day-other'
                : '.d-min-cell.d-min-other';
            // Every calendar style now shares one toolbar, so the title element is the same id.
            const titleSelector = '#cal-title';

            const otherCell = page.locator(otherCellSelector).first();
            const otherDate = await otherCell.getAttribute('data-date');
            expect(otherDate).toBeTruthy();

            const titleBefore = await page.locator(titleSelector).textContent() ?? '';
            await otherCell.click();

            // Title must change to reflect the adjacent month
            await expect(page.locator(titleSelector)).not.toHaveText(titleBefore);

            // The clicked cell must no longer carry the "other month" class
            const cellAfterNav = calendarView === 'full'
                ? page.locator(`.fc-daygrid-day[data-date="${otherDate}"]`)
                : page.locator(`.d-min-cell[data-date="${otherDate}"]`);
            await expect(cellAfterNav).not.toHaveClass(
                calendarView === 'full' ? /fc-day-other/ : /d-min-other/,
            );
        }
    });
});

test.describe('Dashboard – Minimal calendar', () => {
    // Switch to minimal view before each test; reset after so the outer describe is unaffected.
    test.beforeEach(async ({authenticatedPage: page}) => {
        await page.goto('/settings');
        await setCalendarView(page, 'minimal');
    });

    test.afterEach(async ({authenticatedPage: page}) => {
        await page.goto('/settings');
        await setCalendarView(page, 'full');
    });

    test('minimal calendar is rendered instead of FullCalendar', async ({authenticatedPage: page}) => {
        await page.goto('/');
        await expect(page.locator('#d-min-grid')).toBeVisible();
        await expect(page.locator('#calendar')).toHaveCount(0);
    });

    test('today cell carries the today highlight class', async ({authenticatedPage: page}) => {
        await page.goto('/');
        await expect(page.locator(`.d-min-cell[data-date="${todayStr()}"]`)).toHaveClass(/d-min-today/);
    });

    test('today is pre-selected and day panel loads automatically', async ({authenticatedPage: page}) => {
        await page.goto('/');
        await expect(page.locator('#day-panel')).not.toContainText('Click a day to log actions');
    });

    test('clicking a past date loads that day in the day panel', async ({authenticatedPage: page}) => {
        await page.goto('/');
        const past = pastDateStr(3);
        await page.locator(`.d-min-cell[data-date="${past}"]`).click();
        await expect(page.locator('#day-panel')).not.toContainText('Click a day to log actions');
    });

    test('clicked date receives the selected class', async ({authenticatedPage: page}) => {
        await page.goto('/');
        const past = pastDateStr(2);
        await page.locator(`.d-min-cell[data-date="${past}"]`).click();
        await expect(page.locator(`.d-min-cell[data-date="${past}"]`)).toHaveClass(/d-min-selected/);
    });

    test('dot appears under today after logging an action', async ({authenticatedPage: page}) => {
        await page.goto('/');
        // Ensure today's log is at 0 first
        await page.locator('#day-panel [id^="log-"]').first().waitFor({timeout: 5000}).catch(() => {
        });
        for (let i = 0; i < 10; i++) {
            const decBtn = page.locator('#day-panel').getByTitle('Decrease').first();
            if (await decBtn.isDisabled().catch(() => true)) break;
            await Promise.all([
                page.waitForResponse(r => r.url().includes('/logs/') && r.request().method() === 'POST'),
                decBtn.click(),
            ]);
        }

        const today = todayStr();
        await expect(page.locator(`.d-min-cell[data-date="${today}"] .d-min-dot`)).toHaveCount(0);

        await Promise.all([
            page.waitForResponse(r => r.url().includes('/logs/') && r.request().method() === 'POST'),
            page.locator('#day-panel').getByTitle('Increase').first().click(),
        ]);

        await expect(page.locator(`.d-min-cell[data-date="${today}"] .d-min-dot`)).toHaveCount(1, {timeout: 5000});
    });

    test('jump picker opens and closes with Escape', async ({authenticatedPage: page}) => {
        await page.goto('/');
        await page.locator('#cal-jump').click();
        await expect(page.locator('#cal-pop')).not.toHaveClass(/hidden/);

        await page.keyboard.press('Escape');
        await expect(page.locator('#cal-pop')).toHaveClass(/hidden/);
    });

    test('jump picker closes on click outside', async ({authenticatedPage: page}) => {
        await page.goto('/');
        await page.locator('#cal-jump').click();
        await expect(page.locator('#cal-pop')).not.toHaveClass(/hidden/);

        await page.locator('h2').first().click();
        await expect(page.locator('#cal-pop')).toHaveClass(/hidden/);
    });

    test('prev/next month navigation changes the title', async ({authenticatedPage: page}) => {
        await page.goto('/');
        const originalTitle = await page.locator('#cal-title').textContent();
        await page.locator('#cal-next').click();
        await expect(page.locator('#cal-title')).not.toHaveText(originalTitle!);
    });

    test('today button returns to current month', async ({authenticatedPage: page}) => {
        await page.goto('/');
        const originalTitle = await page.locator('#cal-title').textContent();
        await page.locator('#cal-next').click();
        await page.locator('#cal-today').click();
        await expect(page.locator('#cal-title')).toHaveText(originalTitle!);
    });
});

test.describe('Dashboard – Stacked calendar', () => {
    test.beforeEach(async ({authenticatedPage: page}) => {
        await page.goto('/settings');
        await setCalendarView(page, 'stacked');
    });

    test.afterEach(async ({authenticatedPage: page}) => {
        await page.goto('/settings');
        await setCalendarView(page, 'full');
    });

    test('stacked calendar is rendered instead of FullCalendar', async ({authenticatedPage: page}) => {
        await page.goto('/');
        await expect(page.locator('#d-min-grid')).toBeVisible();
        await expect(page.locator('#calendar')).toHaveCount(0);
    });

    test('today cell carries the today highlight class', async ({authenticatedPage: page}) => {
        await page.goto('/');
        await expect(page.locator(`.d-min-cell[data-date="${todayStr()}"]`)).toHaveClass(/d-min-today/);
    });

    test('today is pre-selected and day panel loads automatically', async ({authenticatedPage: page}) => {
        await page.goto('/');
        await expect(page.locator('#day-panel')).not.toContainText('Click a day to log actions');
    });

    test('clicking a past date loads that day in the day panel', async ({authenticatedPage: page}) => {
        await page.goto('/');
        const past = pastDateStr(3);
        await page.locator(`.d-min-cell[data-date="${past}"]`).click();
        await expect(page.locator('#day-panel')).not.toContainText('Click a day to log actions');
    });

    test('bar appears under today after logging an action', async ({authenticatedPage: page}) => {
        await page.goto('/');
        // Reset log count to 0
        await page.locator('#day-panel [id^="log-"]').first().waitFor({timeout: 5000}).catch(() => {
        });
        for (let i = 0; i < 10; i++) {
            const decBtn = page.locator('#day-panel').getByTitle('Decrease').first();
            if (await decBtn.isDisabled().catch(() => true)) break;
            await Promise.all([
                page.waitForResponse(r => r.url().includes('/logs/') && r.request().method() === 'POST'),
                decBtn.click(),
            ]);
        }

        const today = todayStr();
        await expect(page.locator(`.d-min-cell[data-date="${today}"] .d-stk-bar`)).toHaveCount(0);

        await Promise.all([
            page.waitForResponse(r => r.url().includes('/logs/') && r.request().method() === 'POST'),
            page.locator('#day-panel').getByTitle('Increase').first().click(),
        ]);

        await expect(page.locator(`.d-min-cell[data-date="${today}"] .d-stk-bar`)).toHaveCount(1, {timeout: 5000});
    });

    test('prev/next month navigation changes the title', async ({authenticatedPage: page}) => {
        await page.goto('/');
        const originalTitle = await page.locator('#cal-title').textContent();
        await page.locator('#cal-next').click();
        await expect(page.locator('#cal-title')).not.toHaveText(originalTitle!);
    });
});

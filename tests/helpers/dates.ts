// Date helpers shared by the E2E specs, all computed in UTC to match the server (app.timezone=UTC under
// -Dall). Using setUTCDate/getUTCDate (not the local setDate/getDate) keeps the arithmetic in the same
// zone as toISOString(), so a non-UTC host (e.g. NZST) near midnight can't shift a result by a day.
//
// These started life copied into each spec that needed them, on the reasoning that one-line expressions
// were not worth coupling two specs over. They are now wanted by four, which is enough that the copies
// were the bigger cost: the UTC rule above is the whole point of them, and it has to hold identically
// everywhere or a spec silently goes flaky for one hour a day on the wrong host.

// Today's date as YYYY-MM-DD.
export function todayStr(): string {
    return new Date().toISOString().slice(0, 10)
}

// A past date offset by -n days.
//
// This says NOTHING about the calendar grid, and a spec that wants to click the date's cell must not
// assume one exists. The grid carries only `(weekday of the 1st - week start + 7) % 7` leading days of the
// previous month, which is 0-6 depending on the calendar date, so how far back a cell can be found drifts
// over the month and vanishes at the start of one: on 2026-09-01 (a Tuesday, Monday-first week) the grid
// began at 2026-08-31, and every spec reaching further back failed with `element(s) not found`. Pair it
// with `showMonthOf` from ../helpers/calendar, or use `otherDaysThisMonth` below.
export function pastDateStr(daysAgo: number): string {
    const d = new Date()
    d.setUTCDate(d.getUTCDate() - daysAgo)
    return d.toISOString().slice(0, 10)
}

// A future date offset by +n days.
//
// Unlike the past direction above, a SMALL offset is always drawn: the grid is 42 cells and at most 6 of
// them lead, so at least 5 trail even a 31-day month, and +2/+3 from any day of it lands inside them.
export function futureDateStr(daysAhead: number): string {
    const d = new Date()
    d.setUTCDate(d.getUTCDate() + daysAhead)
    return d.toISOString().slice(0, 10)
}

// `count` distinct days of the CURRENT UTC month, earliest first, never today.
//
// The grid always draws every day of the month it is showing, so these are the only dates guaranteed a
// cell without paging the calendar first - which is what a spec needs when it must stay in today's month,
// either because it asserts a cache HIT (paging fetches the month it lands on) or because it clicks back
// to today's cell afterwards. Taken from the start of the month, so a 28-day February is as safe as any
// other, and `count` above 27 is a programming error rather than a date-dependent one.
//
// Today is skipped because its cell is styled differently in every calendar - the brand fill, and a
// LIGHTENED note marker to stay legible on it - so a spec asserting the ordinary appearance must not land
// on it. The days returned may therefore be in the future; a note can be written for any date, so only a
// spec about LOGGING needs a genuinely past day (and that one wants `pastDateStr` + `showMonthOf`).
export function otherDaysThisMonth(count: number): string[] {
    const now = new Date()
    const today = now.getUTCDate()
    const lastDay = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 0)).getUTCDate()
    const days: string[] = []

    // Without this the loop below would walk past the end of the month and `Date.UTC` would roll into the
    // NEXT one, handing back a date with no cell - the exact failure this helper exists to remove. February
    // is the binding case, so the ceiling is 27 in a common year.
    if (count > lastDay - 1) {
        throw new Error(`asked for ${count} days other than today, but this month has only ${lastDay - 1}`)
    }

    for (let day = 1; days.length < count; day++) {
        if (day !== today) {
            days.push(new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), day)).toISOString().slice(0, 10))
        }
    }

    return days
}

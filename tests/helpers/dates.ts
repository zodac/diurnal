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
export function pastDateStr(daysAgo: number): string {
    const d = new Date()
    d.setUTCDate(d.getUTCDate() - daysAgo)
    return d.toISOString().slice(0, 10)
}

// A future date offset by +n days. A small offset stays within the rendered month grid (which also shows
// trailing days of the next month), so the cell is clickable without paging the calendar.
export function futureDateStr(daysAhead: number): string {
    const d = new Date()
    d.setUTCDate(d.getUTCDate() + daysAhead)
    return d.toISOString().slice(0, 10)
}

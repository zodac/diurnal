/*
 * Dashboard calendar engine (extracted from dashboard.html so it rides the immutable,
 * content-hashed cache instead of being re-parsed on every dashboard load).
 *
 * Served as /js/dashboard.<hash>.js in production (hashed + `immutable` in the Dockerfile, baked
 * into AppInfo.jsDashboardFile) and /js/dashboard.js in dev. Loaded only on the dashboard, as a
 * classic script at the end of <body>. The two server-injected values it needs — the app's UTC
 * "today" and the user's calendar style — arrive via the #dashboard-main element's data-today /
 * data-calendar-view attributes (dashboard.html), so this file carries no inline bootstrap and
 * stays a fully static, content-hashed script. Because this is a plain .js file (not a Qute
 * template) the historic `{`-escaping caveats no longer apply here.
 */
document.addEventListener('DOMContentLoaded', function () {
    const _cfg = document.getElementById('dashboard-main').dataset
    const today = _cfg.today
    const calendarView = _cfg.calendarView
    // The real "today" month, parsed once — the jump popup marks the month containing today with the
    // calendar's solid "today" highlight (distinct from the merely-viewed month, which gets the
    // selected-day ring). Shared by every calendar style's picker.
    const todayYear  = parseInt(today.substring(0, 4), 10)
    const todayMonth = parseInt(today.substring(5, 7), 10) - 1 // 0-indexed

    // The month/year title is emitted with BOTH a full and an abbreviated variant in the DOM; CSS
    // (see the .cal-title media queries) shows whichever fits the screen width, so the single-row
    // toolbar never wraps. Shared by every calendar style.
    const CAL_MONTHS_FULL = ['January', 'February', 'March', 'April', 'May', 'June',
                           'July', 'August', 'September', 'October', 'November', 'December']
    const CAL_MONTHS_ABBR = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
                           'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
    function setCalTitle(el, year, monthIndex) {
        const yr = String(year)
        el.innerHTML =
            `<span class="cal-title-month-full">${  CAL_MONTHS_FULL[monthIndex]  }</span>` +
            `<span class="cal-title-month-abbr">${  CAL_MONTHS_ABBR[monthIndex]  }</span>` +
            ' ' +
            `<span class="cal-title-year-full">${  yr  }</span>` +
            `<span class="cal-title-year-abbr">${  yr.slice(-2)  }</span>`
        fitCalTitle(el)
    }

    // Abbreviate the title ONLY when the full text would overflow the toolbar row — measured live, so a
    // short month ("June") keeps its full name at a width where a long one ("September") must shorten.
    // Start from the fullest form and drop detail (month, then year) only while the row still overflows.
    function fitCalTitle(el) {
        const bar = el.closest('.cal-toolbar')
        if (!bar) {return}
        el.classList.remove('cal-title-abbr-month', 'cal-title-abbr-year')
        if (bar.scrollWidth <= bar.clientWidth) {return}
        el.classList.add('cal-title-abbr-month')
        if (bar.scrollWidth <= bar.clientWidth) {return}
        el.classList.add('cal-title-abbr-year')
    }
    // Re-fit on resize/orientation change: the same #cal-title serves whichever calendar style is active.
    window.addEventListener('resize', function () {
        const t = document.getElementById('cal-title')
        if (t) {fitCalTitle(t)}
    })

    // ── Shared calendar chrome ──────────────────────────────────────────────
    // The three calendar styles (full / minimal / stacked) differ only in how they RENDER each cell and
    // which feed fills their month. Everything around them — toolbar navigation, the month/year picker
    // popup, day selection and the day-panel, and the log-toggle refresh — is identical, so it lives here
    // ONCE and drives the active style through a small adapter (`cal`) built below, with four methods:
    //   currentView() -> { year, month }  the month on screen
    //   goToMonth(year, month)           navigate to a month (no selection change)
    //   setHighlight(dateStr|null)       record + paint (or clear) the selected-day ring
    //   refresh()                        re-pull the month's dots/events after a log change
    const calWrap  = document.getElementById('calendar-wrap')
    const titleEl  = document.getElementById('cal-title')
    const pop      = document.getElementById('cal-pop')
    const dayPanel = document.getElementById('day-logger-panel')
    const dayPanelPlaceholder = dayPanel ? dayPanel.innerHTML : '' // the "Click a day…" prompt, captured pre-load
    let selectedDate = null // ISO date of the highlighted day, or null when nothing is selected

    function pad2(n) { return String(n).padStart(2, '0') }

    // ── Day-panel cache ──────────────────────────────────────────────────────
    // Each /logs/day/<date> partial is cached so re-opening a day by clicking around the calendar is
    // instant instead of paying the edge round-trip every time (the public origin sits behind Cloudflare,
    // so each call is a ~250ms+ round-trip). We cache the PRISTINE default view (page 1, no search
    // filter) — exactly what a fresh load returns — so a cached swap is indistinguishable from a network
    // one. The selected day is fetched on its own (fast first paint); the REST of its month is then
    // back-filled by a SINGLE /logs/month/<yyyy-MM> request that returns every day's HTML in one map,
    // rather than fanning out ~30 concurrent per-day requests (which would exhaust the small JDBC pool).
    const dayPanelCache    = {} // dateStr -> HTML string (the /logs/day response body)
    const dayPanelInflight = {} // dateStr -> Promise, dedupes concurrent single-day fetches
    const monthBackfilled  = {} // "YYYY-MM" -> true once its whole-month back-fill has been requested

    // The panel cache is bounded the same way `dayData` is (see that cache's LRU below): by month,
    // least-recently-used first. Each visited month back-fills ~30 rendered HTML strings, so WITHOUT a cap
    // a session that browses back through years would accumulate megabytes that are never released.
    // `dayPanelLru` holds month keys oldest→newest; DAY_PANEL_MONTH_LIMIT stays well above the working set
    // (the selected month plus the neighbours a user hops between) and touch-on-access keeps the current
    // month at the tail, so a month that's on screen or about to be revisited is never dropped.
    const dayPanelLru           = [] // "YYYY-MM" keys, least-recently-used first
    const DAY_PANEL_MONTH_LIMIT = 12 // max months of cached panels retained (mirrors the dayData CACHE_LIMIT)

    // Mark a date's month as most-recently-used, then evict the oldest months' panels until at most
    // DAY_PANEL_MONTH_LIMIT remain resident. Called on every cache access and write.
    function touchPanelMonth(dateStr) {
        const ym = dateStr.substring(0, 7) // "YYYY-MM"
        const i = dayPanelLru.indexOf(ym)
        if (i !== -1) { dayPanelLru.splice(i, 1) }
        dayPanelLru.push(ym)
        while (dayPanelLru.length > DAY_PANEL_MONTH_LIMIT) {
            const stale = dayPanelLru.shift()
            const prefix = `${stale  }-` // "YYYY-MM-" — every date key in that month
            Object.keys(dayPanelCache).forEach(function (d) { if (d.indexOf(prefix) === 0) { delete dayPanelCache[d] } })
            delete monthBackfilled[stale] // let a later visit re-fetch the whole month
        }
    }

    function fetchDayPanel(dateStr) {
        if (dayPanelCache[dateStr] !== undefined) { touchPanelMonth(dateStr); return Promise.resolve(dayPanelCache[dateStr]) }
        if (dayPanelInflight[dateStr]) { return dayPanelInflight[dateStr] }
        const p = fetch(`/logs/day/${  dateStr}`)
            .then(function (r) { return r.text() })
            .then(function (html) { dayPanelCache[dateStr] = html; delete dayPanelInflight[dateStr]; touchPanelMonth(dateStr); return html })
            .catch(function (err) { delete dayPanelInflight[dateStr]; throw err }) // drop so a later view retries
        dayPanelInflight[dateStr] = p
        return p
    }

    // Back-fill every other day of dateStr's month from one bulk request, once the browser is idle so it
    // never competes with the just-issued load for the selected day. Runs at most once per month, and
    // only fills days NOT already cached — so the selected day (and any day the user has since changed,
    // whose stale entry was dropped) keeps its fresher copy instead of being clobbered by the snapshot.
    function backfillMonth(dateStr) {
        const ym = dateStr.substring(0, 7) // "YYYY-MM"
        if (monthBackfilled[ym]) { return }
        monthBackfilled[ym] = true
        const schedule = window.requestIdleCallback || function (fn) { return setTimeout(fn, 200) }
        schedule(function () {
            fetch(`/logs/month/${  ym}`)
                .then(function (r) { return r.json() })
                .then(function (panels) {
                    Object.keys(panels).forEach(function (d) {
                        if (dayPanelCache[d] === undefined) { dayPanelCache[d] = panels[d] }
                    })
                    touchPanelMonth(dateStr) // whole month now resident — record recency & trim
                })
                .catch(function () { delete monthBackfilled[ym] }) // let a later navigation retry
        })
    }

    // Swap a day's cached/loaded HTML into the panel and wire its HTMX attributes (htmx.process, since
    // we set innerHTML directly rather than going through htmx.ajax). Lifts the in-flight dim.
    function swapDayPanel(html) {
        if (!dayPanel) {return}
        dayPanel.innerHTML = html
        htmx.process(dayPanel)
        dayPanel.style.opacity = ''
    }

    function loadDayPanel(dateStr) {
        if (dayPanel && dayPanelCache[dateStr] === undefined) {
            // Cache miss: keep the previous day's actions on screen but dim them while the response is in
            // flight. Blanking the panel (or painting a skeleton) reads as a harsh flash on fast loads;
            // holding the content and fading the opacity makes the switch feel continuous. A cache hit
            // resolves on the next microtask, so it swaps instantly with no dim.
            dayPanel.style.transition = 'opacity 150ms ease'
            dayPanel.style.opacity = '0.45'
        }
        fetchDayPanel(dateStr).then(function (html) {
            // Only swap if the user is still on this day (they may have clicked onward mid-fetch, or
            // cleared the selection, in which case that action already won).
            if (selectedDate === dateStr) { swapDayPanel(html) }
        })
        backfillMonth(dateStr) // back-fill the rest of the month from one bulk request
    }

    // Persist / restore the chosen day for the current WORKING session. sessionStorage is scoped to this
    // browser tab: it survives in-app navigation (every page is a full load, so this script re-runs and
    // would otherwise reset to today) but is wiped when the tab/browser closes. It is ALSO cleared on the
    // login page (see login.html) so a logout — or a different user logging in on the same tab — starts
    // fresh, tying the retained date to the working session rather than the authentication session.
    const SELECTED_DATE_KEY = 'diurnal.selectedDate'
    function rememberSelectedDate(dateStr) { try { sessionStorage.setItem(SELECTED_DATE_KEY, dateStr) } catch (e) {} }
    function forgetSelectedDate()          { try { sessionStorage.removeItem(SELECTED_DATE_KEY) } catch (e) {} }

    // Select a specific day: paint its highlight, switch month if it belongs to an adjacent one, load panel.
    function selectDay(dateStr) {
        const y = parseInt(dateStr.substring(0, 4), 10)
        const m = parseInt(dateStr.substring(5, 7), 10) - 1
        const v = cal.currentView()
        cal.setHighlight(dateStr)
        if (y !== v.year || m !== v.month) { cal.goToMonth(y, m) } // re-applies the highlight on arrival
        loadDayPanel(dateStr)
        rememberSelectedDate(dateStr)
    }

    // Deselect the current day and reset the panel to its prompt. Used when only the MONTH changes
    // (arrows / picker), where no specific day was chosen, so leaving the old day's actions on screen
    // would be misleading. Selecting an explicit date (selectDay) does the opposite and keeps it.
    function clearSelection() {
        cal.setHighlight(null)
        if (dayPanel) { dayPanel.style.opacity = ''; dayPanel.innerHTML = dayPanelPlaceholder }
        forgetSelectedDate()
    }

    // ── Hand-rolled grid (full / minimal / stacked) ──────────────────────────
    // One engine renders all three calendar styles over the same 7×6 month grid; only each cell's
    // contents differ (see renderGrid's `calendarView` branch) and which data feed fills `dayData`
    // (see fetchMonth). `full` draws bordered cells with a day number + a list of logged-action events;
    // `minimal`/`stacked` draw a centred date circle with a dots/bars activity strip.
    function buildGridCalendar() {
        const grid      = document.getElementById('d-min-grid')
        let viewYear  = parseInt(today.substring(0, 4), 10)
        let viewMonth = parseInt(today.substring(5, 7), 10) - 1 // 0-indexed
        const dayData   = {} // date string -> array of { colour, label } (one per logged action that day);
                            // ACCUMULATES across months (keys are full dates, so months never collide) and
                            // acts as the month cache. `label` is only rendered by the `full` style; the
                            // dots/bars read `colour`. Filled (and normalised per feed) by fetchMonth.

        // Optically centre the date number inside its circle, on BOTH axes. flex centres the glyph's
        // ADVANCE box horizontally and its line/em box vertically, but the painted ink doesn't sit
        // centred within those, so a circle/ring around the number looks lopsided. We paint the digits to
        // an offscreen canvas at the same font and measure the painted pixels, then translate the digits
        // to centre them — but the right "centre" differs per axis:
        //   • HORIZONTAL → the ink BOUNDING BOX (so the whitespace gaps left & right are equal, which is
        //     what the eye reads sideways). The digit side-bearings (the "1" glyph especially) otherwise
        //     leave the number off-centre. This is the web analogue of Android's Paint.getTextBounds.
        //     NB centring the horizontal *mass* instead skews mixed-weight numbers (e.g. the light "1" +
        //     heavy "5" in "15" pulls the mass right, so mass-centring leaves a gap on the right).
        //   • VERTICAL → the ink MASS centroid. Digits have no descenders, so bounding-box centring makes
        //     them ride visibly high; centring the mass reads as balanced. The vertical reference is the
        //     text's real baseline, read from the DOM with a zero-height baseline-aligned strut probe
        //     (canvas em-box metrics don't match the browser's line-box placement).
        // Both measurements are cached (by digits+weight+size / weight+size — all day states now share
        // one weight, so the cache key still holds if that ever changes again).
        const inkCanvas = document.createElement('canvas')
        const inkCtx = inkCanvas.getContext('2d', { willReadFrequently: true })
        let inkCentroidCache = {} // weight|size|text -> { x: bbox centre from advance centre, y: mass from baseline }
        let baselineCache = {}    // weight|size      -> baseline offset from the circle's top edge (CSS px)
        let circleSize = 0        // the circle's height (constant across cells) — read once

        // `full`-style event fitting (see fitFullEvents). The font is fixed; keep FULL_FONT_PX in sync with
        // `.d-cal-full .d-full-event`'s font-size (0.7rem) so the width measurements match what's painted.
        const rootPx = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16
        const FULL_FONT_PX = 0.70 * rootPx

        // Natural width (CSS px) of `text` at the given font, reusing the ink canvas. Independent of layout, so
        // it lets fitFullEvents decide truncation without per-candidate reflows.
        function measureText(text, cs, fontPx) {
            inkCtx.font = `${cs.fontWeight  } ${  fontPx  }px ${  cs.fontFamily}`
            return inkCtx.measureText(text).width
        }

        function inkCentroid(cs, text) {
            const key = `${cs.fontWeight  }|${  cs.fontSize  }|${  text}`
            let c = inkCentroidCache[key]
            if (c) {return c}
            const fontPx = parseFloat(cs.fontSize) || 12
            const ss = 8                            // supersample for sub-pixel precision
            const box = Math.ceil(fontPx * 3) * ss  // ample square around the digits
            const baseY = box / 2                   // draw the alphabetic baseline at mid-canvas
            inkCanvas.width = box; inkCanvas.height = box
            inkCtx.font = `${cs.fontWeight  } ${  fontPx * ss  }px ${  cs.fontFamily}`
            inkCtx.textAlign = 'center'           // x origin = glyph advance centre
            inkCtx.textBaseline = 'alphabetic'
            inkCtx.fillStyle = '#000'
            inkCtx.fillText(text, box / 2, baseY)
            const data = inkCtx.getImageData(0, 0, box, box).data
            let minX = box, maxX = -1, sumY = 0, weight = 0
            for (let py = 0; py < box; py++) {
                for (let px = 0; px < box; px++) {
                    const alpha = data[(py * box + px) * 4 + 3]
                    if (alpha > 20) {
                        if (px < minX) { minX = px }
                        if (px > maxX) { maxX = px }
                        sumY += py * alpha; weight += alpha
                    }
                }
            }
            c = weight
                ? { x: ((minX + maxX) / 2 - box / 2) / ss, y: (sumY / weight - baseY) / ss }  // x: bbox centre, y: mass
                : { x: 0, y: 0 }
            inkCentroidCache[key] = c
            return c
        }

        function baselineOffset(circleEl, textEl, cs) {
            const key = `${cs.fontWeight  }|${  cs.fontSize}`
            if (baselineCache[key] !== undefined) {return baselineCache[key]}
            const strut = document.createElement('span')
            strut.style.cssText = 'display:inline-block;width:0;height:0;vertical-align:baseline'
            textEl.appendChild(strut)
            const off = strut.getBoundingClientRect().top - circleEl.getBoundingClientRect().top
            strut.remove()
            baselineCache[key] = off
            return off
        }

        function centreInk(circleEl, textEl) {
            const cs = getComputedStyle(circleEl)
            if (!circleSize) { circleSize = circleEl.getBoundingClientRect().height }
            const c = inkCentroid(cs, textEl.textContent)
            const baseY = baselineOffset(circleEl, textEl, cs)
            const shiftX = -c.x                         // ink bbox centre -> circle centre (equal whitespace)
            const shiftY = circleSize / 2 - (baseY + c.y) // ink mass centroid -> circle centre
            textEl.style.transform = `translate(${  shiftX.toFixed(3)  }px,${  shiftY.toFixed(3)  }px)`
        }

        // ── Month data cache (LRU) & background prefetch ────────────────────────────
        // Each month's dots are fetched once and merged into `dayData`; `monthPromises` dedupes in-flight
        // and resolved fetches, and `monthLoaded` flags a month whose data is already in `dayData` so we
        // can render it WITHOUT a network wait. This is what makes month navigation feel instant: the public
        // origin sits behind Cloudflare, so every `/logs/minimal-events` call is a ~250ms+ edge round-trip.
        // We pay that once per month (eagerly for the visible month, on idle for its neighbours) instead of
        // on every prev/next click.
        //
        // To bound memory when a user hops across many months, resident months are capped by a least-recently-
        // -used policy (`lru` holds month keys oldest→newest; `touch` refreshes recency on every access). Only
        // RESOLVED months count toward the cap and are eligible for eviction, so an in-flight prefetch is never
        // dropped mid-fetch. CACHE_LIMIT must stay well above the live window (2*PREFETCH_RADIUS+1) so that
        // hopping between adjacent months never evicts a month that's still on screen or about to be revisited.
        const monthPromises = {} // "YYYY-MM" -> Promise (in-flight or resolved)
        const monthLoaded   = {} // "YYYY-MM" -> true once its data has been merged into dayData
        const lru           = [] // "YYYY-MM" keys, least-recently-used first
        const PREFETCH_RADIUS = 2 // months either side of the visible one to warm in the background
        const CACHE_LIMIT     = 12 // max RESOLVED months retained (>> 2*PREFETCH_RADIUS+1 = 5, the live window)

        function monthKey(y, m) { return `${y  }-${  pad2(m + 1)}` }

        function stepMonth(year, month, delta) {
            let y = year
            let m = month + delta
            while (m > 11) { m -= 12; y++ }
            while (m < 0)  { m += 12; y-- }
            return [y, m]
        }

        // Last calendar day of the given year/month as an ISO date string. The feeds treat `end` as INCLUSIVE
        // (`logDate <= end`), so a month's fetch must end on its own last day. Using the 1st of the NEXT
        // month here would pull that day into this month's response, and since the `full` merge APPENDS
        // per date, the 1st of every month would then be double-appended by both its own month's fetch and
        // the preceding month's — rendering the same action twice. (day 0 of the next month = last of this.)
        function monthEnd(y, m) { return `${y  }-${  pad2(m + 1)  }-${  pad2(new Date(Date.UTC(y, m + 1, 0)).getUTCDate())}` }

        // Mark a month as most-recently-used (moves it to the end of `lru`).
        function touch(key) {
            const i = lru.indexOf(key)
            if (i !== -1) { lru.splice(i, 1) }
            lru.push(key)
        }

        // Forget a month entirely: its dots, its cached fetch, and its loaded flag.
        function dropMonth(key) {
            const prefix = `${key  }-` // "2026-06-" — every date key in that month
            Object.keys(dayData).forEach(function (d) { if (d.indexOf(prefix) === 0) { delete dayData[d] } })
            delete monthPromises[key]
            delete monthLoaded[key]
        }

        // The current month and its two neighbours are PINNED: never evicted, regardless of recency. The
        // 'Today' button snaps straight back to the current month (whose grid also spills into ±1), so those
        // three are the months most likely to be revisited — keeping them resident makes that jump instant
        // even after the user has hopped far enough away to LRU-evict everything else.
        const prevMonth = stepMonth(todayYear, todayMonth, -1)
        const nextMonth = stepMonth(todayYear, todayMonth,  1)
        const PINNED_MONTHS = [
            monthKey(prevMonth[0], prevMonth[1]),
            monthKey(todayYear, todayMonth),
            monthKey(nextMonth[0], nextMonth[1])
        ]

        // Evict the oldest RESOLVED, non-pinned month until at most CACHE_LIMIT remain resident. In-flight
        // months (touched but not yet loaded) and the pinned current-month window are skipped — the former
        // get trimmed once they resolve and re-run this; the latter stay resident for life. Pinned months
        // still count toward `resident`, so the cap bounds total memory either way.
        function evictIfNeeded() {
            let resident = 0, i
            for (i = 0; i < lru.length; i++) { if (monthLoaded[lru[i]]) { resident++ } }
            while (resident > CACHE_LIMIT) {
                let idx = -1
                for (i = 0; i < lru.length; i++) {
                    if (monthLoaded[lru[i]] && PINNED_MONTHS.indexOf(lru[i]) === -1) { idx = i; break }
                }
                if (idx === -1) { break }                          // nothing left but pinned months
                const key = lru[idx]
                lru.splice(idx, 1)
                dropMonth(key)
                resident--
            }
        }

        // Order a `full`-view day's events the same way the server pre-sorts minimal/stacked: highest count
        // first, then name (alphabetical) as a stable tiebreak. The count/name live inside the "name ×N"
        // label (the multiplier is omitted when the count is 1), so parse them back out — mirroring the same
        // ` ×` split renderGrid uses to draw the name/count spans.
        function labelCount(label) {
            const i = label.lastIndexOf(' ×')
            return i !== -1 ? (parseInt(label.slice(i + 2), 10) || 1) : 1
        }
        function labelName(label) {
            const i = label.lastIndexOf(' ×')
            return i !== -1 ? label.slice(0, i) : label
        }
        function fullDaySort(a, b) {
            return (labelCount(b.label) - labelCount(a.label)) || labelName(a.label).localeCompare(labelName(b.label))
        }

        // `force` re-fetches a month even if it's already cached (used by refresh() after a log change).
        // A forced fetch is AUTHORITATIVE for the month: when its data lands it drops the month's old day
        // entries before merging the fresh set, so a day whose last action was removed loses its dot.
        // Crucially it does NOT clear `dayData` up front — the existing dots stay painted until the new
        // data is ready, so the caller can repaint once with no empty-then-filled flash.
        function fetchMonth(y, m, force) {
            const key = monthKey(y, m)
            touch(key)                                            // record access (also on cache hits)
            if (monthPromises[key] && !force) { return monthPromises[key] } // already in-flight or resolved
            const start = `${y  }-${  pad2(m + 1)  }-01`
            const end   = monthEnd(y, m)
            // Each style is fed by the endpoint shaped for it, normalised into a uniform
            // `dayData[date] = [{ colour, label }]` so renderGrid stays feed-agnostic:
            //   • full           → /logs/events: the public, UNCAPPED feed (one event per logged action,
            //                      title already carries the "×N" multiplier). A flat list we group by date.
            //   • minimal/stacked→ /logs/minimal-events: up to four dots/bars per day, pre-sorted.
            const endpoint = (calendarView === 'full') ? '/logs/events' : '/logs/minimal-events'
            const p = fetch(`${endpoint  }?start=${  start  }&end=${  end}`)
                .then(function (r) { return r.json() })
                .then(function (data) {
                    if (force) {                                   // authoritative refresh: clear then merge
                        const prefix = `${key  }-`
                        Object.keys(dayData).forEach(function (d) { if (d.indexOf(prefix) === 0) { delete dayData[d] } })
                    }
                    if (calendarView === 'full') {
                        const touched = {}
                        data.forEach(function (ev) {               // group the flat event list by its date
                            (dayData[ev.start] = dayData[ev.start] || []).push({ colour: ev.backgroundColor, label: ev.title })
                            touched[ev.start] = true
                        })
                        // Highest count first, then name (matches the minimal/stacked server-side ordering).
                        Object.keys(touched).forEach(function (d) {
                            dayData[d].sort(fullDaySort)
                        })
                    } else {
                        data.forEach(function (day) {
                            dayData[day.date] = day.actions.map(function (a) { return { colour: a.colour, label: a.name } })
                        })
                    }
                    monthLoaded[key] = true
                    evictIfNeeded()                               // trim once this month is actually resident
                    return data
                })
                .catch(function (err) {                            // drop so a later view retries
                    delete monthPromises[key]
                    const i = lru.indexOf(key)
                    if (i !== -1) { lru.splice(i, 1) }
                    throw err
                })
            monthPromises[key] = p
            return p
        }

        // Warm SEVERAL months in ONE request instead of a fetch per month. The feeds are range queries
        // (start/end can span any number of months), so the ±PREFETCH_RADIUS window is a single contiguous
        // round-trip rather than 4 — fewer connections, no per-month edge-latency tax, and it stays off the
        // JDBC pool's back the way the day-panel back-fill does. The one response is split back out per
        // month so the per-month cache, LRU and eviction below all behave exactly as the single-month path:
        //   • skip months already resolved/in-flight (deduped via monthPromises);
        //   • fetch the contiguous span covering the rest, but merge ONLY those months — an already-cached
        //     month sitting inside the span (e.g. the visible month, between its neighbours) is ignored, so
        //     the `full` feed never double-appends its events;
        //   • share the one promise across the pending months so a concurrent fetchMonth() dedups against it.
        function fetchMonthsSpan(months) {
            const pending = months.filter(function (ym) { return !monthPromises[monthKey(ym[0], ym[1])] })
            if (pending.length === 0) { return }

            const ordered = pending.slice().sort(function (a, b) { return (a[0] - b[0]) || (a[1] - b[1]) })
            const first = ordered[0], last = ordered[ordered.length - 1]
            const start = `${first[0]  }-${  pad2(first[1] + 1)  }-01`
            const end   = monthEnd(last[0], last[1])

            const pendingKeys = {}
            pending.forEach(function (ym) { pendingKeys[monthKey(ym[0], ym[1])] = true })

            const endpoint = (calendarView === 'full') ? '/logs/events' : '/logs/minimal-events'
            const p = fetch(`${endpoint  }?start=${  start  }&end=${  end}`)
                .then(function (r) { return r.json() })
                .then(function (data) {
                    if (calendarView === 'full') {
                        const touched = {}
                        data.forEach(function (ev) {
                            if (!pendingKeys[ev.start.substring(0, 7)]) { return } // skip non-pending months in the span
                            (dayData[ev.start] = dayData[ev.start] || []).push({ colour: ev.backgroundColor, label: ev.title })
                            touched[ev.start] = true
                        })
                        Object.keys(touched).forEach(function (d) {
                            dayData[d].sort(fullDaySort)
                        })
                    } else {
                        data.forEach(function (day) {
                            if (!pendingKeys[day.date.substring(0, 7)]) { return }
                            dayData[day.date] = day.actions.map(function (a) { return { colour: a.colour, label: a.name } })
                        })
                    }
                    pending.forEach(function (ym) { monthLoaded[monthKey(ym[0], ym[1])] = true })
                    evictIfNeeded()
                    return data
                })
                .catch(function (err) {                            // drop each so a later view retries
                    pending.forEach(function (ym) {
                        const key = monthKey(ym[0], ym[1])
                        delete monthPromises[key]
                        const i = lru.indexOf(key)
                        if (i !== -1) { lru.splice(i, 1) }
                    })
                    throw err
                })

            pending.forEach(function (ym) {
                const key = monthKey(ym[0], ym[1])
                touch(key)                                        // mirror fetchMonth's per-month LRU touch
                monthPromises[key] = p                            // share the one promise for dedup
            })
            return p
        }

        // Warm the surrounding months once the browser is idle, so the next prev/next click reads from
        // cache. Deferred via requestIdleCallback so it never competes with the initial calendar paint,
        // the day-panel load or the stats summary. The whole ±PREFETCH_RADIUS window is one request.
        function prefetchNeighbours() {
            const schedule = window.requestIdleCallback || function (fn) { return setTimeout(fn, 200) }
            schedule(function () {
                const months = []
                for (let d = 1; d <= PREFETCH_RADIUS; d++) {
                    months.push(stepMonth(viewYear, viewMonth, -d))
                    months.push(stepMonth(viewYear, viewMonth,  d))
                }
                const key = monthKey(viewYear, viewMonth)
                const p = fetchMonthsSpan(months)
                // The visible grid's leading/trailing cells belong to the ADJACENT months (e.g. Jun 28–30 in
                // the July grid), so once the neighbours land their dots must be painted in — re-render, but
                // only if the user is still on the same month (they may have navigated away mid-prefetch).
                if (p) { p.then(function () { if (monthKey(viewYear, viewMonth) === key) { renderGrid() } }) }
            })
        }

        function fetchAndRender() {
            const key = monthKey(viewYear, viewMonth)
            // Paint the grid immediately — numbers, today, selection — so the month switch is instant and the
            // page never blocks on the network. The activity dots come from `dayData`: already present for a
            // cached month, or filled in by the re-render below once the fetch lands for an uncached one. Cells
            // always reserve the dot/bar row, so dots appearing later causes no layout shift.
            renderGrid()
            if (monthLoaded[key]) {       // cached → dots are already drawn, nothing more to fetch
                touch(key)               // viewing it counts as use, so it stays hot in the LRU
                prefetchNeighbours()
                return
            }
            fetchMonth(viewYear, viewMonth).then(function () {
                // Re-render with the dots — but only if the user is still on this month (they may have
                // clicked onward mid-fetch, in which case that month's own render already won).
                if (monthKey(viewYear, viewMonth) === key) { renderGrid() }
                prefetchNeighbours()
            })
        }

        // Fit each `full`-style event to its cell. A row is `[dot] [name] [×N]`; when "name ×N" is too wide for
        // the box we degrade in this order, always keeping the dot:
        //   1. truncate the name (CSS ellipsis) while keeping the count;
        //   2. if there's no room even for an ellipsised name beside the count, drop the name (dot + count);
        //   3. if the count alone won't fit, drop it too (dot only).
        // The font is never shrunk. Widths are measured off-DOM via the ink canvas, so the whole grid is fitted
        // with a single layout read (the shared cell width) rather than a reflow per row. Idempotent — safe to
        // re-run (e.g. once the webfont loads, or on resize) against the already-rendered rows.
        function fitFullEvents() {
            const rows = grid.querySelectorAll('.d-full-event')
            if (!rows.length) { return }
            const listW = rows[0].parentNode.clientWidth // .d-full-events content width (identical for every cell)
            if (!listW) { return }                      // not laid out yet — leave the CSS base size
            // Row chrome (see the `.d-full-event*` CSS): margin 2+2, padding 2+2, dot 8, inter-item gap 4.
            const rowContent      = listW - 8               // inside the row's margin + padding
            const textRegionFull  = rowContent - 8 - 8      // dot + two gaps (dot|name, name|count)
            const textRegionCount = rowContent - 8 - 4      // dot + one gap (dot|count)
            const GAP = 4
            const cs = getComputedStyle(rows[0].querySelector('.d-full-event-title'))
            const ellipsisW = measureText('…', cs, FULL_FONT_PX)
            rows.forEach(function (ev) {
                const nameStr  = ev.dataset.fname  || ''
                const countStr = ev.dataset.fcount || ''
                const eventTitleEl = ev.querySelector('.d-full-event-title')
                const countEl  = ev.querySelector('.d-full-event-count')
                if (eventTitleEl) { eventTitleEl.style.display = '' } // reset any prior degradation before re-fitting
                if (countEl) { countEl.style.display = '' }
                const nameW  = nameStr  ? measureText(nameStr,  cs, FULL_FONT_PX) : 0
                const countW = countStr ? measureText(countStr, cs, FULL_FONT_PX) : 0
                if (nameW + countW + ((nameStr && countStr) ? GAP : 0) <= textRegionFull) {
                    return // whole "name ×N" fits — nothing to trim
                }
                if (nameStr && textRegionFull - (countStr ? countW + GAP : 0) >= ellipsisW) {
                    return // room for an ellipsised name beside the count — CSS handles the truncation
                }
                if (eventTitleEl) { eventTitleEl.style.display = 'none' } // drop the name
                if (countStr && countW > textRegionCount && countEl) {
                    countEl.style.display = 'none' // count won't fit either — dot only
                }
            })
        }

        function renderGrid() {
            setCalTitle(titleEl, viewYear, viewMonth)
            grid.innerHTML = ''
            const firstDow = new Date(viewYear, viewMonth, 1).getDay() // 0 = Sunday

            for (let i = 0; i < 42; i++) {
                // JS Date constructor handles month overflow correctly for cells outside the current month.
                const d  = new Date(viewYear, viewMonth, i - firstDow + 1)
                const yr = d.getFullYear(), mo = d.getMonth(), dy = d.getDate()
                const dateStr = `${yr  }-${  pad2(mo + 1)  }-${  pad2(dy)}`

                const isCurrentMonth = (mo === viewMonth && yr === viewYear)
                const isToday        = (dateStr === today)
                const isSelected     = (dateStr === selectedDate)

                const cell = document.createElement('div')
                cell.className = `d-min-cell${ 
                    isCurrentMonth ? '' : ' d-min-other' 
                    }${isToday    ? ' d-min-today'    : '' 
                    }${isSelected ? ' d-min-selected' : ''}`
                cell.dataset.date = dateStr

                // `full` draws a classic month cell: a top-right day number + a vertical list of the day's
                // logged-action events (coloured dot + title). `minimal`/`stacked` draw a centred date
                // circle (ink-centred) plus an activity strip. The number element differs, so centreInk
                // (which measures the circle's glyph) only runs for the circle styles.
                let dateNum = null, dateInk = null
                if (calendarView === 'full') {
                    const top = document.createElement('div')
                    top.className = 'd-full-top'
                    const num = document.createElement('span')
                    num.className = 'd-full-daynum'
                    num.textContent = dy
                    top.appendChild(num)
                    cell.appendChild(top)

                    const evList = document.createElement('div')
                    evList.className = 'd-full-events';
                    (dayData[dateStr] || []).forEach(function (a) {
                        const ev = document.createElement('div')
                        ev.className = 'd-full-event'
                        const dot = document.createElement('span')
                        dot.className = 'd-full-event-dot'
                        dot.style.backgroundColor = a.colour
                        ev.appendChild(dot)
                        // The label is "name ×N" (the multiplier is omitted when the count is 1). Split it into
                        // a truncatable name span and a fixed count span so fitFullEvents can shrink/truncate/
                        // drop the name independently of the ×N. dataset carries the parts for re-fitting.
                        const mIdx     = a.label.lastIndexOf(' ×')
                        const nameStr  = mIdx !== -1 ? a.label.slice(0, mIdx) : a.label
                        const countStr = mIdx !== -1 ? a.label.slice(mIdx + 1) : '' // "×N"
                        const title = document.createElement('span')
                        title.className = 'd-full-event-title'
                        title.textContent = nameStr
                        ev.appendChild(title)
                        if (countStr) {
                            const count = document.createElement('span')
                            count.className = 'd-full-event-count'
                            count.textContent = countStr
                            ev.appendChild(count)
                        }
                        ev.dataset.fname  = nameStr
                        ev.dataset.fcount = countStr
                        evList.appendChild(ev)
                    })
                    cell.appendChild(evList)
                } else {
                    dateNum = document.createElement('span')
                    dateNum.className = 'd-min-date'
                    // The digits live in an inner span so we can translate the TEXT to centre its ink without
                    // moving the circle (the background/ring sits on .d-min-date itself).
                    dateInk = document.createElement('span')
                    dateInk.className = 'd-min-date-ink'
                    dateInk.textContent = dy
                    dateNum.appendChild(dateInk)
                    cell.appendChild(dateNum)

                    // Activity indicator — always appended so cell height never changes.
                    if (calendarView === 'stacked') {
                        const barsEl = document.createElement('div')
                        barsEl.className = 'd-stk-bars';
                        (dayData[dateStr] || []).forEach(function (a) {
                            const bar = document.createElement('span')
                            bar.className = 'd-stk-bar'
                            bar.style.backgroundColor = a.colour
                            barsEl.appendChild(bar)
                        })
                        cell.appendChild(barsEl)
                    } else {
                        const dotRow = document.createElement('div')
                        dotRow.className = 'd-min-dots';
                        (dayData[dateStr] || []).forEach(function (a) {
                            const dot = document.createElement('span')
                            dot.className = 'd-min-dot'
                            dot.style.backgroundColor = a.colour
                            dotRow.appendChild(dot)
                        })
                        cell.appendChild(dotRow)
                    }
                }

                cell.addEventListener('click', (function (ds) {
                    return function () { selectDay(ds) }
                })(dateStr))
                grid.appendChild(cell)
                // Measured once the cell is in the DOM, so getComputedStyle resolves the real font/weight.
                if (dateNum) { centreInk(dateNum, dateInk) }
            }
            if (calendarView === 'full') { fitFullEvents() }
        }

        fetchAndRender() // initial month render + dot fetch; shared chrome (below) wires nav/picker/selection

        // The initial render can run before the Nova webfont is decoded, in which case the canvas measures
        // digit ink with the FALLBACK face — and those offsets are cached, so a mis-centred first paint would
        // persist on every later render (navigation / selection), e.g. "4" sitting off-centre in its circle.
        // Once the fonts are ready, drop the caches and re-centre every rendered cell so the offsets reflect
        // the real glyphs. (No-op for the Standard font setting: ready resolves immediately, re-measuring the
        // same fallback face to the same result.)
        if (document.fonts && document.fonts.ready) {
            document.fonts.ready.then(function () {
                inkCentroidCache = {}
                baselineCache = {}
                circleSize = 0
                grid.querySelectorAll('.d-min-cell').forEach(function (cell) {
                    const circle = cell.querySelector('.d-min-date')
                    const ink = cell.querySelector('.d-min-date-ink')
                    if (circle && ink) { centreInk(circle, ink) }
                })
                // The `full` event fit measures text widths off the same font, so re-fit against the real glyphs.
                fitFullEvents()
            })
        }

        // The `full` event fit is width-dependent, so re-run it when the grid resizes (rotation, window
        // resize, sidebar toggle). Debounced; a no-op when the current style isn't `full` (no event rows).
        let refitTimer = null
        window.addEventListener('resize', function () {
            if (refitTimer) { clearTimeout(refitTimer) }
            refitTimer = setTimeout(function () { if (calendarView === 'full') { fitFullEvents() } }, 150)
        })

        return {
            currentView:  function () { return { year: viewYear, month: viewMonth } },
            goToMonth:    function (y, m) { viewYear = y; viewMonth = m; fetchAndRender() },
            setHighlight: function (dateStr) { selectedDate = dateStr; renderGrid() },
            // Re-fetch the visible month in the BACKGROUND, leaving the current dots/bars on screen, then
            // repaint exactly once when the fresh data lands — so a log change swaps the dots in smoothly
            // instead of blanking them for the duration of the (edge-latency) refetch.
            refresh:      function () {
                const key = monthKey(viewYear, viewMonth)
                fetchMonth(viewYear, viewMonth, true).then(function () {
                    if (monthKey(viewYear, viewMonth) === key) { renderGrid() } // skip if navigated away mid-fetch
                })
            }
        }
    } // end buildGridCalendar

    // ── Build the calendar, then wire the shared chrome against its adapter ──
    // One engine drives every style now (full / minimal / stacked); calendarView only changes how each
    // cell is rendered and which feed fills it, both handled inside buildGridCalendar.
    const cal = buildGridCalendar()

    // Toolbar: month-only moves clear the day selection first (no specific day was chosen), then navigate.
    function navMonths(delta) {
        const v = cal.currentView()
        let m = v.month + delta, y = v.year
        while (m > 11) { m -= 12; y++ }
        while (m < 0)  { m += 12; y-- }
        clearSelection()
        cal.goToMonth(y, m)
    }
    document.getElementById('cal-prev-year').addEventListener('click', function () { navMonths(-12) })
    document.getElementById('cal-prev').addEventListener('click',      function () { navMonths(-1) })
    document.getElementById('cal-next').addEventListener('click',      function () { navMonths(1) })
    document.getElementById('cal-next-year').addEventListener('click', function () { navMonths(12) })
    // 'Today' lives at the bottom of the picker popup: select today (switching month if needed) and close.
    document.getElementById('cal-today').addEventListener('click', function () { selectDay(today); closePop() })

    // ── Month/year picker popup (shared #cal-pop markup) ─────────────────────
    let pickerYear = cal.currentView().year
    const yearLabel  = pop.querySelector('.cal-pop-year')
    const monthsGrid = pop.querySelector('.cal-pop-months')

    // Build the 12 month buttons ONCE; renderPicker only repaints their state + the year field.
    // Rebuilding them on every render would destroy the button a user is mid-click on: the editable
    // year commits on blur, which fires exactly as the pointer goes down on a month, so an innerHTML
    // rebuild there would detach the button and swallow the click. Each handler reads pickerYear at
    // click time, so typing a year then clicking a month navigates to the typed year.
    const monthButtons = CAL_MONTHS_ABBR.map(function (name, i) {
        const b = document.createElement('button')
        b.type = 'button'
        b.textContent = name
        b.className = 'cal-pop-month'
        b.addEventListener('click', function () {
            clearSelection() // picked a month, not a day → drop the previous selection
            cal.goToMonth(pickerYear, i)
            closePop()
        })
        monthsGrid.appendChild(b)
        return b
    })

    function renderPicker() {
        yearLabel.value = pickerYear
        const v = cal.currentView()
        monthButtons.forEach(function (b, i) {
            const isToday    = (pickerYear === todayYear && i === todayMonth) // solid "today" fill
            const isSelected = (pickerYear === v.year && i === v.month)       // brand selection ring
            b.className = `cal-pop-month${
                 isToday ? ' cal-pop-month-today' : ''
                 }${isSelected ? ' cal-pop-month-selected' : ''}`
        })
    }

    pop.querySelectorAll('button[data-y]').forEach(function (btn) {
        btn.addEventListener('click', function () {
            pickerYear += parseInt(btn.dataset.y, 10)
            renderPicker()
        })
    })

    // The year is a typeable field (mirrors the day panel's editable count). Commit on change/Enter:
    // parse, clamp to a sane range, then repaint the month grid for the new year. renderPicker resets
    // the input to the clamped value, so a bad/blank entry silently reverts. Editing the year only
    // moves the picker; the calendar navigates when a month is clicked.
    function commitYear() {
        const y = parseInt(yearLabel.value, 10)
        if (!isNaN(y)) { pickerYear = Math.max(1, Math.min(9999, y)) }
        renderPicker()
    }
    yearLabel.addEventListener('change', commitYear)
    yearLabel.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { e.preventDefault(); commitYear() }
    })

    function openPop(anchor) {
        pickerYear = cal.currentView().year
        renderPicker()
        const a = anchor.getBoundingClientRect()
        const c = calWrap.getBoundingClientRect()
        pop.style.left = `${Math.min(a.left - c.left, calWrap.clientWidth - 230)  }px`
        pop.style.top  = `${a.bottom - c.top  }px`
        pop.classList.remove('hidden')
    }
    function closePop() { pop.classList.add('hidden') }

    document.getElementById('cal-jump').addEventListener('click', function (e) {
        e.stopPropagation()
        if (pop.classList.contains('hidden')) {openPop(this)} else {closePop()}
    })
    document.addEventListener('click', function (e) {
        if (!pop.contains(e.target) && !e.target.closest('#cal-jump')) {closePop()}
    })
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape') {closePop()} })

    // Refresh the active calendar's dots/events after a log MUTATION inside the day panel. Only the
    // increment/decrement/set/delete endpoints (all POSTs) change a day's logged actions; the panel's
    // own GETs — the day-panel load, the Erase confirmation prompt, the Cancel that restores the row —
    // leave the logs untouched. Refreshing on those would needlessly re-fetch and repaint every dot,
    // which reads as a flash of all logged actions across the calendar. So gate on a non-GET verb.
    document.body.addEventListener('htmx:afterRequest', function (e) {
        const verb = e.detail && e.detail.requestConfig && e.detail.requestConfig.verb
        if (verb && verb !== 'get' && e.target && e.target.closest && e.target.closest('#day-logger-panel')) {
            cal.refresh()
            // The live panel was updated inline by the mutation, but the cached snapshot for this day is
            // now stale. Drop it so the next revisit re-fetches the fresh counts via the single-day fetch
            // (the once-per-month back-fill won't re-run, and skips already-cached days anyway).
            if (selectedDate) { delete dayPanelCache[selectedDate] }
        }
    })

    // Open the day panel immediately, without clicking the calendar. Restore the day chosen earlier this
    // working session (retained per-tab via sessionStorage) if there is a valid one; otherwise fall back to
    // today. The format guard is load-bearing: selectedDate is interpolated straight into the
    // /logs/day/<date> fetch URL, so only a well-formed ISO date is accepted. The regex uses \d\d… rather
    // than \d{4} on purpose: Qute would read the {4}/{2} quantifiers as template expressions (see the
    // brace-parsing note in CLAUDE.md) and corrupt the pattern.
    const ISO_DATE = /^\d\d\d\d-\d\d-\d\d$/
    let restoredDate = null
    try { restoredDate = sessionStorage.getItem('diurnal.selectedDate') } catch (e) {}
    selectDay(ISO_DATE.test(restoredDate) ? restoredDate : today)
})

/*
 * Dashboard calendar engine (extracted from dashboard.html so it rides the immutable,
 * content-hashed cache instead of being re-parsed on every dashboard load).
 *
 * Served as /js/dashboard.<hash>.js in production (hashed + `immutable` in the Dockerfile, baked
 * into AppInfo.jsDashboardFile) and /js/dashboard.js in dev. Loaded only on the dashboard, as a
 * classic script at the end of <body>. The three server-injected values it needs — the app's UTC
 * "today", the user's calendar style and the day their week starts on — arrive via the #dashboard-main
 * element's data-today / data-calendar-view / data-week-start attributes (dashboard.html), so this
 * file carries no inline bootstrap and stays a fully static, content-hashed script. Because this is a
 * plain .js file (not a Qute template) the historic `{`-escaping caveats no longer apply here.
 */
document.addEventListener('DOMContentLoaded', function () {
    const _cfg = document.getElementById('dashboard-main').dataset
    const today = _cfg.today
    const calendarView = _cfg.calendarView
    // The day the user's week starts on, already resolved server-side (their "Week starts on" setting, or
    // their language's own convention when they have not set one) and handed over in the browser's own
    // Date#getDay() numbering, 0 = Sunday. The column header above the grid is rotated by the SAME resolved
    // day (DayLabels.weekdayAbbreviations), so nothing here re-derives it.
    const weekStartIndex = parseInt(_cfg.weekStart, 10) || 0
    // The real "today" month, parsed once — the jump popup marks the month containing today with the
    // calendar's solid "today" highlight (distinct from the merely-viewed month, which gets the
    // selected-day ring). Shared by every calendar style's picker.
    const todayYear  = parseInt(today.substring(0, 4), 10)
    const todayMonth = parseInt(today.substring(5, 7), 10) - 1 // 0-indexed

    // The month/year title is emitted with BOTH a full and an abbreviated variant in the DOM; CSS
    // (see the .cal-title media queries) shows whichever fits the screen width, so the single-row
    // toolbar never wraps. Shared by every calendar style. The month names and their 3-letter forms
    // come from Diurnal (app.js), the one place the project spells out "June" → "Jun" — the stats
    // tiles' own date fitting reduces their labels through the same ladder.
    function setCalTitle(el, year, monthIndex) {
        // Slice the plain Latin digits first (a fixed "last 2 characters" is meaningless once
        // transcoded), then localize each form — so the abbreviated year keeps this language's own
        // digit glyphs too, not just the full one (Diurnal.MONTHS_FULL/ABBR are already localized).
        const yr = String(year)
        const yrFull  = window.Diurnal.localizeDigits(yr)
        const yrAbbr  = window.Diurnal.localizeDigits(yr.slice(-2))
        el.innerHTML =
            `<span class="cal-title-month-full">${  window.Diurnal.MONTHS_FULL[monthIndex]  }</span>` +
            `<span class="cal-title-month-abbr">${  window.Diurnal.MONTHS_ABBR[monthIndex]  }</span>` +
            ' ' +
            `<span class="cal-title-year-full">${  yrFull  }</span>` +
            `<span class="cal-title-year-abbr">${  yrAbbr  }</span>`
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

    // Shared with every other page's fetches — see Diurnal.requireSession in app.js for the two shapes
    // an expired session arrives in, and why touching a response body without this check strands the page.
    // Resolved at CALL time, not captured at module init: layout.html loads app.js (which defines it) AFTER
    // the page body, so this script runs first and an up-front reference would capture `undefined`.
    function requireSession(resp) {
        return window.Diurnal.requireSession(resp)
    }

    // The note box lives in its own script (note.js), which loads first. It owns everything about a day's
    // note - the textarea, its caches, the save/undo/clear controls and the drag-resize - and exposes only
    // what the calendar needs: which days have one, and hooks to load, clear and evict them. Absent the
    // panel it is a set of no-ops, so the dashboard works unchanged if the box is ever not rendered.
    const noteBox = window.Diurnal.noteBox

    // ── Per-day HTML fragment cache ──────────────────────────────────────────
    // The day panel and the stats summary both show a server-rendered fragment for the SELECTED day, and
    // both are cached the same way, so both are built from this one factory. Re-opening a day by clicking
    // around the calendar is then instant instead of paying the edge round-trip every time (the public
    // origin sits behind Cloudflare, so each call is a ~250ms+ round-trip).
    //
    // The selected day is fetched on its own for a fast first paint; the REST of its month is then
    // back-filled by a SINGLE bulk request returning every day's HTML in one map, rather than fanning out
    // ~30 concurrent per-day requests (which would exhaust the small JDBC pool).
    //
    // The cache is bounded the same way `dayData` is (see that cache's LRU below): by month,
    // least-recently-used first. Each visited month back-fills ~30 rendered HTML strings, so WITHOUT a cap
    // a session that browses back through years would accumulate megabytes that are never released.
    // MONTH_LIMIT stays well above the working set (the selected month plus the neighbours a user hops
    // between) and touch-on-access keeps the current month at the tail, so a month that's on screen or
    // about to be revisited is never dropped.
    //
    // `dayUrl`/`monthUrl` are the only things that vary between the two users: everything else — eviction,
    // in-flight de-duplication, idle-scheduled back-fill, retry-on-failure — is identical.
    const FRAGMENT_MONTH_LIMIT = 12 // max months of cached fragments retained (mirrors the dayData CACHE_LIMIT)

    function createFragmentCache(dayUrl, monthUrl) {
        const cache      = {} // dateStr -> HTML string (the single-day response body)
        const inflight   = {} // dateStr -> Promise, dedupes concurrent single-day fetches
        const backfilled = {} // "YYYY-MM" -> true once its whole-month back-fill has been requested
        const lru        = [] // "YYYY-MM" keys, least-recently-used first

        // Mark a date's month as most-recently-used, then evict the oldest months' fragments until at most
        // FRAGMENT_MONTH_LIMIT remain resident. Called on every cache access and write.
        function touchMonth(dateStr) {
            const ym = dateStr.substring(0, 7) // "YYYY-MM"
            const i = lru.indexOf(ym)
            if (i !== -1) { lru.splice(i, 1) }
            lru.push(ym)
            while (lru.length > FRAGMENT_MONTH_LIMIT) {
                const stale = lru.shift()
                const prefix = `${stale}-` // "YYYY-MM-" — every date key in that month
                Object.keys(cache).forEach(function (d) { if (d.indexOf(prefix) === 0) { delete cache[d] } })
                delete backfilled[stale] // let a later visit re-fetch the whole month
            }
        }

        return {
            // True once this date's fragment is resident, so a caller can tell an instant swap from one
            // that has to wait on the network (and decide whether to show in-flight feedback).
            has: function (dateStr) { return cache[dateStr] !== undefined },

            get: function (dateStr) {
                if (cache[dateStr] !== undefined) { touchMonth(dateStr); return Promise.resolve(cache[dateStr]) }
                if (inflight[dateStr]) { return inflight[dateStr] }
                const p = fetch(dayUrl(dateStr))
                    .then(requireSession)
                    .then(function (r) { return r.text() })
                    .then(function (html) { cache[dateStr] = html; delete inflight[dateStr]; touchMonth(dateStr); return html })
                    .catch(function (err) { delete inflight[dateStr]; throw err }) // drop so a later view retries
                inflight[dateStr] = p
                return p
            },

            // Back-fill every other day of dateStr's month from one bulk request, once the browser is idle so
            // it never competes with the just-issued load for the selected day. Runs at most once per month,
            // and only fills days NOT already cached — so the selected day (and any day the user has since
            // changed, whose stale entry was dropped) keeps its fresher copy rather than being clobbered by
            // the snapshot.
            backfill: function (dateStr) {
                const ym = dateStr.substring(0, 7) // "YYYY-MM"
                if (backfilled[ym]) { return }
                backfilled[ym] = true
                const schedule = window.requestIdleCallback || function (fn) { return setTimeout(fn, 200) }
                schedule(function () {
                    fetch(monthUrl(ym))
                        .then(requireSession)
                        .then(function (r) { return r.json() })
                        .then(function (fragments) {
                            Object.keys(fragments).forEach(function (d) {
                                if (cache[d] === undefined) { cache[d] = fragments[d] }
                            })
                            touchMonth(dateStr) // whole month now resident — record recency & trim
                        })
                        .catch(function () { delete backfilled[ym] }) // let a later navigation retry
                })
            },

            // Adopt an already-rendered fragment the page shipped inline, so showing that day costs no request.
            seed: function (dateStr, html) { cache[dateStr] = html; touchMonth(dateStr) },

            // Forget ONE day, when only that day's fragment went stale.
            drop: function (dateStr) { delete cache[dateStr] },

            // Forget everything, when a change on one day invalidates every day's fragment.
            clear: function () {
                Object.keys(cache).forEach(function (d) { delete cache[d] })
                Object.keys(inflight).forEach(function (d) { delete inflight[d] })
                Object.keys(backfilled).forEach(function (ym) { delete backfilled[ym] })
                lru.length = 0
            }
        }
    }

    // ── Day panel ────────────────────────────────────────────────────────────
    // We cache the PRISTINE default view (page 1, no search filter) — exactly what a fresh load returns —
    // so a cached swap is indistinguishable from a network one. Invalidation is per-date: a log mutation
    // changes only the day it was made on.
    const dayPanelCache = createFragmentCache(
        function (d) { return `/internal/logs/day/${d}` },
        function (ym) { return `/internal/logs/month/${ym}` },
    )

    // Swap a day's cached/loaded HTML into the panel and wire its HTMX attributes (htmx.process, since
    // we set innerHTML directly rather than going through htmx.ajax). Lifts the in-flight dim.
    //
    // This bypasses htmx's own swap entirely, so none of app.js's htmx:afterSwap-driven passes
    // (locale number grouping/digit localization, figure fitting, the day-panel's own count fields)
    // run on their own here — call each explicitly, the same way the stats-summary panel already does
    // for its own plain-fetch load (see loadStatsSummary below).
    function swapDayPanel(html) {
        if (!dayPanel) {return}
        dayPanel.innerHTML = html
        htmx.process(dayPanel)
        window.Diurnal.formatNumbers(dayPanel)
        window.Diurnal.localizeDigitsIn(dayPanel)
        window.Diurnal.localizeNumInputsIn(dayPanel)
        window.Diurnal.fitFigures(dayPanel)
        dayPanel.style.opacity = ''
    }

    function loadDayPanel(dateStr) {
        if (dayPanel && !dayPanelCache.has(dateStr)) {
            // Cache miss: keep the previous day's actions on screen but dim them while the response is in
            // flight. Blanking the panel (or painting a skeleton) reads as a harsh flash on fast loads;
            // holding the content and fading the opacity makes the switch feel continuous. A cache hit
            // resolves on the next microtask, so it swaps instantly with no dim.
            dayPanel.style.transition = 'opacity 150ms ease'
            dayPanel.style.opacity = '0.45'
        }
        dayPanelCache.get(dateStr).then(function (html) {
            // Only swap if the user is still on this day (they may have clicked onward mid-fetch, or
            // cleared the selection, in which case that action already won).
            if (selectedDate === dateStr) { swapDayPanel(html) }
        })
        dayPanelCache.backfill(dateStr) // back-fill the rest of the month from one bulk request
    }

    // ── Stats summary ────────────────────────────────────────────────────────
    // The summary card under the calendar shows the SELECTED day's most-logged actions, so it follows the
    // selection exactly the way the day panel does. The page ships the initially selected day's card
    // inline, so that one is seeded into the cache below rather than re-fetched.
    //
    // Invalidation is coarser than the day panel's, and deliberately so: the tiles report each action's
    // WHOLE history (streaks, totals, best month), so logging against ANY day changes the figures shown on
    // EVERY day, not just the one that was edited. Dropping one date would leave stale numbers on the rest,
    // so a log mutation clears the whole cache.
    const statsHost  = document.getElementById('stats-summary') // absent when the summary is off in Settings
    const statsCache = createFragmentCache(
        function (d) { return `/internal/stats/summary/${d}` },
        function (ym) { return `/internal/stats/summary-month/${ym}` },
    )

    // Swap a day's card into the host. The figures are server-rendered as bare digits in their fullest
    // form, so the presentation passes that normally run on page load / after an HTMX swap (locale
    // number grouping, digit-only localization, and the responsive shortening of long dates and large
    // counts) have to be re-applied by hand here — this is a plain fetch + innerHTML, which fires none
    // of them.
    function swapStatsSummary(html) {
        if (!statsHost) {return}
        statsHost.innerHTML = html
        window.Diurnal.formatNumbers(statsHost)
        window.Diurnal.localizeDigitsIn(statsHost)
        window.Diurnal.fitFigures(statsHost)
    }

    // `backfill` is false for the post-mutation reload: the cache was just emptied, and re-arming the
    // whole-month request on every increment would fire a bulk fetch per tap. The next day the user selects
    // re-arms it instead.
    function loadStatsSummary(dateStr, backfill) {
        if (!statsHost) {return}
        statsCache.get(dateStr).then(function (html) {
            // Only swap if the user is still on this day (they may have clicked onward mid-fetch).
            if (selectedDate === dateStr) { swapStatsSummary(html) }
        })
        if (backfill) { statsCache.backfill(dateStr) }
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
        loadStatsSummary(dateStr, true)
        noteBox.load(dateStr)
        rememberSelectedDate(dateStr)
    }

    // Deselect the current day and reset the panel to its prompt. Used when only the MONTH changes
    // (arrows / picker), where no specific day was chosen, so leaving the old day's actions on screen
    // would be misleading. Selecting an explicit date (selectDay) does the opposite and keeps it.
    function clearSelection() {
        cal.setHighlight(null)
        if (dayPanel) { dayPanel.style.opacity = ''; dayPanel.innerHTML = dayPanelPlaceholder }
        // The summary is a statement about a specific day, so with no day selected there is nothing to
        // state — empty the card rather than leave the previous day's figures under a fresh month.
        if (statsHost) { statsHost.innerHTML = '' }
        // Likewise the note: with no day selected there is nothing to write against, so the box is emptied
        // and disabled rather than left showing (and accepting edits to) the day that was deselected. Any
        // unsaved draft is kept, so re-selecting that day brings it straight back.
        noteBox.disable()
        forgetSelectedDate()
    }

    // ── Hand-rolled grid (full / minimal / stacked) ──────────────────────────
    // One engine renders all three calendar styles over the same 7×6 month grid; only each cell's
    // contents differ (see renderGrid's `calendarView` branch) and which data feed fills `dayData`
    // (see fetchMonth). `full` draws bordered cells with a day number + a list of logged-action events;
    // `minimal`/`stacked` draw a centred date circle with a dots/bars activity strip.
    // ── Optical text centring (offscreen ink measurement) ────────────────────
    // Pure typography, with no calendar knowledge: it measures painted glyphs and returns offsets, so it
    // works for any element pair. Used by the calendar to centre each date number inside its circle, and
    // to size `full`-style event text.
    //
    // Centring is optical on BOTH axes. flex centres the glyph's ADVANCE box horizontally and its line/em
    // box vertically, but the painted ink doesn't sit centred within those, so a circle/ring around a
    // number looks lopsided. We paint the digits to an offscreen canvas at the same font and measure the
    // painted pixels, then translate the digits to centre them — but the right "centre" differs per axis:
    //   • HORIZONTAL → the ink BOUNDING BOX (so the whitespace gaps left & right are equal, which is
    //     what the eye reads sideways). The digit side-bearings (the "1" glyph especially) otherwise
    //     leave the number off-centre. This is the web analogue of Android's Paint.getTextBounds.
    //     NB centring the horizontal *mass* instead skews mixed-weight numbers (e.g. the light "1" +
    //     heavy "5" in "15" pulls the mass right, so mass-centring leaves a gap on the right).
    //   • VERTICAL → the ink MASS centroid. Digits have no descenders, so bounding-box centring makes
    //     them ride visibly high; centring the mass reads as balanced. The vertical reference is the
    //     text's real baseline, read from the DOM with a zero-height baseline-aligned strut probe
    //     (canvas em-box metrics don't match the browser's line-box placement).
    // Both measurements are cached (by digits+weight+size / weight+size — all calendar day states now share
    // one weight, so the cache key still holds if that ever changes again). A caller re-measuring after a
    // web font swaps in must `reset()` first, since every cached figure was taken against the fallback.
    function createInkMetrics() {
        const canvas = document.createElement('canvas')
        const ctx = canvas.getContext('2d', { willReadFrequently: true })
        let centroidCache = {} // weight|size|text -> { x: bbox centre from advance centre, y: mass from baseline }
        let baselineCache = {} // weight|size      -> baseline offset from the container's top edge (CSS px)
        let boxSize = 0        // the container's height (constant across callers) — read once

        function inkCentroid(cs, text) {
            const key = `${cs.fontWeight}|${cs.fontSize}|${text}`
            let c = centroidCache[key]
            if (c) {return c}
            const fontPx = parseFloat(cs.fontSize) || 12
            const ss = 8                            // supersample for sub-pixel precision
            const box = Math.ceil(fontPx * 3) * ss  // ample square around the digits
            const baseY = box / 2                   // draw the alphabetic baseline at mid-canvas
            canvas.width = box; canvas.height = box
            ctx.font = `${cs.fontWeight} ${fontPx * ss}px ${cs.fontFamily}`
            ctx.textAlign = 'center'           // x origin = glyph advance centre
            ctx.textBaseline = 'alphabetic'
            ctx.fillStyle = '#000'
            ctx.fillText(text, box / 2, baseY)
            const data = ctx.getImageData(0, 0, box, box).data
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
            centroidCache[key] = c
            return c
        }

        function baselineOffset(containerEl, textEl, cs) {
            const key = `${cs.fontWeight}|${cs.fontSize}`
            if (baselineCache[key] !== undefined) {return baselineCache[key]}
            const strut = document.createElement('span')
            strut.style.cssText = 'display:inline-block;width:0;height:0;vertical-align:baseline'
            textEl.appendChild(strut)
            const off = strut.getBoundingClientRect().top - containerEl.getBoundingClientRect().top
            strut.remove()
            baselineCache[key] = off
            return off
        }

        return {
            // Natural width (CSS px) of `text` at the given font, reusing the ink canvas. Independent of
            // layout, so a caller can decide truncation without per-candidate reflows.
            measureText: function (text, cs, fontPx) {
                ctx.font = `${cs.fontWeight} ${fontPx}px ${cs.fontFamily}`
                return ctx.measureText(text).width
            },

            // Translate `textEl` so its painted ink sits optically centred within `containerEl`.
            centre: function (containerEl, textEl) {
                const cs = getComputedStyle(containerEl)
                if (!boxSize) { boxSize = containerEl.getBoundingClientRect().height }
                const c = inkCentroid(cs, textEl.textContent)
                const baseY = baselineOffset(containerEl, textEl, cs)
                const shiftX = -c.x                        // ink bbox centre -> container centre (equal whitespace)
                const shiftY = boxSize / 2 - (baseY + c.y) // ink mass centroid -> container centre
                textEl.style.transform = `translate(${shiftX.toFixed(3)}px,${shiftY.toFixed(3)}px)`
            },

            // Discard every cached measurement, so the next call re-measures against the current font.
            reset: function () { centroidCache = {}; baselineCache = {}; boxSize = 0 }
        }
    }

    function buildGridCalendar() {
        const grid      = document.getElementById('d-min-grid')
        let viewYear  = parseInt(today.substring(0, 4), 10)
        let viewMonth = parseInt(today.substring(5, 7), 10) - 1 // 0-indexed
        const dayData   = {} // date string -> array of { colour, label } (one per logged action that day);
                            // ACCUMULATES across months (keys are full dates, so months never collide) and
                            // acts as the month cache. `label` is only rendered by the `full` style; the
                            // dots/bars read `colour`. Filled (and normalised per feed) by fetchMonth.

        // Optical centring of the date numbers, and the text width measurements the `full` style's event
        // fitting needs (see fitFullEvents). The font there is fixed; keep FULL_FONT_PX in sync with
        // `.d-cal-full .d-full-event`'s font-size (0.7rem) so the width measurements match what's painted.
        const ink = createInkMetrics()
        const rootPx = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16
        const FULL_FONT_PX = 0.70 * rootPx

        // ── Month data cache (LRU) & background prefetch ────────────────────────────
        // Each month's dots are fetched once and merged into `dayData`; `monthPromises` dedupes in-flight
        // and resolved fetches, and `monthLoaded` flags a month whose data is already in `dayData` so we
        // can render it WITHOUT a network wait. This is what makes month navigation feel instant: the public
        // origin sits behind Cloudflare, so every `/internal/logs/minimal-events` call is a ~250ms+ edge round-trip.
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

        // The day's NOTES ride this same cache, because the calendar paints a green day number for every day
        // that has one — but with their OWN promise map, loaded flag and radius, because their radius differs.
        // (A single shared flag could not say "this month has its events but not yet its notes", which is
        // exactly the state a month two clicks away is in.) Everything subtle stays shared: the `lru` recency
        // list, CACHE_LIMIT, PINNED_MONTHS, evictIfNeeded and dropMonth, so "keep the most recent 12" and the
        // pinned current-month window apply to notes for free and cannot drift from the events side.
        const notePromises    = {} // "YYYY-MM" -> Promise (in-flight or resolved) for that month's notes
        const monthNotesLoaded = {} // "YYYY-MM" -> true once its notes have been merged into the note cache
        // Deliberately narrower than PREFETCH_RADIUS: a month of note PROSE is far heavier than a month of
        // dots, and the odds of jumping two months out before reading anything are low. A month beyond this
        // window simply fetches its notes when it becomes the visible month (see fetchAndRender).
        const NOTE_PREFETCH_RADIUS = 1

        function monthKey(y, m) { return `${y  }-${  pad2(m + 1)}` }

        function feedEndpoint() {
            return (calendarView === 'full') ? '/api/v1/logs/events' : '/internal/logs/minimal-events'
        }

        // The full view reads the public /api/v1 feed, whose anonymous challenge is a plain 401 (no
        // redirect for a programmatic client) — so an expired session must be turned into the /login
        // navigation the internal endpoints get via their 302.
        function feedJson(r) {
            return requireSession(r).json()
        }

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

        // Forget a month entirely: its dots, its notes, both cached fetches and both loaded flags. Eviction is
        // symmetric on purpose — a half-evicted month would keep answering "loaded" for one of the two.
        function dropMonth(key) {
            const prefix = `${key  }-` // "2026-06-" — every date key in that month
            Object.keys(dayData).forEach(function (d) { if (d.indexOf(prefix) === 0) { delete dayData[d] } })
            noteBox.dropMonth(key)
            delete monthPromises[key]
            delete monthLoaded[key]
            delete notePromises[key]
            delete monthNotesLoaded[key]
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
        // A month counts as resident when EITHER its events or its notes are loaded: the two radii mean a month
        // can legitimately hold one without the other, and counting only the events side would let the notes of
        // an unbounded number of months accumulate outside the cap.
        function isResident(key) { return Boolean(monthLoaded[key] || monthNotesLoaded[key]) }

        function evictIfNeeded() {
            let resident = 0, i
            for (i = 0; i < lru.length; i++) { if (isResident(lru[i])) { resident++ } }
            while (resident > CACHE_LIMIT) {
                let idx = -1
                for (i = 0; i < lru.length; i++) {
                    if (isResident(lru[i]) && PINNED_MONTHS.indexOf(lru[i]) === -1) { idx = i; break }
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
            //   • full           → /api/v1/logs/events: the public, UNCAPPED feed (one event per logged
            //                      action, title already carries the "×N" multiplier). A flat list we
            //                      group by date.
            //   • minimal/stacked→ /internal/logs/minimal-events: up to four dots/bars per day, pre-sorted.
            const p = fetch(`${feedEndpoint()  }?start=${  start  }&end=${  end}`)
                .then(feedJson)
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

        // ── Notes: the same month cache, its own flags ───────────────────────────
        // One range request per contiguous span, merged month by month so the per-month flags, the shared LRU
        // and eviction all behave exactly as the events side. `pendingKeys` filters the response the same way
        // the events span does, so an already-cached month sitting inside a span is never merged twice.
        function fetchNoteSpan(months, force) {
            const pending = months.filter(function (ym) {
                return force || !notePromises[monthKey(ym[0], ym[1])]
            })
            if (pending.length === 0) { return null }

            const ordered = pending.slice().sort(function (a, b) { return (a[0] - b[0]) || (a[1] - b[1]) })
            const first = ordered[0], last = ordered[ordered.length - 1]
            const start = `${first[0]  }-${  pad2(first[1] + 1)  }-01`
            const end   = monthEnd(last[0], last[1])

            const pendingKeys = pending.map(function (ym) { return monthKey(ym[0], ym[1]) })

            const p = fetch(`/internal/notes?start=${  start  }&end=${  end}`)
                .then(feedJson)
                .then(function (byDate) {
                    noteBox.mergeMonths(byDate, pendingKeys, force)
                    pendingKeys.forEach(function (key) { monthNotesLoaded[key] = true })
                    evictIfNeeded()
                    return byDate
                })
                .catch(function (err) {                            // drop each so a later view retries
                    pendingKeys.forEach(function (key) {
                        delete notePromises[key]
                        const i = lru.indexOf(key)
                        if (i !== -1) { lru.splice(i, 1) }
                    })
                    throw err
                })

            pendingKeys.forEach(function (key) {
                touch(key)                                        // mirror the events side's per-month LRU touch
                notePromises[key] = p                             // share the one promise for dedup
            })
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

            const p = fetch(`${feedEndpoint()  }?start=${  start  }&end=${  end}`)
                .then(feedJson)
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
                const notePrefetchMonths = []
                for (let d = 1; d <= PREFETCH_RADIUS; d++) {
                    months.push(stepMonth(viewYear, viewMonth, -d))
                    months.push(stepMonth(viewYear, viewMonth,  d))
                    if (d <= NOTE_PREFETCH_RADIUS) {
                        notePrefetchMonths.push(stepMonth(viewYear, viewMonth, -d))
                        notePrefetchMonths.push(stepMonth(viewYear, viewMonth,  d))
                    }
                }
                const key = monthKey(viewYear, viewMonth)
                // Two independent spans, because the radii differ. The notes span covers three months but
                // merges only the two pending neighbours — the visible month between them is already cached.
                const notesP = fetchNoteSpan(notePrefetchMonths, false)
                if (notesP) {
                    notesP.then(function () {
                        if (monthKey(viewYear, viewMonth) === key) { renderGrid() }
                    }).catch(function () {})
                }
                const p = fetchMonthsSpan(months)
                // The visible grid's leading/trailing cells belong to the ADJACENT months (e.g. Jun 28–30 in
                // the July grid), so once the neighbours land their dots must be painted in — re-render, but
                // only if the user is still on the same month (they may have navigated away mid-prefetch).
                if (p) { p.then(function () { if (monthKey(viewYear, viewMonth) === key) { renderGrid() } }) }
            })
        }

        // Two-sided: the events and the notes of a month load independently (different prefetch radii), so a
        // month can arrive here with one side cached and the other not — which is exactly the state of a month
        // two clicks out, whose events the ±2 prefetch warmed but whose notes the ±1 prefetch did not. Fetch
        // whichever side is missing, and repaint once each lands.
        function fetchAndRender() {
            const key = monthKey(viewYear, viewMonth)
            // Paint the grid immediately — numbers, today, selection — so the month switch is instant and the
            // page never blocks on the network. The activity dots come from `dayData`: already present for a
            // cached month, or filled in by the re-render below once the fetch lands for an uncached one. Cells
            // always reserve the dot/bar row, so dots appearing later causes no layout shift.
            renderGrid()

            const pending = []
            if (monthLoaded[key]) {
                touch(key)               // viewing it counts as use, so it stays hot in the LRU
            } else {
                pending.push(fetchMonth(viewYear, viewMonth))
            }
            if (!monthNotesLoaded[key]) {
                const notesP = fetchNoteSpan([[viewYear, viewMonth]], false)
                if (notesP) { pending.push(notesP.catch(function () {})) }
            }

            if (pending.length === 0) {  // both sides cached → nothing to fetch, everything already drawn
                prefetchNeighbours()
                return
            }
            // One repaint once BOTH land, rather than one per side: the visible month's dots and its green
            // note numbers then appear together instead of flickering in one after the other.
            Promise.all(pending).then(function () {
                // Re-render — but only if the user is still on this month (they may have clicked onward
                // mid-fetch, in which case that month's own render already won).
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
            const ellipsisW = ink.measureText('…', cs, FULL_FONT_PX)
            rows.forEach(function (ev) {
                const nameStr  = ev.dataset.fname  || ''
                const countStr = ev.dataset.fcount || ''
                const eventTitleEl = ev.querySelector('.d-full-event-title')
                const countEl  = ev.querySelector('.d-full-event-count')
                if (eventTitleEl) { eventTitleEl.style.display = '' } // reset any prior degradation before re-fitting
                if (countEl) { countEl.style.display = '' }
                const nameW  = nameStr  ? ink.measureText(nameStr,  cs, FULL_FONT_PX) : 0
                const countW = countStr ? ink.measureText(countStr, cs, FULL_FONT_PX) : 0
                if (nameW + countW + ((nameStr && countStr) ? GAP : 0) <= textRegionFull) {
                    delete ev.dataset.tipFull    // whole "name ×N" fits — nothing to trim, nothing to explain
                    return
                }
                // Past this point the row cannot show its label in full, however it degrades below — so
                // hand the whole label to the hover/long-press tooltip (`data-tip-full`, app.js). This
                // routine ASSERTS the clipping rather than leaving it to be measured, because two of the
                // three degradations hide the name outright: there would be nothing left in the row for a
                // measurement to catch. Idempotent with the branch above, since re-fitting re-decides.
                ev.dataset.tipFull = countStr ? `${nameStr} ${countStr}` : nameStr
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
            // How many leading cells the month needs: the distance from the user's first day of the week to
            // the weekday the 1st falls on (+7 % 7 keeps it non-negative when the month starts BEFORE that day).
            const firstDow = (new Date(viewYear, viewMonth, 1).getDay() - weekStartIndex + 7) % 7

            for (let i = 0; i < 42; i++) {
                // JS Date constructor handles month overflow correctly for cells outside the current month.
                const d  = new Date(viewYear, viewMonth, i - firstDow + 1)
                const yr = d.getFullYear(), mo = d.getMonth(), dy = d.getDate()
                const dateStr = `${yr  }-${  pad2(mo + 1)  }-${  pad2(dy)}`

                const isCurrentMonth = (mo === viewMonth && yr === viewYear)
                const isToday        = (dateStr === today)
                const isSelected     = (dateStr === selectedDate)

                const cell = document.createElement('div')
                // d-note-day paints the day NUMBER green (see app.css). It sits on the shared cell, so all
                // three calendar styles get it from this one line.
                cell.className = `d-min-cell${ 
                    isCurrentMonth ? '' : ' d-min-other' 
                    }${isToday    ? ' d-min-today'    : '' 
                    }${isSelected ? ' d-min-selected' : '' 
                    }${noteBox.hasNote(dateStr) ? ' d-note-day' : ''}`
                cell.dataset.date = dateStr

                // `full` draws a classic month cell: a top-right day number + a vertical list of the day's
                // logged-action events (coloured dot + title). `minimal`/`stacked` draw a centred date
                // circle (ink-centred) plus an activity strip. The number element differs, so the optical
                // centring (which measures the circle's glyph) only runs for the circle styles.
                let dateNum = null, dateInk = null
                if (calendarView === 'full') {
                    const top = document.createElement('div')
                    top.className = 'd-full-top'
                    const num = document.createElement('span')
                    num.className = 'd-full-daynum'
                    num.textContent = window.Diurnal.localizeDigits(dy)
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
                        // "×N" - localize the digit now (both the displayed span and the dataset used
                        // for re-fitting, so a later re-measurement sees the same glyphs actually on
                        // screen, not the server's original Latin digit).
                        const countStr = mIdx !== -1 ? window.Diurnal.localizeDigits(a.label.slice(mIdx + 1)) : ''
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
                    dateInk.textContent = window.Diurnal.localizeDigits(dy)
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
                if (dateNum) { ink.centre(dateNum, dateInk) }
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
                ink.reset()
                grid.querySelectorAll('.d-min-cell').forEach(function (cell) {
                    const circle = cell.querySelector('.d-min-date')
                    const inkEl = cell.querySelector('.d-min-date-ink')
                    if (circle && inkEl) { ink.centre(circle, inkEl) }
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
            },
            // Ensure a day's month has its notes cached, for the note box's own read. Resolves immediately
            // when the month is already resident, so switching between cached days costs no request at all.
            ensureNotes: function (dateStr) {
                const y = parseInt(dateStr.substring(0, 4), 10)
                const m = parseInt(dateStr.substring(5, 7), 10) - 1
                const key = monthKey(y, m)
                if (monthNotesLoaded[key]) { return Promise.resolve() }
                // A fetch for this month may already be IN FLIGHT — selecting a day in another month calls
                // goToMonth (which starts one) immediately before the note box's own read. fetchNoteSpan
                // dedupes against it and hands back nothing to wait on, so waiting must be on the pending
                // promise itself: resolving straight away instead repaints the box from a cache that has not
                // arrived, and nothing ever repaints it again — an empty note box on a day that has one.
                const inFlight = notePromises[key]
                if (inFlight) { return inFlight.then(function () { renderGrid() }) }
                const p = fetchNoteSpan([[y, m]], false)
                return p ? p.then(function () { renderGrid() }) : Promise.resolve()
            },
            // Repaint after the note box writes or clears a note, so the day's green number appears or goes
            // immediately. The cache was updated in place by the caller, so there is nothing to re-fetch.
            noteChanged: function () { renderGrid() }
        }
    } // end buildGridCalendar

    // ── Build the calendar, then wire the shared chrome against its adapter ──
    // One engine drives every style now (full / minimal / stacked); calendarView only changes how each
    // cell is rendered and which feed fills it, both handled inside buildGridCalendar.
    const cal = buildGridCalendar()
    // The note box drives two things on the calendar — making sure a day's month has its notes cached, and
    // repainting the green day markers after a save — so it is handed the adapter as soon as it exists.
    noteBox.bindCalendar(cal)

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
    const monthButtons = window.Diurnal.MONTHS_ABBR.map(function (name, i) {
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
        // .js-num-input (app.js) only auto-localizes a field's value ONCE (idempotent, so an htmx swap
        // never re-shows stale digits) - this direct `.value =` assignment runs on every open/nav/commit,
        // so it must localize explicitly, the same way the day panel's manually-swapped innerHTML does.
        yearLabel.value = window.Diurnal.localizeDigits(String(pickerYear))
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
        // yearLabel.value may hold this language's own digit glyphs (typed, or left over from
        // renderPicker's own display) - parseInt only ever understands plain Latin ones.
        const y = parseInt(window.Diurnal.delocalizeDigits(yearLabel.value), 10)
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
            if (selectedDate) { dayPanelCache.drop(selectedDate) }
            // The summary caches whole-history figures, so a change on THIS day moves the numbers shown on
            // every other day too — the whole cache goes, not just this date (see the cache's note above).
            // The visible card is reloaded straight away; the month back-fill re-arms on the next day the
            // user selects, so a run of increments doesn't fire a bulk fetch per tap.
            statsCache.clear()
            if (selectedDate) { loadStatsSummary(selectedDate, false) }
        }
    })

    // Open the day panel immediately, without clicking the calendar. An explicit ?date= in the URL wins —
    // that is a deliberate request to land on a specific day, sent by a notes-page search result (/?date=…)
    // so the note found there can be read beside the actions logged against it. With no such parameter,
    // restore the day chosen earlier this working session (retained per-tab via sessionStorage); failing
    // that, fall back to today. selectDay() then persists whichever was used, so the choice survives moving
    // around the app exactly as a click on the calendar would.
    // The format guard is load-bearing, and doubly so for the URL: selectedDate is interpolated straight
    // into the /internal/logs/day/<date> fetch URL, so only a well-formed ISO date is accepted — anything
    // else is ignored rather than sent. The regex uses \d\d… rather than \d{4} on purpose: Qute would read
    // the {4}/{2} quantifiers as template expressions (see the brace-parsing note in CLAUDE.md) and corrupt
    // the pattern.
    const ISO_DATE = /^\d\d\d\d-\d\d-\d\d$/
    let restoredDate = null
    try { restoredDate = new URLSearchParams(window.location.search).get('date') } catch (e) {}
    if (ISO_DATE.test(restoredDate)) {
        // Consume it: the deep link is a one-shot instruction, not a state the page should keep re-applying.
        // Left in the address bar it would out-rank the session's own selection on every reload, so clicking
        // around the calendar and refreshing would snap back to whatever day the search result named.
        try { window.history.replaceState(null, '', window.location.pathname) } catch (e) {}
    } else {
        try { restoredDate = sessionStorage.getItem('diurnal.selectedDate') } catch (e) {}
    }

    // The page already carries the server-rendered summary for the day it was rendered for (today), so seed
    // the cache with it: opening the dashboard on today then costs no summary request at all, and a
    // restored other day simply misses and fetches its own.
    if (statsHost && statsHost.dataset.summaryDate) {
        statsCache.seed(statsHost.dataset.summaryDate, statsHost.innerHTML)
    }

    // The note box seeds its own cache from the inline content the page shipped (see note.js).

    selectDay(ISO_DATE.test(restoredDate) ? restoredDate : today)
})

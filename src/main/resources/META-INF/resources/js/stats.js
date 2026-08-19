/*
 * Stats page behaviour: the per-action frequency graph dialog.
 *
 * Served as /js/stats.<hash>.js in production (hashed + `immutable` in the Dockerfile, baked into
 * AppInfo.jsStatsFile) and /js/stats.js in dev. Loaded only on the stats page, as a classic script
 * at the end of <body>, after the shared /js/app.js.
 *
 * The chart itself is rendered by the server (partials/stats-chart.html) — this file only decides
 * WHICH actions and window are showing and swaps the fragment in, so there is no charting code in the
 * browser and no second copy of the bar/caption wording to keep in sync with the Qute template.
 *
 * There is deliberately no client-side copy of the current selection beyond `subjectId`: the period,
 * the window and the compared actions are all read back off the rendered fragment (the server echoes
 * them onto .chart-wrap as data-chart-shown-*), so every control continues from what is actually on
 * screen and the server stays the single authority on what a valid selection is.
 */

const modal = document.getElementById('stats-chart-modal')
const body = document.getElementById('stats-chart-body')
const title = document.getElementById('stats-chart-title')

let subjectId = null

function currentWrap() {
    return body.querySelector('.chart-wrap')
}

// The comparison actions currently drawn, as an array of ids ([] when only the primary is charted).
function shownCompare() {
    const wrap = currentWrap()
    const raw = wrap ? wrap.dataset.chartShownCompare : ''
    return raw ? raw.split(',') : []
}

// Fetches and swaps one chart fragment. Every argument is optional: omitting `period`/`at` lets the
// server pick its defaults (a month window containing today), which is what the first open does.
function load(period, at, compare) {
    if (!subjectId) {return}
    const params = new URLSearchParams()
    if (period) {params.set('period', period)}
    if (at) {params.set('at', at)}
    ;(compare || []).forEach(function (id) {params.append('compare', id)})
    const query = params.toString()

    fetch(`/internal/stats/chart/${subjectId}${query ? `?${query}` : ''}`, {headers: {'X-Requested-With': 'XMLHttpRequest'}})
        .then(function (response) {
            if (response.status === 401 || response.redirected) {
                window.location.href = '/login'
                return null
            }
            return response.ok ? response.text() : null
        })
        .then(function (html) {
            if (html === null) {return}
            body.innerHTML = html
            // The swap is a plain innerHTML write, not an htmx one, so the passes that normally run on
            // htmx:afterSwap have to be re-run by hand (same as the dashboard's stats-summary card):
            // the shared figure formatting, digit-only localization (the chart's axis ticks and hover
            // tooltips), and htmx's own wiring for the picker's search box — which arrives inside this
            // fragment and would otherwise never be bound.
            window.Diurnal.formatNumbers(body)
            window.Diurnal.localizeDigitsIn(body)
            window.Diurnal.fitFigures(body)
            window.htmx.process(body)
        })
}

function open(button) {
    subjectId = button.dataset.chartSubject
    title.textContent = button.dataset.chartName
    body.innerHTML = ''
    modal.classList.remove('hidden')
    load(null, null, [])
}

function close() {
    modal.classList.add('hidden')
    body.innerHTML = ''
    subjectId = null
}

// Opening is delegated off document.body: the stats cards are re-rendered by HTMX on every
// pagination step, so a listener bound to each button directly would be lost on the first page
// change.
document.body.addEventListener('click', function (event) {
    const opener = event.target.closest('[data-chart-subject]')
    if (opener) {
        open(opener)
    }
})

document.getElementById('stats-chart-close').addEventListener('click', close)

// Clicking the backdrop (but not the panel) closes, matching the Escape key below.
modal.addEventListener('click', function (event) {
    if (event.target === modal) {
        close()
    }
})

document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape' && !modal.classList.contains('hidden')) {
        close()
    }
})

// Translates the window currently on screen into the equivalent window of the period being switched
// to, so a flip stays where the user was looking: March 2025 → Year lands on 2025, and 2025 → Month
// lands on January 2025. A key that is already the right shape is kept as-is.
function reanchor(period, shownAt) {
    if (!shownAt) {return null}
    if (period === 'year') {return shownAt.slice(0, 4)}
    return shownAt.length === 4 ? `${shownAt}-01` : shownAt
}

// Every control lives inside the swapped fragment, so they are all delegated off the stable body
// wrapper rather than bound per render. Each one re-reads the rest of the selection off the fragment
// and re-fetches, so changing one thing never resets another.
body.addEventListener('click', function (event) {
    const periodButton = event.target.closest('button[data-chart-period]')
    if (periodButton) {
        const wrap = currentWrap()
        const period = periodButton.dataset.chartPeriod
        load(period, reanchor(period, wrap ? wrap.dataset.chartShownAt : null), shownCompare())
        return
    }

    const stepButton = event.target.closest('button[data-chart-at]')
    if (stepButton && !stepButton.disabled) {
        const wrap = currentWrap()
        load(wrap ? wrap.dataset.chartShownPeriod : null, stepButton.dataset.chartAt, shownCompare())
        return
    }

    // "Compare to..." only reveals the picker; the list inside it was rendered with the chart, so
    // there is nothing to fetch until the user types (which htmx handles) or picks (below).
    const compareToggle = event.target.closest('[data-chart-compare-open]')
    if (compareToggle) {
        const panel = document.getElementById('chart-compare-panel')
        const opening = panel.classList.contains('hidden')
        panel.classList.toggle('hidden', !opening)
        compareToggle.setAttribute('aria-expanded', String(opening))
        if (opening) {
            document.getElementById('chart-candidate-search').focus()
        }
        return
    }

    const addButton = event.target.closest('[data-chart-add]')
    if (addButton) {
        const wrap = currentWrap()
        load(wrap.dataset.chartShownPeriod, wrap.dataset.chartShownAt, shownCompare().concat(addButton.dataset.chartAdd))
        return
    }

    const removeButton = event.target.closest('[data-chart-remove]')
    if (removeButton) {
        const wrap = currentWrap()
        const dropped = removeButton.dataset.chartRemove
        load(wrap.dataset.chartShownPeriod, wrap.dataset.chartShownAt, shownCompare().filter(function (id) {return id !== dropped}))
    }
})

/*
 * Shared front-end behaviour for every page (extracted from layout.html so it rides the
 * immutable, content-hashed cache instead of being re-parsed on every no-cache navigation).
 *
 * Served as /js/app.<hash>.js in production (hashed + `immutable` in the Dockerfile, baked into
 * AppInfo.jsAppFile) and /js/app.js in dev. Loaded as a classic script at the end of <body>, so
 * the document is fully parsed when it runs — the same guarantee the inline blocks relied on
 * (e.g. Diurnal.formatNumbers(document.body) below expects a parsed body). Block ORDER matters:
 * the data-validate handler must register before the data-ajax-submit handler (both at document
 * level) so the latter sees the former's preventDefault.
 *
 * NOTE: the FOUC-critical window.Diurnal.applyTheme('{theme}') stays inline in <head> — it must
 * run before the stylesheet loads and carries a server-injected value, so it cannot be hashed.
 */

// Single shared namespace for the helpers reused across this file and the page scripts
// (settings.js, dashboard.js).
window.Diurnal = window.Diurnal || {}

// The resolved app language (not the browser's own locale - see the comment on
// formatNumber below for why that distinction matters), read off <html lang> - every page
// already renders it there (layout.html), from the same User.language/Accept-Language
// resolution the server-side AppMessages bundle uses. Every locale-aware browser API below
// (Intl.DateTimeFormat, toLocaleString) is driven by this ONE constant rather than each
// call site reading document.documentElement.lang for itself.
window.Diurnal.lang = document.documentElement.lang || 'en-GB'

// m:ss, clamped so it can NEVER render a negative value. Shared by the two live counters below (the
// lockout countdown ticking DOWN to an expiry, the presence counter ticking UP from a last-seen), which
// render the same clock and must not drift into two spellings of it.
window.Diurnal.formatClock = function (totalSeconds) {
    const s = totalSeconds > 0 ? totalSeconds : 0
    const mins = Math.floor(s / 60)
    const secs = s % 60
    const clock = `${(mins < 10 ? '0' : '') + mins  }:${  secs < 10 ? '0' : ''  }${secs}`
    // localizeDigits is defined further down this file, but only ever CALLED here later (async, off a
    // setInterval tick) - by then the whole file has finished executing, so the forward reference is safe.
    return window.Diurnal.localizeDigits(clock)
}

// The ONE place the inline error-banner markup is built client-side, mirroring
// partials/banner.html (and the Java-built HTMX banners) — a keep-in-sync pair, see
// .claude/UI_PATTERNS.md. The message is NOT escaped here: callers pass trusted literals, or
// escape user content themselves (the form validator below).
//
// partials/banner.html gets its digit-localization/bidi-ordering via the shared `.js-digits`/
// `.js-phrase` DOM passes (registered on htmx:afterSwap), but this markup is inserted by a raw
// `slot.innerHTML =` write - one of the "plain fetch + innerHTML" paths that bypasses htmx's swap
// event entirely (see the note in dashboard.js's swapDayPanel). Rather than remembering to call the
// DOM passes by hand at every one of this helper's several call sites, localizeDigits runs on the
// MESSAGE STRING directly here (this function already has it as a plain JS string, before it ever
// becomes DOM) - `.js-phrase` (CSS-only, no JS pass needed) still covers the bidi-ordering half.
window.Diurnal.bannerHtml = function (message) {
    return `<div class="banner banner-error js-phrase">${  window.Diurnal.localizeDigits(message)  }</div>`
}

// True when every [required] field in the form holds a non-blank value. Shared by the
// data-disable-until-complete controller and the lockout countdown below.
window.Diurnal.requiredFilled = function (form) {
    return Array.prototype.every.call(form.querySelectorAll('[required]'), function (field) {
        return field.value.trim() !== ''
    })
}

// Guard every fetch against an expired session. A session that has gone shows up in TWO different shapes,
// because the two namespaces challenge differently (see CLAUDE.md):
//   • /api/v1/* answers 401 — no redirect, which is the right answer for a programmatic client;
//   • /internal/* answers 302 to the login PAGE, and fetch FOLLOWS redirects, so the response arrives as
//     the login HTML with status 200 and a url of /login.
// The second shape is the damaging one: a JSON caller throws a parse error into its own retry path (leaving
// the page sitting there, silently never loading), and an HTML caller swaps the entire login page into
// whatever element it was filling. Run every response through this before touching its body.
window.Diurnal.requireSession = function (resp) {
    if (resp.status === 401 || (resp.redirected && new URL(resp.url).pathname === '/login')) {
        window.location.assign('/login')
        throw new Error('session expired')
    }
    return resp
}

// POST a form via fetch as a URL-encoded body — the shared submission core for every
// fetch-submitted form (the login/register cards below, the settings password steps). fetch (not
// htmx) keeps expected, handled 4xx outcomes off the console — htmx unsuppressably console.errors
// every 4xx. Redirects are followed, so callers can inspect resp.url to tell success from failure.
window.Diurnal.postForm = function (form, accept) {
    return fetch(form.action, {
        method: 'POST',
        body: new URLSearchParams(new FormData(form)),
        headers: { 'Accept': accept || 'text/html' },
        redirect: 'follow'
    })
}

// An editable row carries both a view state (elements marked [data-dt-view]) and a hidden
// edit state ([data-dt-edit]). Entering/leaving edit mode is a pure client-side toggle —
// Save submits the row's form, Cancel just restores the view. Shared by every editable table.
window.dtStartEdit = function (row) {
    if (!row) {return}
    row.classList.add('dt-row-highlight', 'dt-row-edit')
    row.querySelectorAll('[data-dt-view]').forEach(function (el) { el.classList.add('hidden') })
    row.querySelectorAll('[data-dt-edit]').forEach(function (el) { el.classList.remove('hidden') })
}
window.dtCancelEdit = function (row) {
    if (!row) {return}
    row.classList.remove('dt-row-highlight', 'dt-row-edit')
    row.querySelectorAll('form').forEach(function (f) { f.reset() })   // drop unsaved input
    row.querySelectorAll('[data-dt-edit]').forEach(function (el) { el.classList.add('hidden') })
    row.querySelectorAll('[data-dt-view]').forEach(function (el) { el.classList.remove('hidden') })
}
// Leave edit mode WITHOUT resetting the form — used on Save. Save submits the row's form over the
// network and then htmx swaps in the server-rendered (view-state) row. Without this, the row sits
// highlighted and expanded through the whole round-trip and then jars straight into plain view when
// the response lands — the "flash". Exiting edit mode the instant the request starts makes Save feel
// as immediate as Cancel; the swap that follows just updates the (now already view-state) content.
// The form is NOT reset (its values are mid-flight to the server) and stays in the DOM, so the
// request already gathered its parameters — see the htmx:beforeRequest wiring below.
window.dtFinishEdit = function (row) {
    if (!row) {return}
    row.classList.remove('dt-row-highlight', 'dt-row-edit')
    row.querySelectorAll('[data-dt-edit]').forEach(function (el) { el.classList.add('hidden') })
    row.querySelectorAll('[data-dt-view]').forEach(function (el) { el.classList.remove('hidden') })
}
// True when any form-associated control differs from its server-rendered default value. Reads the
// live controls against their defaults (defaultValue / defaultChecked / option.defaultSelected), so
// no baseline snapshot is needed: the edit inputs render with `value=`/`selected` attributes set to
// the current values, which are exactly those defaults. `form.elements` includes controls linked via
// the `form=` attribute (e.g. the Actions colour picker, which lives outside its <form>), so they are
// checked too. Colours compare case-insensitively — the native colour picker always reports lowercase
// `value`, but a stored uppercase hex would otherwise read as a spurious change.
window.dtFormDirty = function (form) {
    if (!form) {return true}
    const els = form.elements
    for (let i = 0; i < els.length; i++) {
        const el = els[i]
        if (!el.name) {continue}
        if (el.type === 'checkbox' || el.type === 'radio') {
            if (el.checked !== el.defaultChecked) {return true}
        } else if (el.tagName === 'SELECT') {
            for (let j = 0; j < el.options.length; j++) {
                if (el.options[j].selected !== el.options[j].defaultSelected) {return true}
            }
        } else if (el.type === 'color') {
            if (el.value.toLowerCase() !== el.defaultValue.toLowerCase()) {return true}
        } else if (el.value !== el.defaultValue) {
            return true
        }
    }
    return false
}

// A Save submit fires from a form inside an edit row (.dt-row-edit). First guard against a no-op
// save: if nothing in the row's form changed from its rendered defaults, cancel the request so the
// backend is never contacted (no phantom role-change log line, no needless UPDATE) and just restore
// the view state. Otherwise proceed. By htmx:beforeRequest the request's parameters are already
// gathered, so collapsing the row's edit state here can't affect what gets submitted — it only
// removes the highlight/expanded chrome before the round-trip, so the row never flashes when the
// swapped-in view row replaces it. Scoped to .dt-row-edit so the many other htmx requests
// (increment/decrement, delete-confirm, pagination) are untouched.
document.body.addEventListener('htmx:beforeRequest', function (e) {
    const row = e.target.closest ? e.target.closest('tr.dt-row-edit') : null
    if (!row) {return}
    const form = e.target.tagName === 'FORM' ? e.target : (e.target.closest && e.target.closest('form'))
    if (form && !window.dtFormDirty(form)) {
        e.preventDefault()             // abort the htmx request — nothing changed
        window.dtCancelEdit(row)       // restore the view state without a round-trip
        return
    }
    window.dtFinishEdit(row)
})

// Kill the post-Save "flash". An outerHTML swap replaces the whole <tr> with a brand-new element;
// because the cursor is stationary over it, the browser applies `:hover` only on the NEXT frame, so
// the row's hover background and its action-button opacity animate in from their non-hover state —
// a visible fade Cancel never shows (Cancel reuses the same element and never loses hover). Tag the
// swapped-in row `.dt-no-transition` (transitions off) so that late hover snaps in instantly, then
// drop the class a couple of frames later to restore normal hover-out transitions. Collect the row
// from the swap target itself, its descendants, or its ancestor, to cover whichever element htmx
// reports for the swap.
document.body.addEventListener('htmx:afterSwap', function (e) {
    const el = e.target
    if (!el || !el.querySelectorAll) {return}
    const rows = new Set()
    if (el.matches && el.matches('tr.dt-row')) {rows.add(el)}
    el.querySelectorAll('tr.dt-row').forEach(function (r) { rows.add(r) })
    const ancestor = el.closest && el.closest('tr.dt-row')
    if (ancestor) {rows.add(ancestor)}
    rows.forEach(function (row) {
        row.classList.add('dt-no-transition')
        void row.offsetWidth   // flush the non-hover state without a transition
        requestAnimationFrame(function () {
            requestAnimationFrame(function () { row.classList.remove('dt-no-transition') })
        })
    })
})

// Disarm every armed row except (optionally) one. "Armed" = mid edit or mid delete-confirm;
// either way the row shows a visible Cancel (.dt-btn-cancel), so clicking it restores the row.
// Exposed on window so page scripts can also call it (e.g. a delete rejected server-side).
window.dtClearArmedRows = function (exceptRow) {
    document.querySelectorAll('tr .dt-btn-cancel').forEach(function (cancel) {
        const row = cancel.closest('tr')
        if (!row || row === exceptRow) {return}
        if (cancel.offsetParent === null) {return}   // hidden → this row isn't armed
        cancel.click()
    })
}
// Selecting another entry (clicking its Edit or Delete) disarms whatever was armed before, and
// Edit/Cancel themselves toggle the row's edit state (partials/dt-row-actions.html). Guarded
// against a confirm-delete row (partials/dt-confirm-delete-row.html) — its OWN Cancel button
// shares the `.dt-btn-cancel` class (for the generic disarm-on-select behaviour above) but is an
// htmx `hx-get` restore, not an edit-mode toggle.
document.body.addEventListener('click', function (e) {
    const trigger = e.target.closest('.dt-btn-edit, .dt-btn-delete')
    if (trigger) {window.dtClearArmedRows(trigger.closest('tr'))}
    const row = e.target.closest('tr')
    if (row && row.classList.contains('dt-row-confirm')) {return}
    if (e.target.closest('.dt-btn-edit')) {window.dtStartEdit(row)}
    if (e.target.closest('.dt-btn-cancel')) {window.dtCancelEdit(row)}
});

// Mobile hamburger menu (partials/navbar.html): toggles the dropdown open/closed via the
// data-open attribute its CSS keys on, and mirrors the state onto aria-expanded.
(function () {
    const toggle = document.getElementById('hamburger-btn')
    if (!toggle) {return}
    toggle.addEventListener('click', function () {
        const menu = document.getElementById('mobile-menu')
        const open = menu.getAttribute('data-open') !== 'true'
        menu.setAttribute('data-open', String(open))
        toggle.setAttribute('aria-expanded', String(open))
    })
})()

// ── Delegated htmx:configRequest for the search-filter inputs/links ───────────
// Any element carrying `data-search-source="<input id>"` (the search box itself, or a pagination
// link referencing it) copies that input's current value into the outgoing htmx request's `q`
// parameter. Replaces five identical `hx-on="htmx:configRequest: …"` attributes (actions.html,
// day-panel.html, day-actions-list.html) — htmx executes `hx-on` via the Function constructor,
// which the CSP's script-src blocks without 'unsafe-eval'.
document.body.addEventListener('htmx:configRequest', function (e) {
    const marker = e.target.closest('[data-search-source]')
    if (!marker) {return}
    const input = document.getElementById(marker.dataset.searchSource)
    if (input) {e.detail.parameters.q = input.value}
})

// Day-panel: at most one confirm row open at a time. Runs in capture phase so it fires before
// HTMX on the Delete button; the Cancel click triggers its own hx-get to restore the normal state.
document.addEventListener('click', function (e) {
    if (e.target.closest('.day-item .dt-btn-delete')) {
        document.querySelectorAll('.day-item-confirm .dt-btn-cancel').forEach(function (cancel) {
            cancel.click()
        })
    }
}, true);

// ── Shared form-modal validation ──────────────────────────────────────────────
// A form marked `data-validate` — built from partials/form-field.html with a `[data-form-errors]`
// slot — surfaces blank-required and malformed-email fields as in-page error banners (identical
// markup to the server-rendered ones) instead of the browser's native per-field pop-ups. The
// login and register cards both opt in, so they behave the same; any future form-based modal gets
// the behaviour for free. Server-side checks remain the authoritative backstop for anything the
// client can't see (e.g. a duplicate email or a password mismatch on register).
(function () {
    function escapeHtml(value) {
        return String(value).replace(/[&<>"']/g, function (c) {
            return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', '\'': '&#39;'}[c]
        })
    }
    function labelOf(field) {
        return field.getAttribute('data-field-label') || field.name || window.Diurnal.i18n.thisFieldFallback
    }

    document.addEventListener('submit', function (e) {
        const form = e.target
        if (!form || !form.matches || !form.matches('form[data-validate]')) {
            return
        }

        const missing = []
        const errors = []
        let firstInvalid = null
        form.querySelectorAll('[required]').forEach(function (field) {
            if (!field.value.trim()) {
                missing.push(labelOf(field))
                firstInvalid = firstInvalid || field
            } else if (field.type === 'email' && field.value.indexOf('@') === -1) {
                if (errors.indexOf(window.Diurnal.i18n.emailShapeInvalid) === -1) {
                    errors.push(window.Diurnal.i18n.emailShapeInvalid)
                }
                firstInvalid = firstInvalid || field
            }
        })

        const slot = form.querySelector('[data-form-errors]')
        if (missing.length === 0 && errors.length === 0) {
            // Valid — let the form submit (natively or via ajax). Deliberately DON'T clear any banner
            // still showing: a stale error should linger until the response replaces it (or the page
            // navigates on success), so the card never blinks empty between attempts.
            return
        }

        e.preventDefault()
        if (!slot) {
            return
        }

        let html = ''
        if (missing.length > 0) {
            // Deliberately count-independent wording ("the required fields", not "field"/"fields") - see
            // AppMessages#missingRequiredFieldsPrefix's own Javadoc for why this client-side pre-check can't
            // replicate every offered language's plural grammar the way the server-rendered banner does.
            html += window.Diurnal.bannerHtml(`${  window.Diurnal.i18n.missingRequiredFieldsPrefix  }` +
                    `<ul class="list-disc list-inside mt-1">${
                    missing.map(function (m) { return `<li>${  escapeHtml(m)  }</li>` }).join('')
                    }</ul>`)
        }
        errors.forEach(function (msg) {
            html += window.Diurnal.bannerHtml(escapeHtml(msg))
        })
        // Only touch the DOM when the banner actually changes — re-rendering identical markup
        // destroys and recreates the nodes, which makes a repeated identical failure "jump".
        if (slot.innerHTML !== html) { slot.innerHTML = html }
        slot.hidden = false
        if (firstInvalid) { firstInvalid.focus() }
    })
})();

// ── Disable submit until every required field is filled ───────────────────────
// A form marked `data-disable-until-complete` (the register card) keeps its submit button disabled
// until every [required] field holds a non-blank value, so an obviously incomplete submission can't
// be fired — a clearer signal than only surfacing the missing-fields banner after a click. This is a
// UX affordance ONLY, and deliberately a strict subset of the real validation: it checks presence
// (plus the one extra rule below), not format — a filled-but-malformed email still enables the
// button and is caught by the data-validate banner above; the server remains the authoritative
// validator. If the lock is ever bypassed (no JS, a forced submit), both backstops still fire.
// Listening on the form (input + change) keeps the button state in sync as the user types; both
// cards keep their fields (incl. passwords) on a failed AJAX submit, so the button simply stays in
// whatever state the fields warrant.
// A button carrying `data-hold-disabled` is owned by another controller (the shared lockout countdown,
// which greys it out for a fixed duration) — this handler leaves it alone so the two don't fight.
(function () {
    // The one format rule folded into this presence check: a non-blank confirmPassword that doesn't
    // yet match password keeps the button disabled, mirroring the Settings change-password flow's
    // confirmStepValid (settings.js) instead of letting the user submit a doomed request.
    function passwordsMismatched(form) {
        const pwd = form.querySelector('input[name="password"]')
        const confirm = form.querySelector('input[name="confirmPassword"]')
        if (!pwd || !confirm) { return false }
        return confirm.value.length > 0 && confirm.value !== pwd.value
    }
    function sync(form) {
        const btn = form.querySelector('button[type="submit"]')
        if (btn && !btn.hasAttribute('data-hold-disabled')) {
            btn.disabled = !window.Diurnal.requiredFilled(form) || passwordsMismatched(form)
        }
    }
    document.querySelectorAll('form[data-disable-until-complete]').forEach(function (form) {
        sync(form)   // reflect the server-rendered state (blank fields → disabled) on first paint
        form.addEventListener('input', function () { sync(form) })
        form.addEventListener('change', function () { sync(form) })
    })
})();

// ── Shared lockout countdown ──────────────────────────────────────────────────
// When the server rejects a login OR a registration because the client IP is locked out, it carries the
// exact seconds left in the X-Lockout-Retry-After response header. Both AJAX form handlers below render
// the SAME live mm:ss countdown banner in the form's [data-form-errors] slot and keep the submit button
// greyed + inert until it reaches 00:00, then hide the banner and hand the button back. The countdown
// ticks off wall-clock time to the server-provided expiry, so a backgrounded tab self-corrects on return.
// Enforcement stays server-side — this is cosmetic, so an early retry just re-shows the banner. A per-form
// timer (keyed via WeakMap) means a re-trigger replaces cleanly and the submit handlers can tell a
// countdown is running (the form stays inert until it expires).
(function () {
    const timers = new WeakMap()

    // Whether a lockout countdown is currently running for this form (the submit handlers stay inert).
    window.Diurnal.lockoutRunning = function (form) {
        return timers.has(form)
    }

    // Stop any countdown, hide the banner, and hand the button back to the data-disable-until-complete
    // controller in a consistent state (a blank required field must stay disabled).
    window.Diurnal.clearLockout = function (form, slot, submitBtn) {
        if (timers.has(form)) { clearInterval(timers.get(form)); timers.delete(form) }
        if (slot) { slot.hidden = true; slot.innerHTML = '' }
        if (submitBtn) {
            submitBtn.removeAttribute('data-hold-disabled')
            submitBtn.disabled = !window.Diurnal.requiredFilled(form)
        }
    }

    // Show the live mm:ss countdown banner and keep the submit button greyed + inert until the
    // server-provided expiry. The lead text is neutral ("Too many failed attempts.") so it reads the same
    // on the login and registration cards — they share ONE per-IP lockout counter, so naming either flow
    // would be misleading — and matches the server's no-JS banner and API message. data-hold-disabled
    // tells the data-disable-until-complete handler to keep its hands off, so typing during a lockout
    // can't re-enable the greyed-out button.
    window.Diurnal.startLockoutCountdown = function (form, slot, submitBtn, seconds) {
        const total = Math.floor(seconds)
        if (!(total > 0) || !slot) { window.Diurnal.clearLockout(form, slot, submitBtn); return }
        if (timers.has(form)) { clearInterval(timers.get(form)); timers.delete(form) }
        if (submitBtn) { submitBtn.setAttribute('data-hold-disabled', ''); submitBtn.disabled = true }
        slot.innerHTML = window.Diurnal.bannerHtml(
            'Too many failed attempts. Please try again in <span data-lockout-clock></span>.')
        slot.hidden = false
        const clock = slot.querySelector('[data-lockout-clock]')
        const endTime = Date.now() + total * 1000
        function tick() {
            const remaining = Math.round((endTime - Date.now()) / 1000)
            if (remaining < 0) { window.Diurnal.clearLockout(form, slot, submitBtn); return }   // expired
            if (clock) { clock.textContent = window.Diurnal.formatClock(remaining) }
        }
        timers.set(form, setInterval(tick, 1000))
        tick()   // paint immediately, before the first interval
    }
})();

// ── Fetch-submitted forms (data-ajax-submit / data-ajax-errors) ───────────────
// Both modes post via fetch() instead of a full-page navigation, so a rejected submission can
// surface an inline error and let the user amend and retry WITHOUT the page reloading and wiping
// the fields they just typed. Both run after the `data-validate` handler above (registered later,
// both at document level, so they see the validator's preventDefault) and only take over once
// client-side validation has passed; without JS each form submits natively and the server
// round-trips the same page + banner, degrading cleanly. They share the entry guard, the
// Diurnal.postForm() submission core and the lockout-header branch below, and differ only in how a
// resolved response is interpreted (see each handler's comment).
(function () {
    // Common entry guard: respect the validator's preventDefault, match the mode's selector, and
    // swallow every submit while a lockout countdown is running (button click or Enter — the form
    // is inert until it expires and the button is restored).
    function guardedForm(e, selector) {
        const form = e.target
        if (e.defaultPrevented) { return null }   // client-side validation already blocked this submit
        if (!form || !form.matches || !form.matches(selector)) { return null }
        if (window.Diurnal.lockoutRunning(form)) { e.preventDefault(); return null }
        e.preventDefault()
        return form
    }

    // The exact seconds left on a lockout, carried on the response by the server (NaN when absent).
    function retryAfterOf(resp) {
        return parseInt(resp.headers.get('X-Lockout-Retry-After'), 10)
    }

    // `data-ajax-submit` (the login card): form auth 302s to the landing page on success and back
    // to /login?error=true on failure; fetch follows the redirect, so the final resolved path tells
    // the two apart. Server-side form auth is unchanged and remains the no-JS fallback (the server
    // still redirects to /login?error=true). OIDC login is a plain link, not this form, so it is
    // untouched.
    document.addEventListener('submit', function (e) {
        const form = guardedForm(e, 'form[data-ajax-submit]')
        if (!form) { return }

        const slot = form.querySelector('[data-form-errors]')
        const submitBtn = form.querySelector('button[type="submit"]')
        // Hold the button disabled while this submit is in flight. data-hold-disabled tells the
        // data-disable-until-complete handler to keep its hands off (a lockout keeps the hold via the
        // shared countdown; otherwise clearLockout below hands the button back).
        if (submitBtn) { submitBtn.setAttribute('data-hold-disabled', ''); submitBtn.disabled = true }

        function showError(message) {
            window.Diurnal.clearLockout(form, slot, submitBtn)   // drop any countdown + release the button
            if (slot) {
                slot.innerHTML = window.Diurnal.bannerHtml(message)
                slot.hidden = false
            }
            const pw = form.querySelector('input[type="password"]')
            if (pw) { pw.focus() }   // land on the password so a minor change is a keystroke away
        }

        window.Diurnal.postForm(form).then(function (resp) {
            const dest = new URL(resp.url, window.location.origin)
            if (dest.pathname === '/login') {
                // A lockout carries the seconds left in X-Lockout-Retry-After; otherwise it's a bad login.
                const retryAfter = retryAfterOf(resp)
                if (retryAfter > 0) {
                    window.Diurnal.startLockoutCountdown(form, slot, submitBtn, retryAfter)
                } else {
                    showError(window.Diurnal.i18n.invalidCredentials)
                }
            } else {
                window.location.assign(resp.url)   // session cookie already set — load the landing page
            }
        }).catch(function () {
            showError(window.Diurnal.i18n.somethingWentWrong)
        })
    })

    // `data-ajax-errors` (the register card): a rejected submission re-renders ONLY its error
    // banner, in place. Both failure sources — a blank field caught client-side and a duplicate
    // email caught server-side — land in the same `[data-form-errors]` slot, and the banner is
    // swapped ONLY when its contents change and is NEVER cleared on a failure, so repeated failed
    // attempts don't make the card "jump" between them; it clears only when a successful attempt
    // navigates away. On success the server 303s onward (with the session cookie set on the
    // redirect) and we follow it.
    document.addEventListener('submit', function (e) {
        const form = guardedForm(e, 'form[data-ajax-errors]')
        if (!form) { return }

        const slot = form.querySelector('[data-form-errors]')
        const submitBtn = form.querySelector('button[type="submit"]')
        if (submitBtn) { submitBtn.disabled = true }

        // Swap the banner only when it changes; leave the DOM (and layout) untouched otherwise. The
        // password fields are deliberately KEPT on a failure (aligned with the login card) so a user
        // whose email was rejected can just amend it and resubmit without retyping both passwords —
        // the fields stay filled, so re-enabling the submit button below is consistent with the
        // data-disable-until-complete lock.
        function showErrors(html) {
            if (submitBtn) { submitBtn.disabled = false }
            if (!slot) { return }
            if (slot.innerHTML !== html) { slot.innerHTML = html }
            slot.hidden = false
        }

        const ownPath = new URL(form.action, window.location.origin).pathname
        window.Diurnal.postForm(form).then(function (resp) {
            // A failed submit re-renders the form at its own path (400/429); success 303s elsewhere.
            if (new URL(resp.url, window.location.origin).pathname !== ownPath) {
                window.location.assign(resp.url)
                return undefined
            }
            // A lockout (429) carries the exact seconds left in X-Lockout-Retry-After: run the shared live
            // mm:ss countdown instead of swapping the server's static (no-JS) banner.
            const retryAfter = retryAfterOf(resp)
            if (retryAfter > 0) {
                window.Diurnal.startLockoutCountdown(form, slot, submitBtn, retryAfter)
                return undefined
            }
            return resp.text().then(function (body) {
                const fresh = new DOMParser().parseFromString(body, 'text/html').querySelector('[data-form-errors]')
                showErrors(fresh ? fresh.innerHTML : '')
            })
        }).catch(function () {
            showErrors(window.Diurnal.bannerHtml(window.Diurnal.i18n.somethingWentWrong))
        })
    })
})();

// ── App-locale number grouping ─────────────────────────────────────────────────
// Stats figures (counts, streaks, trends, averages) are rendered as bare digit strings by the
// server, which can't know the viewer's locale. This formats every number inside a `.js-num`
// element using the *resolved app language* (Diurnal.lang, NOT the browser's own locale — a
// Japanese-language preference with an English-locale browser must still group numbers the
// Japanese way, matching every other figure the server rendered on the same page), so 1000
// becomes "1,000" (en) or "1.000" (de) etc. Only elements explicitly tagged `.js-num` are
// touched — never date/label fields (e.g. "Jun 2026"), names or emails; a year/day-of-month instead
// goes through Diurnal.localizeDigits (below), which transcodes digits without ever grouping them.
(function () {
    // Any element holding a number of 5+ digits (i.e. >= 10,000) keeps its ungrouped server text on
    // `data-num-raw`, so the fitting pass below can re-derive a "10.0k" form from exact digits rather
    // than trying to parse locale-grouped text back into a number ("1.000" is 1000 in en, 1 in de).
    const ABBREVIABLE = /\d{5}/

    window.Diurnal.formatNumber = function (num, decimals) {
        return decimals > 0
            ? num.toLocaleString(window.Diurnal.lang, { minimumFractionDigits: decimals, maximumFractionDigits: decimals })
            : num.toLocaleString(window.Diurnal.lang)
    }
    // Glyph-for-glyph digit transcoding, deliberately WITHOUT grouping — for a calendar day number or
    // year, which must show this language's own digit glyphs (e.g. Eastern Arabic-Indic for Arabic)
    // but must NEVER gain a "2,026"-style grouping separator a bare Number#toLocaleString would add.
    // Built from Intl.NumberFormat itself, not a hardcoded numbering-system table, so it stays correct
    // for whatever the resolved language's own CLDR data says — a no-op for every Latin-digit language.
    // Operates on the STRING (not a parsed Number), so a zero-padded value ("05") keeps its padding.
    const DIGIT_GLYPHS = (function () {
        const nf = new Intl.NumberFormat(window.Diurnal.lang, { useGrouping: false })
        const glyphs = {}
        for (let d = 0; d <= 9; d += 1) { glyphs[d] = nf.format(d) }
        return glyphs
    })()
    window.Diurnal.localizeDigits = function (text) {
        return String(text).replace(/\d/g, function (digit) { return DIGIT_GLYPHS[digit] })
    }
    // The reverse transcode, for an EDITABLE field that displays this language's own digit glyphs
    // (a Settings numeric stepper) but must submit/parse plain Latin digits underneath — see
    // wireNumericPref in settings.js. Only ever strips this language's OWN glyph set (built from the
    // same Intl.NumberFormat as DIGIT_GLYPHS above), so it never touches a character that merely looks
    // digit-adjacent in some other script. A no-op under a Latin-digit language, same as the forward
    // direction.
    const LATIN_FROM_GLYPH = (function () {
        const map = {}
        for (let d = 0; d <= 9; d += 1) { map[DIGIT_GLYPHS[d]] = String(d) }
        return map
    })()
    const GLYPH_CHARS = Object.keys(LATIN_FROM_GLYPH).map(function (ch) { return ch.replace(/[-\]\\^]/g, '\\$&') }).join('')
    const GLYPH_PATTERN = new RegExp(`[${GLYPH_CHARS}]`, 'g')
    window.Diurnal.delocalizeDigits = function (text) {
        return String(text).replace(GLYPH_PATTERN, function (glyph) { return LATIN_FROM_GLYPH[glyph] })
    }
    // Replace each run of digits (optionally with a decimal part) in a string, preserving everything
    // around it: "+1234" → "+1,234" and "1234 this month" → "1,234 this month".
    window.Diurnal.groupNumbers = function (text) {
        return text.replace(/\d+(?:\.\d+)?/g, function (match) {
            const dot = match.indexOf('.')
            return window.Diurnal.formatNumber(Number(match), dot === -1 ? 0 : match.length - dot - 1)
        })
    }
    window.Diurnal.formatNumbers = function (rootParam) {
        const root = rootParam || document.body
        const els = []
        if (root.classList && root.classList.contains('js-num')) { els.push(root) }
        if (root.querySelectorAll) { Array.prototype.push.apply(els, root.querySelectorAll('.js-num')) }
        els.forEach(function (el) {
            if (el.dataset.numDone) { return }   // idempotent: don't re-group an already-grouped value
            el.dataset.numDone = '1'
            if (ABBREVIABLE.test(el.textContent)) { el.dataset.numRaw = el.textContent }
            const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null)
            const nodes = []
            let node
            while ((node = walker.nextNode())) { nodes.push(node) }
            nodes.forEach(function (textNode) {
                textNode.nodeValue = window.Diurnal.groupNumbers(textNode.nodeValue)
            })
        })
    }
    // Initial render (the body is fully parsed above this script), then again for any HTMX-swapped
    // content (e.g. the stats list paginating in).
    window.Diurnal.formatNumbers(document.body)
    document.body.addEventListener('htmx:afterSwap', function (e) { window.Diurnal.formatNumbers(e.target) })
})();

// ── Digit-only localization (no grouping) ──────────────────────────────────────
// A second, narrower pass alongside .js-num above: `.js-digits` marks server-rendered numeric text
// that must show this language's own digit glyphs but must NEVER be grouped - a settings preset
// pill (its own value already round-trips through `data-value`, untouched here), the footer's build
// year, the footer's version text. Kept separate from formatNumbers rather than a mode flag on
// `.js-num`, so a caller can't accidentally group a year or a version number by picking the wrong
// class. Never touches an attribute (href, data-value) - only text nodes, same as formatNumbers.
(function () {
    window.Diurnal.localizeDigitsIn = function (rootParam) {
        const root = rootParam || document.body
        const els = []
        if (root.classList && root.classList.contains('js-digits')) { els.push(root) }
        if (root.querySelectorAll) { Array.prototype.push.apply(els, root.querySelectorAll('.js-digits')) }
        els.forEach(function (el) {
            if (el.dataset.digitsDone) { return } // idempotent: don't re-walk an already-localized element
            el.dataset.digitsDone = '1'
            const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null)
            const nodes = []
            let node
            while ((node = walker.nextNode())) { nodes.push(node) }
            nodes.forEach(function (textNode) {
                textNode.nodeValue = window.Diurnal.localizeDigits(textNode.nodeValue)
            })
        })
    }
    window.Diurnal.localizeDigitsIn(document.body)
    document.body.addEventListener('htmx:afterSwap', function (e) { window.Diurnal.localizeDigitsIn(e.target) })
})();

// ── Editable numeric text inputs that display localized digits ────────────────
// `.js-digits` (above) is read-only text. `.js-num-input` is the editable-field counterpart: a
// `type="text" inputmode="numeric"` field (never `type="number"` - that value is spec-constrained to
// ASCII digits and cannot hold e.g. Eastern Arabic-Indic ones) whose VALUE should show this language's
// own digit glyphs while the request that actually leaves the browser carries plain Latin ones. The
// day panel's per-action count field (partials/day-action-item.html) and the calendar's month/year jump
// popup (partials/calendar-toolbar.html's `.cal-pop-year`, wired by hand in dashboard.js's own
// renderPicker/commitYear rather than through this file's own change-listener since it never leaves the
// browser as a request) are today's users; settings.js's
// wireNumericPref is the fuller version of this same pattern (presets/stepper), kept separate there since
// it also owns pill-highlighting/clamping this generic version has no notion of.
(function () {
    function localizeField(field) {
        if (field.dataset.numInputDone) { return } // idempotent: don't re-localize an already-shown value
        field.dataset.numInputDone = '1'
        field.value = window.Diurnal.localizeDigits(field.value)
    }
    // Exposed (not just wired to htmx:afterSwap) because at least one caller sets innerHTML directly
    // rather than going through an htmx swap (dashboard.js's day panel) and must call this explicitly,
    // the same way it already does for formatNumbers/localizeDigitsIn/fitFigures.
    window.Diurnal.localizeNumInputsIn = function (rootParam) {
        const root = rootParam || document.body
        if (root.matches && root.matches('.js-num-input')) { localizeField(root) }
        if (root.querySelectorAll) { root.querySelectorAll('.js-num-input').forEach(localizeField) }
    }
    window.Diurnal.localizeNumInputsIn(document.body)
    document.body.addEventListener('htmx:afterSwap', function (e) { window.Diurnal.localizeNumInputsIn(e.target) })

    // Normalize whatever was typed (this language's own digits, plain Latin ones - many keyboards
    // still produce these even under a non-Latin layout - or a mix) to one consistent, this-language
    // display once the value commits, mirroring wireNumericPref's identical `change` handling.
    document.body.addEventListener('change', function (e) {
        if (e.target.matches && e.target.matches('.js-num-input')) {
            e.target.value = window.Diurnal.localizeDigits(window.Diurnal.delocalizeDigits(e.target.value))
        }
    })

    // The actual request is built from the field's live (localized) DOM value - delocalize the named
    // parameter back to Latin right before it leaves the browser, the same htmx:configRequest shape
    // settings.js uses for its own numeric fields (see that file for why getAll/delete/append, not a
    // direct assignment, is required here).
    document.body.addEventListener('htmx:configRequest', function (e) {
        ['count'].forEach(function (name) {
            const values = e.detail.parameters.getAll(name)
            if (values.length === 0) { return }
            e.detail.parameters.delete(name)
            values.forEach(function (v) { e.detail.parameters.append(name, window.Diurnal.delocalizeDigits(v)) })
        })
    })
})();

// ── Responsive figure fitting (dates, large counts) ───────────────────────────
// Every server-rendered figure carries its FULLEST form — a spelled-out month ("15 June 2026"), a
// 4-digit year, an exact count. That is the right text whenever it fits, and only the browser knows
// whether it does: the width of a stat tile depends on the viewport, the locale's grouping separators
// and the user's chosen font. So the server marks each shortenable line `data-fit` and the reduction
// happens here — one step at a time, and only while the line still overflows its own box:
//     "15 June 2026"    →  "15 Jun 2026"  →  "15 Jun 26"
//     "10,000 all time" →  "10.0k all time"        (only for a figure of 10,000 or more)
// A line additionally marked `data-fit-scale` (the stat-tile value) may then step its TYPE SIZE down
// once the text ladder is exhausted: every stat value is rendered at the same size whatever it carries,
// and only a value that cannot be abbreviated into the tile ("1 year, 2 months, 3 days") gives size up,
// one step at a time. Text is spent before size, so the type stays uniform for as long as it can.
// Each step is measured with the line forced onto a single line (`.fit-measure`); the class is removed
// again straight after, so a line that overflows even at its shortest wraps normally rather than being
// clipped. The month table and the abbreviation ladder are shared with the calendar toolbar's own
// title fitting (dashboard.js), so "June" → "Jun" is spelled out in exactly one place.
(function () {
    const COUNT_ABBR_THRESHOLD = 10000 // a count is only ever shortened to "10.0k" at or above this
    const DEFAULT_DECIMALS = 1
    const OVERFLOW_TOLERANCE = 1 // px; scrollWidth rounds up, so ignore a sub-pixel "overflow"

    // Full/abbreviated month names in the resolved app language (Diurnal.lang), not a hardcoded
    // English array — Intl.DateTimeFormat draws on the SAME CLDR data java.time uses server-side
    // (user/Language#monthYearPattern, time/DayLabels), so shortenMonths below abbreviates whatever
    // month name the server actually rendered. A fixed UTC noon avoids any host-timezone date
    // arithmetic shifting which calendar month is formatted.
    const monthNames = function (style) {
        const fmt = new Intl.DateTimeFormat(window.Diurnal.lang, { month: style, timeZone: 'UTC' })
        return Array.from({ length: 12 }, function (_, month) {
            return fmt.format(new Date(Date.UTC(2000, month, 1, 12)))
        })
    }
    window.Diurnal.MONTHS_FULL = monthNames('long')
    window.Diurnal.MONTHS_ABBR = monthNames('short')

    // A bare `\b` is an ASCII word boundary (built on \w, which never matches an Arabic - or any
    // non-Latin - letter), so it silently matches NOTHING around a non-Latin month name: confirmed,
    // "20 أغسطس 2026".replace(/\bأغسطس\b/g, ...) is a no-op, meaning the abbreviation step below would
    // never fire for Arabic. The Unicode-aware equivalent - negative lookaround on \p{L}/\p{N} (needs
    // the `u` flag) - works identically across every script.
    const MONTH_PATTERNS = window.Diurnal.MONTHS_FULL.map(function (month) {
        return new RegExp(`(?<![\\p{L}\\p{N}])${  month  }(?![\\p{L}\\p{N}])`, 'gu')
    })

    // "15 June 2026" → "15 Jun 2026". Whole words only, so "1 month, 2 days" is untouched.
    window.Diurnal.shortenMonths = function (text) {
        return MONTH_PATTERNS.reduce(function (acc, pattern, i) {
            return acc.replace(pattern, window.Diurnal.MONTHS_ABBR[i])
        }, text)
    }
    // "15 Jun 2026" → "15 Jun 26". Only a bare 19xx/20xx reads as a year, so a count never matches.
    // `fitFigures` runs after the locale-digit passes (.js-num/.js-digits) further up this file, so by
    // the time this sees `text` its digits may already be this language's own glyphs, not ASCII - the
    // \d/literal-19/20 match below only ever recognizes ASCII, so delocalize first (a no-op for a
    // Latin-digit language) and re-localize the result (matching what was already on screen).
    window.Diurnal.shortenYears = function (text) {
        const shortened = window.Diurnal.delocalizeDigits(text).replace(/\b(?:19|20)(\d{2})\b/g, '$1')
        return window.Diurnal.localizeDigits(shortened)
    }
    // The ordered abbreviation ladder for a label, widest first, with each step included only when it
    // actually shortens the one before it. A label with no month, year or large count yields a single
    // step and is left alone.
    window.Diurnal.labelSteps = function (text, rawNumbers, decimals) {
        const steps = [text]
        const push = function (candidate) {
            if (candidate !== steps[steps.length - 1]) { steps.push(candidate) }
        }
        push(window.Diurnal.shortenMonths(steps[steps.length - 1]))
        push(window.Diurnal.shortenYears(steps[steps.length - 1]))
        if (rawNumbers) { push(abbreviateCounts(rawNumbers, decimals)) }
        return steps
    }

    // Rewrite the ungrouped text's figures, replacing any of 10,000 or more with its "10.0k" form (at
    // the viewer's decimal-place preference) and locale-grouping the rest exactly as formatNumbers did.
    function abbreviateCounts(raw, decimals) {
        return raw.replace(/\d+(?:\.\d+)?/g, function (match) {
            const value = Number(match)
            if (value >= COUNT_ABBR_THRESHOLD) {
                return `${  window.Diurnal.formatNumber(value / 1000, decimals)  }k`
            }
            const dot = match.indexOf('.')
            return window.Diurnal.formatNumber(value, dot === -1 ? 0 : match.length - dot - 1)
        })
    }

    // The nearest [data-decimal-places] ancestor carries the user's fractional-stat preference, so an
    // abbreviated count matches the averages beside it.
    function decimalsFor(el) {
        const host = el.closest('[data-decimal-places]')
        const parsed = host ? parseInt(host.dataset.decimalPlaces, 10) : NaN
        return isNaN(parsed) ? DEFAULT_DECIMALS : parsed
    }

    // Each element's ladder is computed once and cached (keyed by the element, so an HTMX swap's
    // replacements are collected): a re-fit after a resize re-measures, it does not re-derive.
    const ladders = new WeakMap()

    // The type-size ladder a `data-fit-scale` line walks down, largest first. Every entry is a literal
    // class name so the Tailwind scan of this file keeps all of them (see tailwind.config.js `content`).
    const SIZE_STEPS = ['text-2xl', 'text-xl', 'text-lg', 'text-base', 'text-sm']
    // How many lines a scalable value may wrap onto, in the fallback case where NO size fits it on one
    // line (a condensed duration, "1 year, 2 months, 3 days"). Two lines of larger type read as the same
    // kind of figure as the tiles beside it; the smallest size on one line does not.
    const MAX_VALUE_LINES = 2
    // The rung the server rendered each scalable line at — its starting point on every re-fit, so a
    // widened viewport restores the full size rather than staying shrunk.
    const baseSizes = new WeakMap()

    // The line's index in SIZE_STEPS, or -1 when it does not scale (unmarked, or at no known size).
    function baseSizeOf(el) {
        let base = baseSizes.get(el)
        if (base === undefined) {
            base = 'fitScale' in el.dataset
                ? SIZE_STEPS.findIndex(function (size) { return el.classList.contains(size) })
                : -1
            baseSizes.set(el, base)
        }
        return base
    }

    function applySize(el, index) {
        SIZE_STEPS.forEach(function (size) { el.classList.remove(size) })
        el.classList.add(SIZE_STEPS[index])
    }

    function overflows(el) {
        return el.scrollWidth > el.clientWidth + OVERFLOW_TOLERANCE
    }

    // Whether the line, WRAPPING freely (i.e. with `.fit-measure` off), runs past MAX_VALUE_LINES. A
    // resolved line-height is needed to count the lines; if the browser reports `normal` instead of a
    // length, the size is simply left alone.
    function exceedsLines(el) {
        const lineHeight = parseFloat(window.getComputedStyle(el).lineHeight)
        return !!lineHeight && el.scrollHeight > lineHeight * MAX_VALUE_LINES + OVERFLOW_TOLERANCE
    }

    // Steps a scalable line's type size down until its (already abbreviated) text fits, and returns with
    // `.fit-measure` cleared. Preference order: the largest size that holds the value on ONE line; failing
    // that — no size does — back up to the largest size that holds it within MAX_VALUE_LINES wrapped.
    function fitSize(el, base) {
        let size = base
        while (size < SIZE_STEPS.length - 1 && overflows(el)) {
            size += 1
            applySize(el, size)
        }
        const fitsOnOneLine = !overflows(el)
        el.classList.remove('fit-measure')
        if (fitsOnOneLine) { return }
        size = base
        applySize(el, size)
        while (size < SIZE_STEPS.length - 1 && exceedsLines(el)) {
            size += 1
            applySize(el, size)
        }
    }

    function fit(el) {
        let steps = ladders.get(el)
        if (!steps) {
            steps = window.Diurnal.labelSteps(el.textContent, el.dataset.numRaw, decimalsFor(el))
            ladders.set(el, steps)
        }
        const base = baseSizeOf(el)
        if (steps.length < 2 && base === -1) { return }
        el.classList.add('fit-measure')
        if (base !== -1) { applySize(el, base) }
        let step = 0
        el.textContent = steps[0]
        while (step < steps.length - 1 && overflows(el)) {
            step += 1
            el.textContent = steps[step]
        }
        // Text is spent before size: only a value the abbreviation ladder could not fit gives up a step.
        if (base === -1) {
            el.classList.remove('fit-measure')
            return
        }
        fitSize(el, base)
    }

    window.Diurnal.fitFigures = function (rootParam) {
        const root = rootParam || document.body
        const els = []
        if (root.matches && root.matches('[data-fit]')) { els.push(root) }
        if (root.querySelectorAll) { Array.prototype.push.apply(els, root.querySelectorAll('[data-fit]')) }
        els.forEach(fit)
    }

    // Runs after the locale grouping above (registered first, so its afterSwap handler goes first) —
    // the ladder's widest step is the already-grouped text.
    window.Diurnal.fitFigures(document.body)
    document.body.addEventListener('htmx:afterSwap', function (e) { window.Diurnal.fitFigures(e.target) })
    // Re-fit on resize/orientation change: a widened viewport must restore the full text, not stay
    // abbreviated. Debounced, since every step re-measures layout.
    let resizeTimer = null
    window.addEventListener('resize', function () {
        window.clearTimeout(resizeTimer)
        resizeTimer = window.setTimeout(function () { window.Diurnal.fitFigures(document.body) }, 150)
    })
})();

// ── Recently-active counters ──────────────────────────────────────────────────
// The admin "Recently active" bubble and the Settings "Session" readout each carry a live counter of how
// long ago the user last made a request. The server renders a `data-elapsed-seconds` anchor (seconds since
// that request AT RENDER TIME) on a container that also holds a `.presence-dot` and a [data-activity-clock]
// span; we tick the clock up every second off the wall-clock DELTA since first paint (so a skewed client
// clock or a backgrounded tab self-corrects, exactly like the lockout countdown above). Once the elapsed
// time crosses ACTIVE_WINDOW_MS the user is no longer "recently active", so the dot flips green -> grey and
// the "... ago" text is replaced with the idle label, then the timer stops. There is no polling, so an
// inactive user is only ever re-shown as active by a fresh render (page load / HTMX swap).
(function () {
    // Must match SessionActivityService.ACTIVE_WINDOW on the server.
    const ACTIVE_WINDOW_MS = 5 * 60 * 1000
    const IDLE_LABEL = window.Diurnal.i18n.inactive
    const wired = new WeakSet()

    function wire(el) {
        if (wired.has(el)) { return }   // idempotent: never start two timers for one element
        const base = parseInt(el.dataset.elapsedSeconds, 10)
        if (!Number.isFinite(base)) { return }
        wired.add(el)
        const dot = el.querySelector('.presence-dot')
        const clock = el.querySelector('[data-activity-clock]')
        const label = el.querySelector('[data-activity-label]')
        const start = Date.now()
        function tick() {
            if (!el.isConnected) { if (timer) { clearInterval(timer) } return }   // swapped out: stop the timer
            const elapsedMs = base * 1000 + (Date.now() - start)
            if (elapsedMs >= ACTIVE_WINDOW_MS) {                                   // crossed the window: go inactive, stop
                if (dot) { dot.classList.remove('presence-dot-active'); dot.classList.add('presence-dot-idle') }
                if (label) { label.textContent = IDLE_LABEL }                      // tooltip label (admin) / inline text (Settings) -> "Inactive"
                if (el.hasAttribute('aria-label')) { el.setAttribute('aria-label', IDLE_LABEL) }
                if (timer) { clearInterval(timer) }
                return
            }
            if (clock) { clock.textContent = window.Diurnal.formatClock(Math.floor(elapsedMs / 1000)) }
        }
        const timer = setInterval(tick, 1000)
        tick()   // paint immediately, before the first interval
    }

    window.Diurnal.startActivityCounters = function (rootParam) {
        const root = rootParam || document.body
        if (root.matches && root.matches('[data-elapsed-seconds]')) { wire(root) }
        if (root.querySelectorAll) { root.querySelectorAll('[data-elapsed-seconds]').forEach(wire) }
    }
    // Initial render, then again for any HTMX-swapped content (admin list pagination / row re-renders).
    window.Diurnal.startActivityCounters(document.body)
    document.body.addEventListener('htmx:afterSwap', function (e) { window.Diurnal.startActivityCounters(e.target) })
})();

// ── Global tooltip long-press (touch) ─────────────────────────────────────────
// Desktop reveals `.app-tooltip` on hover (CSS). Touch has no hover, so a LONG press on any tooltip
// host — an element with a direct-child `.app-tooltip` (see partials/tooltip.html) — opens it by
// adding `.tip-open` (the same class the CSS reveal keys on), and swallows the click the press would
// otherwise fire (navigation, htmx, opening the colour picker…). A press elsewhere dismisses it.
// The Action-stats picker manages its OWN hosts (they also drag/toggle), so #stats-fields-list is
// skipped here. Mouse is left to hover.
(function () {
    const LONG_PRESS_MS = 500
    let timer = null
    let openHost = null
    let suppressClick = false

    // The nearest ancestor (or self) whose DIRECT child is an `.app-tooltip` — i.e. the host.
    function hostOf(startEl) {
        let el = startEl
        while (el && el.nodeType === 1) {
            if (el.querySelector(':scope > .app-tooltip')) {return el}
            el = el.parentElement
        }
        return null
    }
    function closeTip() {
        if (openHost) { openHost.classList.remove('tip-open'); openHost = null }
    }

    document.addEventListener('pointerdown', function (e) {
        if (e.pointerType === 'mouse') {return}               // mouse uses hover
        suppressClick = false
        if (openHost && !openHost.contains(e.target)) {closeTip()}   // tap outside dismisses
        if (e.target.closest('#stats-fields-list')) {return}  // handled by the stats-picker script
        const host = hostOf(e.target)
        if (!host) {return}
        timer = setTimeout(function () {
            timer = null
            suppressClick = true
            closeTip()
            host.classList.add('tip-open')
            openHost = host
        }, LONG_PRESS_MS)
    }, true)

    function cancel() { if (timer) { clearTimeout(timer); timer = null } }
    document.addEventListener('pointermove', cancel, true)
    document.addEventListener('pointerup', cancel, true)
    document.addEventListener('pointercancel', cancel, true)

    // A scroll is never a long press. `pointermove` above disarms the timer when the FINGER moves, but a
    // touch that rests on a host while the page scrolls under it (a flick handed over to momentum, a
    // scroll started elsewhere, a browser that stops reporting moves once it takes the gesture over as a
    // scroll) leaves the timer running and pops the bubble open mid-scroll. Cancel on the scroll itself,
    // and dismiss anything already open. Capture, because the scroll event does not bubble from a
    // scrolling ancestor; passive, because nothing here is prevented.
    document.addEventListener('scroll', function () { cancel(); closeTip() }, { capture: true, passive: true })

    // Swallow the click a long-press would otherwise trigger. Capture + stopImmediatePropagation so
    // it never reaches the element's own (htmx / link / colour-input) handler.
    document.addEventListener('click', function (e) {
        if (!suppressClick) {return}
        suppressClick = false
        e.preventDefault()
        e.stopPropagation()
        e.stopImmediatePropagation()
    }, true)
})();

// ── Live password-requirements popover ────────────────────────────────────────
// Drives `partials/password-constraints.html`: while the associated NEW-password field is focused,
// reveal the popover and recolour each requirement green (met) / red (unmet) as the user types. The
// rows are server-rendered from net.zodac.diurnal.text.TextFieldExtensions.constraints, so this only evaluates —
// the check tokens below (minLength / maxLength) MUST match Constraint.type. One handler serves both
// the registration and settings pages; each page has a single opted-in field.
(function () {
    // Code points, matching the server's TextFieldExtensions.length — `value.length` counts UTF-16 units, so an
    // emoji would count as two and the row would contradict the answer the server is about to give.
    function textLength(value) {
        return Array.from(value).length
    }
    function met(type, bound, value) {
        if (type === 'minLength') {return textLength(value) >= parseInt(bound, 10)}
        if (type === 'maxLength') {return textLength(value) <= parseInt(bound, 10)}
        // The change-password flow's extra row (partial param `differsFrom`): the bound is the id of the
        // input holding the password the new one must not repeat, not a numeric length.
        if (type === 'differsFrom') {
            const other = document.getElementById(bound)
            return !other || value !== other.value
        }
        return true                                     // unknown token: never block, just show it
    }
    function refresh(tip, value) {
        tip.querySelectorAll('[data-pw-check]').forEach(function (row) {
            const ok = met(row.getAttribute('data-pw-type'), row.getAttribute('data-pw-value'), value)
            row.classList.toggle('text-success', ok)
            row.classList.toggle('text-danger', !ok)
            const icon = row.querySelector('[data-pw-icon]')
            if (icon) {icon.textContent = ok ? '✓' : '✗'}
        })
    }
    document.querySelectorAll('[data-pw-tooltip]').forEach(function (tip) {
        const input = document.getElementById(tip.getAttribute('data-pw-for'))
        if (!input) {return}
        const update = function () { refresh(tip, input.value) }
        input.addEventListener('focus', function () { update(); tip.classList.add('pw-open') })
        input.addEventListener('input', update)
        input.addEventListener('blur', function () { tip.classList.remove('pw-open') })
        update()                                        // colour correctly before first reveal
    })
})()

// ── Login page: drop the per-tab dashboard state ──────────────────────────────
// Reaching the login page ends the working session: an explicit logout, a session-cookie expiry
// redirect, or a different user about to log in on this tab. Drop the retained day selection and
// the retained note draft (see note.js) so both are tied to the authentication session, never
// leaking across logins — which for a journal entry is a privacy matter, not just tidiness.
// Guarded to the login page only (path check, not a data-page marker — avoids threading a new param
// through every full-page template).
if (window.location.pathname === '/login') {
    try {
        sessionStorage.removeItem('diurnal.selectedDate')
        sessionStorage.removeItem('diurnal.noteDraft')
    } catch (e) { /* ignore */ }
}

// ── Randomise-colour buttons (partials/random-colour-button.html) ─────────────
// Fetch a suggested colour (the server picks one unlike every colour the user already uses — the
// client only ever sees the current page of actions, and never the note colour, so it cannot make
// that choice) and drop it into the given colour input. Plain fetch rather than htmx: the target is
// an <input> value, not markup, and a failed suggestion is a no-op (the picker keeps whatever it
// showed) rather than an error the user has to clear. Setting .value fires no event of its own, so
// a `change` is dispatched explicitly: the action forms ignore it (their colour is submitted with
// the form), while the Settings picker's hx-trigger="change" turns it into the auto-save it would
// have done had the value been picked by hand. `keep` also writes the value ATTRIBUTE, which is
// what form.reset() restores to — the new-action form (actions.js) re-draws its suggestion after
// each add and must not have it undone by the reset of the NEXT add.
window.Diurnal.suggestColourInto = function (input, url, keep) {
    return fetch(url, {headers: {'Accept': 'application/json'}})
        .then(function (resp) { return resp.ok ? resp.json() : null })
        .then(function (body) {
            if (body && body.colour) {
                input.value = body.colour
                if (keep) {input.setAttribute('value', body.colour)}
                input.dispatchEvent(new Event('change', {bubbles: true}))
            }
        })
        .catch(function () { /* keep the current colour */ })
}

// The button wiring over that helper. Lives here rather than in actions.js because the control is
// shared by the Actions page and the Settings note-colour row; one handler means the two can never
// drift. Delegated from the body because the edit-row buttons arrive with every swapped-in row, so
// a per-element listener would miss them; the input is found within the button's own scope — its
// <form> on the new-action card, its <td> in a table row (whose picker belongs to the row form via
// form=, not by nesting), or its [data-colour-scope] on a Settings row, which is neither. The
// button is disabled for the duration so an impatient double-click can't race two suggestions into
// the same input. A hand-picked suggestion does NOT touch the value attribute: on the new-action
// form the reset after a successful add is followed by a fresh suggestion anyway.
document.body.addEventListener('click', function (e) {
    const btn = e.target.closest('[data-random-colour]')
    if (!btn) {return}
    const scope = btn.closest('form, td, [data-colour-scope]')
    const input = scope ? scope.querySelector('input[type="color"]') : null
    if (!input) {return}
    btn.disabled = true
    window.Diurnal.suggestColourInto(input, btn.dataset.randomColourUrl, false)
        .finally(function () { btn.disabled = false })
})

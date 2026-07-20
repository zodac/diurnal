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

// The ONE place the inline error-banner markup is built client-side, mirroring
// partials/banner.html (and the Java-built HTMX banners) — a keep-in-sync pair, see
// .claude/UI_PATTERNS.md. The message is NOT escaped here: callers pass trusted literals, or
// escape user content themselves (the form validator below).
window.Diurnal.bannerHtml = function (message) {
    return `<div class="banner banner-error">${  message  }</div>`
}

// True when every [required] field in the form holds a non-blank value. Shared by the
// data-disable-until-complete controller and the lockout countdown below.
window.Diurnal.requiredFilled = function (form) {
    return Array.prototype.every.call(form.querySelectorAll('[required]'), function (field) {
        return field.value.trim() !== ''
    })
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
        return field.getAttribute('data-field-label') || field.name || 'This field'
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
                if (errors.indexOf('Email must contain an @ symbol.') === -1) {
                    errors.push('Email must contain an @ symbol.')
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
            const noun = missing.length === 1 ? 'field' : 'fields'
            html += window.Diurnal.bannerHtml(`Please fill in the following ${  noun  }:` +
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

    // mm:ss, clamped so it can NEVER render a negative value.
    function formatClock(totalSeconds) {
        const s = totalSeconds > 0 ? totalSeconds : 0
        const mins = Math.floor(s / 60)
        const secs = s % 60
        return `${(mins < 10 ? '0' : '') + mins  }:${  secs < 10 ? '0' : ''  }${secs}`
    }

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
            if (clock) { clock.textContent = formatClock(remaining) }
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
                    showError('Invalid email or password.')
                }
            } else {
                window.location.assign(resp.url)   // session cookie already set — load the landing page
            }
        }).catch(function () {
            showError('Something went wrong. Please try again.')
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
            showErrors(window.Diurnal.bannerHtml('Something went wrong. Please try again.'))
        })
    })
})();

// ── Browser-locale number grouping ────────────────────────────────────────────
// Stats figures (counts, streaks, trends, averages) are rendered as bare digit strings by the
// server, which can't know the viewer's locale. This formats every number inside a `.js-num`
// element using the *browser's* locale (`toLocaleString()` with no locale arg), so 1000 becomes
// "1,000" (en) or "1.000" (de) etc. Only elements explicitly tagged `.js-num` are touched —
// never date/label fields (e.g. "Jun 2026"), names or emails — so years are left alone.
(function () {
    function fmt(num, decimals) {
        return decimals > 0
            ? num.toLocaleString(undefined, { minimumFractionDigits: decimals, maximumFractionDigits: decimals })
            : num.toLocaleString(undefined)
    }
    // Replace each run of digits (optionally with a decimal part) in the element's text. A leading
    // sign/word stays put, so "+1234" → "+1,234" and "1234 this month" → "1,234 this month".
    window.Diurnal.formatNumbers = function (rootParam) {
        const root = rootParam || document.body
        const els = []
        if (root.classList && root.classList.contains('js-num')) { els.push(root) }
        if (root.querySelectorAll) { Array.prototype.push.apply(els, root.querySelectorAll('.js-num')) }
        els.forEach(function (el) {
            if (el.dataset.numDone) { return }   // idempotent: don't re-group an already-grouped value
            el.dataset.numDone = '1'
            const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null)
            const nodes = []
            let node
            while ((node = walker.nextNode())) { nodes.push(node) }
            nodes.forEach(function (textNode) {
                textNode.nodeValue = textNode.nodeValue.replace(/\d+(?:\.\d+)?/g, function (match) {
                    const dot = match.indexOf('.')
                    return fmt(Number(match), dot === -1 ? 0 : match.length - dot - 1)
                })
            })
        })
    }
    // Initial render (the body is fully parsed above this script), then again for any HTMX-swapped
    // content (e.g. the stats list paginating in).
    window.Diurnal.formatNumbers(document.body)
    document.body.addEventListener('htmx:afterSwap', function (e) { window.Diurnal.formatNumbers(e.target) })
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
// rows are server-rendered from net.zodac.diurnal.auth.PasswordConstraints, so this only evaluates —
// the check tokens below (minLength / maxLength) MUST match Constraint.type. One handler serves both
// the registration and settings pages; each page has a single opted-in field.
(function () {
    function met(type, bound, len) {
        if (type === 'minLength') {return len >= bound}
        if (type === 'maxLength') {return len <= bound}
        return true                                     // unknown token: never block, just show it
    }
    function refresh(tip, len) {
        tip.querySelectorAll('[data-pw-check]').forEach(function (row) {
            const ok = met(row.getAttribute('data-pw-type'), parseInt(row.getAttribute('data-pw-value'), 10), len)
            row.classList.toggle('text-success', ok)
            row.classList.toggle('text-danger', !ok)
            const icon = row.querySelector('[data-pw-icon]')
            if (icon) {icon.textContent = ok ? '✓' : '✗'}
        })
    }
    document.querySelectorAll('[data-pw-tooltip]').forEach(function (tip) {
        const input = document.getElementById(tip.getAttribute('data-pw-for'))
        if (!input) {return}
        const update = function () { refresh(tip, input.value.length) }
        input.addEventListener('focus', function () { update(); tip.classList.add('pw-open') })
        input.addEventListener('input', update)
        input.addEventListener('blur', function () { tip.classList.remove('pw-open') })
        update()                                        // colour correctly before first reveal
    })
})()

// ── Login page: drop the per-tab dashboard day selection ──────────────────────
// Reaching the login page ends the working session: an explicit logout, a session-cookie expiry
// redirect, or a different user about to log in on this tab. Drop the selection so it is tied to
// the authentication session, never leaking across logins. Guarded to the login page only (path
// check, not a data-page marker — avoids threading a new param through every full-page template).
if (window.location.pathname === '/login') {
    try { sessionStorage.removeItem('diurnal.selectedDate') } catch (e) { /* ignore */ }
}

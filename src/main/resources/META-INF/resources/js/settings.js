/*
 * Settings page behaviour (extracted from settings.html so it rides the immutable, content-hashed
 * cache instead of being re-parsed on every no-cache navigation).
 *
 * Served as /js/settings.<hash>.js in production (hashed + `immutable` in the Dockerfile, baked into
 * AppInfo.jsSettingsFile) and /js/settings.js in dev. Loaded only on the settings page, as a classic
 * script at the end of <body>, after the shared /js/app.js. Every control below is wired via
 * addEventListener at the bottom of this file — none of the markup carries inline on*= handlers.
 */

// Account card status line: green "Saved" or a red error, shown in the SAME header slot so
// an error never nudges the layout. Colour + text are set here per call.
function showAccountStatus(message, isError) {
    const el = document.getElementById('account-status')
    el.textContent = message
    el.classList.toggle('text-success', !isError)
    el.classList.toggle('text-danger', isError)
    el.classList.add('opacity-100')
    clearTimeout(el._hideTimer)
    // Errors linger longer than the "Saved" confirmation so they can actually be read (mirrors the
    // preference-card indicator timing below).
    el._hideTimer = setTimeout(function () { el.classList.remove('opacity-100') }, isError ? 4000 : 2000)
}

window.startEditDisplayName = function () {
    document.getElementById('display-name-view').classList.add('hidden')
    const form = document.getElementById('account-form')
    form.classList.remove('hidden')
    const input = document.getElementById('displayName')
    input.focus()
    input.select()
}

window.cancelEditDisplayName = function () {
    document.getElementById('account-form').classList.add('hidden')
    document.getElementById('display-name-view').classList.remove('hidden')
    document.getElementById('displayName').value =
        document.getElementById('display-name-text').textContent
}

// "Log out everywhere" in-place confirm (mirrors the data-table delete-confirm): the trigger
// reveals a red-ringed confirm state; Cancel restores the resting view. The confirm itself is a
// plain POST form, so submitting it navigates through the server's 303 redirect to /login.
window.armLogoutAll = function () {
    document.getElementById('logout-all-view').classList.add('hidden')
    document.getElementById('logout-all-confirm').classList.remove('hidden')
}

window.cancelLogoutAll = function () {
    document.getElementById('logout-all-confirm').classList.add('hidden')
    document.getElementById('logout-all-view').classList.remove('hidden')
}

document.getElementById('display-name-edit-btn').addEventListener('click', window.startEditDisplayName)
document.getElementById('display-name-cancel-btn').addEventListener('click', window.cancelEditDisplayName)
document.getElementById('logout-all-arm-btn').addEventListener('click', window.armLogoutAll)
document.getElementById('logout-all-cancel-btn').addEventListener('click', window.cancelLogoutAll)

document.getElementById('account-form').addEventListener('htmx:afterRequest', function (e) {
    if (e.detail.successful) {
        const newName = document.getElementById('displayName').value.trim()
        document.getElementById('display-name-text').textContent = newName
        const navDesktop = document.getElementById('nav-display-name')
        if (navDesktop) {navDesktop.textContent = newName}
        const navMobile = document.getElementById('nav-display-name-mobile')
        if (navMobile) {navMobile.textContent = newName}
        window.cancelEditDisplayName()
        showAccountStatus('Saved', false)
    }
})

// ── Password change ──────────────────────────────────────────────
// A three-step in-place flow that never moves the surrounding controls: view → enter the
// CURRENT password (step 1) → enter the new password (step 2) → re-enter to confirm (step 3).
// Every step occupies the exact same slot as the read-mode value (all share `.settings-field`),
// so the row never grows or reflows. The current password is required so a hijacked session
// cannot silently reset it; the server verifies it on the final POST.

const passwordEls = {
    view:       document.getElementById('password-view'),
    currentForm: document.getElementById('password-current-form'),
    newForm:    document.getElementById('password-new-form'),
    confirm:    document.getElementById('password-confirm-form')
}

// The "Next"/"Save" button of each step, gated below so a step can't be advanced until its field
// is correctly filled (current: non-empty; new: meets every requirement; confirm: matches new).
const passwordBtns = {
    current: passwordEls.currentForm.querySelector('button[type="submit"]'),
    new:     passwordEls.newForm.querySelector('button[type="submit"]'),
    confirm: passwordEls.confirm.querySelector('button[type="submit"]')
}
const newPasswordTip = document.querySelector('[data-pw-tooltip][data-pw-for="newPassword"]')

// Per-step gate predicates. These are a UX affordance only — the server re-verifies the current
// password and the match authoritatively on the final POST, and the error banner still fires if the
// disabled state is ever bypassed (no JS, a forced submit).
function currentStepValid() {
    return document.getElementById('currentPassword').value.length > 0
}
function newStepValid() {
    const len = document.getElementById('newPassword').value.length
    // Fall back to non-empty if the popover is somehow absent, mirroring the server's minimum.
    if (!newPasswordTip) {return len > 0}
    // Evaluate the same server-rendered requirement rows the popover in app.js recolours. Read straight
    // off the DOM (no cross-file dependency, so a stale/cached app.js can't break this), applying the
    // same minLength/maxLength tokens — which mirror net.zodac.diurnal.auth.PasswordConstraints.Constraint.type.
    return Array.prototype.every.call(newPasswordTip.querySelectorAll('[data-pw-check]'), function (row) {
        const bound = parseInt(row.getAttribute('data-pw-value'), 10)
        const type = row.getAttribute('data-pw-type')
        if (type === 'minLength') {return len >= bound}
        if (type === 'maxLength') {return len <= bound}
        return true                                     // unknown token: never block
    })
}
function confirmStepValid() {
    const confirm = document.getElementById('confirmPassword').value
    return confirm.length > 0 && confirm === document.getElementById('pendingPassword').value
}

// Reflect the current field values onto the three step buttons' disabled state. Called on every
// keystroke in the password area and after each step transition (which clears the next field).
function syncPasswordButtons() {
    passwordBtns.current.disabled = !currentStepValid()
    passwordBtns.new.disabled = !newStepValid()
    passwordBtns.confirm.disabled = !confirmStepValid()
}

// Reset an input to hidden (type=password), clear its value, and restore its eye toggle to
// the "reveal" icon.
function resetRevealInput(input) {
    if (!input) {return}
    input.value = ''
    input.type = 'password'
    const btn = input.parentElement.querySelector('.settings-eye')
    if (btn) {setEyeState(btn, false)}
}

// Point an eye toggle at the correct icon: `revealed` true shows the slashed eye (click to
// hide), false shows the plain eye (click to reveal).
function setEyeState(btn, revealed) {
    btn.querySelector('[data-eye-show]').classList.toggle('hidden', revealed)
    btn.querySelector('[data-eye-hide]').classList.toggle('hidden', !revealed)
}

// Toggle an input between password/text and flip its eye icon to match.
window.toggleReveal = function (inputId, btn) {
    const input = document.getElementById(inputId)
    const revealed = input.type === 'password'
    input.type = revealed ? 'text' : 'password'
    setEyeState(btn, revealed)
}

// Show step 1 (current password) and hide the other password states. Used both when opening
// the editor and (belt-and-braces) if the final POST ever rejects the current password.
function showCurrentStep() {
    passwordEls.view.classList.add('hidden')
    passwordEls.newForm.classList.add('hidden')
    passwordEls.confirm.classList.add('hidden')
    passwordEls.currentForm.classList.remove('hidden')
    const input = document.getElementById('currentPassword')
    resetRevealInput(input)
    syncPasswordButtons()
    input.focus()
}

window.startEditPassword = function () {
    showCurrentStep()
}

// Cancel from any step: clear everything (including the stashed values) and return to the read
// view.
window.cancelEditPassword = function () {
    passwordEls.currentForm.classList.add('hidden')
    passwordEls.newForm.classList.add('hidden')
    passwordEls.confirm.classList.add('hidden')
    passwordEls.view.classList.remove('hidden')
    resetRevealInput(document.getElementById('currentPassword'))
    resetRevealInput(document.getElementById('newPassword'))
    resetRevealInput(document.getElementById('confirmPassword'))
    document.getElementById('pendingCurrent').value = ''
    document.getElementById('pendingPassword').value = ''
    syncPasswordButtons()
}

document.getElementById('password-edit-btn').addEventListener('click', window.startEditPassword)
document.querySelectorAll('.password-cancel-btn').forEach(function (btn) {
    btn.addEventListener('click', window.cancelEditPassword)
})
document.querySelectorAll('[data-reveal-target]').forEach(function (btn) {
    btn.addEventListener('click', function () { window.toggleReveal(btn.dataset.revealTarget, btn) })
})

// Keep each step's button in sync as the user types (current non-empty / new meets every rule /
// confirm matches). The confirm field also re-checks against the stashed new password.
;['currentPassword', 'newPassword', 'confirmPassword'].forEach(function (id) {
    document.getElementById(id).addEventListener('input', syncPasswordButtons)
})
syncPasswordButtons()   // reflect the initial (empty → disabled) state on first paint

// Step 1 → 2, run only once the server has confirmed the current password: stash it for the
// final POST and swap to the new-password input in the same slot.
function advanceToNewPassword() {
    document.getElementById('pendingCurrent').value = document.getElementById('currentPassword').value
    passwordEls.currentForm.classList.add('hidden')
    passwordEls.newForm.classList.remove('hidden')
    const input = document.getElementById('newPassword')
    resetRevealInput(input)
    syncPasswordButtons()
    input.focus()
}

// The Next button on step 1 posts the current password to /settings/password/verify via fetch (NOT
// htmx): a wrong current password is an expected, handled outcome, and htmx would log every 4xx to
// the console unsuppressably (it console.errors before the client can react). fetch keeps that 422
// off the console while still showing the inline error, mirroring the login/register cards. Advancing
// to step 2 is gated on the check succeeding (204): a wrong (or empty) password keeps the user here
// with an inline error, so they never fill in the new password for nothing. This is only a UX aid —
// updatePassword re-verifies the current password authoritatively on the final POST (the password
// could change between steps, and a client could skip this call entirely).
passwordEls.currentForm.addEventListener('submit', function (e) {
    e.preventDefault()
    const form = passwordEls.currentForm
    fetch(form.action, {
        method: 'POST',
        body: new URLSearchParams(new FormData(form)),
        headers: { 'Accept': 'text/plain' }
    }).then(function (resp) {
        if (resp.status === 204) {
            advanceToNewPassword()
            return undefined
        }
        return resp.text().then(function (body) {
            showAccountStatus(body || 'Current password is incorrect', true)
        })
    }).catch(function () {
        showAccountStatus('Something went wrong. Please try again.', true)
    })
})

// Step 2 → 3: validate non-empty (the only rule), stash the value for the confirm POST, then
// swap the input for the confirm input in the same slot.
window.submitNewPassword = function (event) {
    event.preventDefault()
    const value = document.getElementById('newPassword').value
    if (value.length === 0) {
        showAccountStatus('Password cannot be empty', true)
        return false
    }
    document.getElementById('pendingPassword').value = value
    passwordEls.newForm.classList.add('hidden')
    passwordEls.confirm.classList.remove('hidden')
    const input = document.getElementById('confirmPassword')
    resetRevealInput(input)
    syncPasswordButtons()
    input.focus()
    return false
}

passwordEls.newForm.addEventListener('submit', window.submitNewPassword)

// Step 3 result: the final POST is fetch()-submitted too (same rationale as step 1 — keep the 422 a
// wrong current password / mismatch returns off the console). On success (200) return to the read
// view and flash "Saved". On failure show the server's message in red; a wrong CURRENT password can't
// be fixed from the confirm step, so send the user back to step 1 (matched by the server's "current
// password" wording). A simple mismatch is corrected in place, so the confirm field is left untouched.
passwordEls.confirm.addEventListener('submit', function (e) {
    e.preventDefault()
    const form = passwordEls.confirm
    fetch(form.action, {
        method: 'POST',
        body: new URLSearchParams(new FormData(form)),
        headers: { 'Accept': 'text/plain' }
    }).then(function (resp) {
        if (resp.ok) {
            window.cancelEditPassword()
            showAccountStatus('Saved', false)
            return undefined
        }
        return resp.text().then(function (body) {
            const msg = body || 'Passwords do not match'
            showAccountStatus(msg, true)
            if (/current password/i.test(msg)) {
                showCurrentStep()
            }
        })
    }).catch(function () {
        showAccountStatus('Something went wrong. Please try again.', true)
    })
})

// Each preference control PATCHes itself, so update the status indicator on the card that
// owns whichever control just saved. e.detail.elt is the element that issued the request
// (a radiogroup div, a <select>, a checkbox or the items-per-page field); its .card is the
// one to flash. On success it shows a green "Saved"; on a rejected value (e.g. an
// out-of-range items-per-page → 422) it shows the server's message in red. The account
// display-name save (a different endpoint) also bubbles here, but its card has no
// [data-saved] — it flashes #account-status itself — so it is ignored.
document.getElementById('prefs-form').addEventListener('htmx:afterRequest', function (e) {
    const card = e.detail.elt.closest('.card')
    const indicator = card ? card.querySelector('[data-saved]') : null
    if (!indicator) {return}
    clearTimeout(indicator._hideTimer)
    if (e.detail.successful) {
        indicator.textContent = 'Saved'
        indicator.classList.remove('text-danger')
        indicator.classList.add('text-success')
    } else {
        indicator.textContent = e.detail.xhr.responseText || 'Could not save'
        indicator.classList.remove('text-success')
        indicator.classList.add('text-danger')
    }
    indicator.classList.add('opacity-100')
    // Errors linger a little longer than the "Saved" confirmation so they can be read.
    indicator._hideTimer = setTimeout(function () {
        indicator.classList.remove('opacity-100')
    }, e.detail.successful ? 2000 : 4000)
})

// Cross-fade into the chosen theme using the shared applyTheme helper (layout.html).
document.getElementById('theme-options').addEventListener('change', function (e) {
    if (e.target.name === 'theme') {
        window.Diurnal.applyTheme(e.target.value, { transition: true, resetBody: true })
    }
})

// Toggle the page font live when the Font radio changes so the rest of Settings updates
// instantly (toggling `.font-nova` on <html>, the same class layout.html renders).
document.getElementById('prefs-form').addEventListener('change', function (e) {
    if (e.target.name === 'font') {
        document.documentElement.classList.toggle('font-nova', e.target.value === 'nova')
    }
})

// Full-size preview overlay for the Theme / Calendar-style tiles. It is a per-setting
// gallery: opening any tile's (!) lets you step (prev/next) through the OTHER options of
// the SAME picker only — the tiles are scoped by their shared data-preview-group.
let previewTiles = []     // the gallery currently on screen (one picker's tiles)
let previewIndex = 0     // which tile of that gallery is showing
let previewModalImgs = [] // one <img> per tile, created at open time; toggled by display
const EASE = 'cubic-bezier(0.65, 0, 0.35, 1)' // even ease-in-out used by the expand/collapse

// Cancel every animation on the modal/panel so each open or close starts from a clean
// slate AND, crucially, no `fill: forwards` animation is left attached at rest — a
// lingering one keeps the panel on a GPU layer, which makes the icon buttons inside it
// sub-pixel-snap the first time they're hovered.
function clearPreviewAnims() {
    document.getElementById('preview-modal').getAnimations().forEach(function (a) { a.cancel() })
    document.getElementById('preview-modal-panel').getAnimations().forEach(function (a) { a.cancel() })
}

// Returns the URL of the thumbnail <img> inside a preview tile.
// Always normalises to an absolute URL for reliable comparison and src assignment.
function previewSrcFor(tile) {
    function toAbs(raw) { return raw ? new URL(raw, document.baseURI).href : '' }
    const imgs = tile.querySelectorAll('img')
    for (let i = 0; i < imgs.length; i++) {
        const el = imgs[i]
        const visible = el.checkVisibility
            ? el.checkVisibility({ visibilityProperty: true, opacityProperty: true })
            : (el.offsetParent !== null && getComputedStyle(el).visibility !== 'hidden')
        if (visible) {return toAbs(el.currentSrc || el.src || el.dataset.src || '')}
    }
    return imgs.length ? toAbs(imgs[0].currentSrc || imgs[0].src || imgs[0].dataset.src || '') : ''
}

// Toggle which pre-loaded modal <img> is visible without mutating any src.
function renderPreview() {
    previewModalImgs.forEach(function (img, i) {
        img.style.display = i === previewIndex ? '' : 'none'
    })
    document.getElementById('preview-modal-title').textContent = previewTiles[previewIndex].dataset.previewTitle
    const multi = previewTiles.length > 1
    document.getElementById('preview-prev').classList.toggle('hidden', !multi)
    document.getElementById('preview-next').classList.toggle('hidden', !multi)
}

window.openPreview = function (btn) {
    const tile = btn.closest('[data-preview-group]')
    const group = tile.dataset.previewGroup
    previewTiles = Array.prototype.slice.call(
        document.querySelectorAll(`[data-preview-group="${  group  }"]`))
    previewIndex = previewTiles.indexOf(tile)
    // Create one <img> per gallery tile, each loaded exactly once at open time.
    // Subsequent previewStep calls toggle display rather than mutating src, so returning
    // to a tile that was already shown never triggers a second network request.
    // Covers all three pickers (theme / calendar / font) through a single code path.
    const container = document.getElementById('preview-modal-imgs')
    container.innerHTML = ''
    const imgClass = container.dataset.imgClass
    previewModalImgs = previewTiles.map(function (t) {
        const img = document.createElement('img')
        img.src = previewSrcFor(t)
        img.alt = t.dataset.previewTitle
        img.className = imgClass
        img.style.display = 'none'
        if (img.decode) {img.decode().catch(function () {})}
        container.appendChild(img)
        return img
    })
    renderPreview()

    const modal = document.getElementById('preview-modal')
    const panel = document.getElementById('preview-modal-panel')
    modal.classList.remove('hidden')
    modal.classList.add('flex')
    // Drive the expand with the Web Animations API rather than CSS transitions: WAAPI
    // runs on the compositor and doesn't depend on the browser painting a collapsed
    // start frame first, so there's no pop/stutter. ease-in-out grows evenly from 0.
    clearPreviewAnims()
    const fade = modal.animate([{ opacity: 0 }, { opacity: 1 }],
        { duration: 350, easing: 'ease-out', fill: 'forwards' })
    // Promote opacity to the base style and drop the animation so the backdrop isn't
    // left composited (the class makes the resting state, then cancel releases the layer).
    fade.onfinish = function () { modal.classList.remove('opacity-0'); fade.cancel() }
    const grow = panel.animate([{ transform: 'scale(0)' }, { transform: 'scale(1)' }],
        { duration: 650, easing: EASE, fill: 'forwards' })
    // scale(1) == no transform, so cancelling on finish is visually identical but releases
    // the GPU layer — without this the buttons inside sub-pixel-snap on first hover.
    grow.onfinish = function () { grow.cancel() }
}

// Select the currently-previewed option (same as ticking its radio) and close.
window.applyPreview = function () {
    const radio = previewTiles[previewIndex].querySelector('input[type="radio"]')
    if (radio && !radio.checked) {
        radio.checked = true
        // Bubbles to the radiogroup's hx-patch (which saves it) and the theme listener (which applies it).
        radio.dispatchEvent(new Event('change', { bubbles: true }))
    }
    window.closePreview()
}

window.previewStep = function (delta) {
    if (previewTiles.length < 2) {return}
    previewIndex = (previewIndex + delta + previewTiles.length) % previewTiles.length
    renderPreview()
}

window.closePreview = function () {
    const modal = document.getElementById('preview-modal')
    const panel = document.getElementById('preview-modal-panel')
    if (modal.classList.contains('hidden')) {return}
    clearPreviewAnims()
    // Exact time-reverse of open: the panel zooms out over the full 650ms while the
    // backdrop stays up, and the fade only runs in the LAST 350ms (delay 300ms). Open
    // fades IN first then zooms; reversed, close zooms out then fades — so you actually
    // see the collapse instead of a fast fade hiding it.
    modal.animate([{ opacity: 1 }, { opacity: 0 }],
        { duration: 350, delay: 300, easing: 'ease-in', fill: 'forwards' })
    const shrink = panel.animate([{ transform: 'scale(1)' }, { transform: 'scale(0)' }],
        { duration: 650, easing: EASE, fill: 'forwards' })
    shrink.onfinish = function () {
        modal.classList.add('hidden')
        modal.classList.remove('flex')
        modal.classList.add('opacity-0') // restore the resting hidden-opacity for next open
        clearPreviewAnims()               // drop the forwards-fills so nothing stays composited
        document.getElementById('preview-modal-imgs').innerHTML = ''
        previewModalImgs = []
    }
}

document.querySelectorAll('.preview-info').forEach(function (btn) {
    btn.addEventListener('click', function () { window.openPreview(btn) })
})
document.getElementById('preview-modal').addEventListener('click', window.closePreview)
document.getElementById('preview-modal-panel').addEventListener('click', function (e) { e.stopPropagation() })
document.getElementById('preview-apply').addEventListener('click', window.applyPreview)
document.getElementById('preview-close').addEventListener('click', window.closePreview)
document.getElementById('preview-prev').addEventListener('click', function () { window.previewStep(-1) })
document.getElementById('preview-next').addEventListener('click', function () { window.previewStep(1) })

document.addEventListener('keydown', function (e) {
    if (document.getElementById('preview-modal').classList.contains('hidden')) {return}
    if (e.key === 'Escape') {window.closePreview()}
    else if (e.key === 'ArrowLeft') {window.previewStep(-1)}
    else if (e.key === 'ArrowRight') {window.previewStep(1)}
})

// Numeric preference control (Items-per-page, Decimal places): preset pills (coarse,
// one-click) + a −/+ stepper (fine, ±1) both drive one hidden number field. Every
// interaction saves instantly via the field's custom `save` event — no blur-to-commit, no
// Save button.
//
// The pills and ± stepper are bounded controls: they can only ever produce a value in
// [min, max], so they always succeed. Direct typing, however, can be invalid (out of range
// or non-numeric): that raw value is sent as-is and the server REJECTS it (422). On
// rejection the field is reverted to the last accepted value and the card shows the error in
// red (the shared prefs handler above); it is never silently coerced.
function wireNumericPref(opts) {
    const field   = document.getElementById(opts.field)
    const minus   = document.getElementById(opts.minus)
    const plus    = document.getElementById(opts.plus)
    const presets = document.getElementById(opts.presets)
    const MIN = opts.min
    const MAX = opts.max
    // The last value the server accepted — the value to restore if a typed entry is rejected.
    let lastGood = field.value

    function clamp(n) {
        if (isNaN(n) || n < MIN) { return MIN }
        if (n > MAX) { return MAX }
        return n
    }

    // Highlight the preset pill matching the current value (none, if it is a custom value).
    function syncPills() {
        const val = parseInt(field.value, 10)
        presets.querySelectorAll('.num-pref-pill').forEach(function (pill) {
            const active = parseInt(pill.dataset.value, 10) === val
            pill.classList.toggle('num-pref-pill-active', active)
            pill.setAttribute('aria-pressed', active ? 'true' : 'false')
        })
    }

    // Bounded controls (pills, ±): the value is clamped into range, so it always validates.
    function setValid(n) {
        field.value = clamp(n)
        syncPills()
        htmx.trigger(field, 'save')
    }

    minus.addEventListener('click', function () { setValid(parseInt(field.value, 10) - 1) })
    plus.addEventListener('click',  function () { setValid(parseInt(field.value, 10) + 1) })
    presets.addEventListener('click', function (e) {
        const pill = e.target.closest('.num-pref-pill')
        if (pill) { setValid(parseInt(pill.dataset.value, 10)) }
    })
    // Direct typing / native arrow keys: send the RAW value (no clamping) so the server
    // validates it. The `save` event (not `change`) carries the PATCH, so this handler is
    // not re-entered by the save itself.
    field.addEventListener('change', function () {
        syncPills()
        htmx.trigger(field, 'save')
    })
    // Save result: remember an accepted value, or revert to the last accepted one on
    // rejection (the red error message is shown by the shared prefs handler above).
    field.addEventListener('htmx:afterRequest', function (e) {
        if (e.detail.successful) {
            lastGood = field.value
        } else {
            field.value = lastGood
        }
        syncPills()
    })

    syncPills()
}

wireNumericPref({ field: 'pageSize', minus: 'pageSizeMinus', plus: 'pageSizePlus',
                  presets: 'pageSizePresets', min: 1, max: 100 })
wireNumericPref({ field: 'decimalPlaces', minus: 'decimalPlacesMinus', plus: 'decimalPlacesPlus',
                  presets: 'decimalPlacesPresets', min: 0, max: 5 })

// Assign src from data-src for all preview thumbnails so fetches are deferred until
// after the page renders. decode() is called so the lightbox open is flash-free.
document.querySelectorAll('#prefs-form .preview-img').forEach(function (el) {
    if (el.dataset.src) {el.src = el.dataset.src}
    if (el.decode) {el.decode().catch(function () {})}
});

// ── Action stats: reorder (handle) + toggle (short press) + tooltip (hover / long press) ──
// One handler owns the whole list, so the three gestures never collide:
//   • Reorder — a drag that STARTS on the handle (Pointer Events, so it works with mouse AND
//     touch; the native HTML5 drag-and-drop API never fires on touch). Releasing after a move
//     dispatches the custom `reorder` event that drives the list's htmx PATCH.
//   • Toggle — a SHORT press anywhere on a row EXCEPT the handle flips that row's (visual-only,
//     pointer-events-none) checkbox and dispatches a `change`, so the same PATCH fires. The
//     shared #prefs-form handler flashes "Saved". Because the PATCH sends every row's hidden
//     `statsOrder`, disabling a stat keeps its position; only reordering changes it.
//   • Tooltip — the description shows on hover (desktop, pure CSS via `group-hover`) or on a
//     LONG press of the text (touch, adding `.tip-open`). A long press opens the tooltip WITHOUT
//     toggling or reordering.
// Shared `suppressClick` guarantees a completed drag or a long-press never becomes a toggle.
(function () {
    const list = document.getElementById('stats-fields-list')
    if (!list) {return}

    const LONG_PRESS_MS = 500
    let dragged = null        // row being reordered (drag started on its handle)
    let moved = false         // whether that drag actually moved a row
    let tipTimer = null       // pending long-press-to-open-tooltip timer
    let suppressClick = false // set by a drag or a long-press so the next click won't toggle

    // The sibling the dragged row should sit BEFORE for a pointer at height y — the first
    // row whose vertical midpoint is below y. null → past the last row (append).
    function referenceRow(y) {
        const rows = list.querySelectorAll('.stats-field-row')
        for (let i = 0; i < rows.length; i++) {
            if (rows[i] === dragged) {continue}
            const rect = rows[i].getBoundingClientRect()
            if (y < rect.top + rect.height / 2) {return rows[i]}
        }
        return null
    }

    function closeTips() {
        list.querySelectorAll('.stats-field-label.tip-open').forEach(function (el) {
            el.classList.remove('tip-open')
        })
    }

    list.addEventListener('pointerdown', function (e) {
        suppressClick = false
        const handle = e.target.closest('.stats-field-handle')
        if (handle) {
            const row = handle.closest('.stats-field-row')
            if (!row) {return}
            e.preventDefault()
            dragged = row
            moved = false
            row.classList.add('opacity-50')
            // Capture so pointermove/up keep firing even if the pointer leaves the handle.
            handle.setPointerCapture(e.pointerId)
            return
        }
        // Otherwise arm a long-press on the text → its description tooltip.
        const label = e.target.closest('.stats-field-label')
        if (!label) {return}
        tipTimer = setTimeout(function () {
            tipTimer = null
            suppressClick = true   // this press is a long-press: swallow the toggle click
            closeTips()
            label.classList.add('tip-open')
        }, LONG_PRESS_MS)
    })

    list.addEventListener('pointermove', function (e) {
        if (dragged) {
            e.preventDefault()
            moved = true
            const before = referenceRow(e.clientY)
            if (before !== dragged) {list.insertBefore(dragged, before)}
            return
        }
        // Any movement means a scroll/drag, not a long press.
        if (tipTimer) { clearTimeout(tipTimer); tipTimer = null }
    })

    function endPress() {
        if (dragged) {
            dragged.classList.remove('opacity-50')
            dragged = null
            if (moved) {
                suppressClick = true   // a completed drag must not also toggle
                list.dispatchEvent(new CustomEvent('reorder', { bubbles: true }))
            }
            return
        }
        if (tipTimer) { clearTimeout(tipTimer); tipTimer = null }
    }
    list.addEventListener('pointerup', endPress)
    list.addEventListener('pointercancel', endPress)

    list.addEventListener('click', function (e) {
        if (e.detail === 0) {return}                       // keyboard (space) → native toggle
        if (e.target.closest('.stats-field-handle')) {return}
        if (suppressClick) { suppressClick = false; return }
        const row = e.target.closest('.stats-field-row')
        if (!row) {return}
        const checkbox = row.querySelector('input[type="checkbox"]')
        if (!checkbox || checkbox.disabled) {return}      // mandatory ("Always shown")
        checkbox.checked = !checkbox.checked
        checkbox.dispatchEvent(new Event('change', { bubbles: true }))
    })

    // Stop the native long-press / right-click menu on the text from fighting the tooltip.
    list.addEventListener('contextmenu', function (e) {
        if (e.target.closest('.stats-field-label')) {e.preventDefault()}
    })

    // On touch (no hover-out) a press outside an open tooltip dismisses it.
    document.addEventListener('pointerdown', function (e) {
        if (!e.target.closest('.stats-field-label.tip-open')) {closeTips()}
    })
})()

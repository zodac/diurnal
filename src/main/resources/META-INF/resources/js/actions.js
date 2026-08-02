/*
 * Actions page behaviour (extracted from actions.html so it rides the immutable, content-hashed
 * cache instead of being re-parsed on every no-cache navigation).
 *
 * Served as /js/actions.<hash>.js in production (hashed + `immutable` in the Dockerfile, baked into
 * AppInfo.jsActionsFile) and /js/actions.js in dev. Loaded only on the actions page, as a classic
 * script at the end of <body>, after the shared /js/app.js.
 */

// New-action form: on a successful add (200), clear any stale "no actions"/error state and grow
// the "Showing X of Y" counters + the section's UNFILTERED data-total by one (mirroring the
// htmx:beforeSwap delete handler below in reverse). Attached directly to the form (not
// delegated), so `this` is the form. A duplicate-name rejection (409) is handled by the
// htmx:beforeSwap opt-in below — the server's HX-Retarget/HX-Reswap headers route its banner into
// #action-error (the same mechanism as admin-users.js).
document.getElementById('new-action-form').addEventListener('htmx:afterRequest', function (event) {
    if (event.detail.xhr.status === 200) {
        document.getElementById('action-error').innerHTML = ''
        this.reset()
        const er = document.getElementById('actions-empty-row')
        if (er) {er.remove()}
        const sh = document.getElementById('showing-shown')
        const tot = document.getElementById('showing-total')
        if (sh) {sh.textContent = parseInt(sh.textContent, 10) + 1}
        if (tot) {tot.textContent = parseInt(tot.textContent, 10) + 1}
        const section = document.getElementById('actions-section')
        if (section) {
            section.dataset.total = parseInt(section.dataset.total, 10) + 1
            section.classList.remove('hidden')
        }
    }
})

// Randomise: fetch a suggested colour (the server picks one unlike every colour the user already
// uses — the client only ever sees the current page of actions, so it cannot make that choice) and
// drop it into the colour input beside the button. Delegated from the body because the edit-row
// buttons arrive with every swapped-in row, so a per-element listener would miss them; the input is
// found within the button's own scope — its <form> on the new-action card, or its <td> in a table
// row (whose picker belongs to the row form via form=, not by nesting). Plain fetch rather than
// htmx: the target is an <input> value, not markup, and a failed suggestion is a no-op (the picker
// keeps whatever it showed) rather than an error the user has to clear. The button is disabled for
// the duration so an impatient double-click can't race two suggestions into the same input.
document.body.addEventListener('click', function (e) {
    const btn = e.target.closest('[data-random-colour]')
    if (!btn) {return}
    const scope = btn.closest('form, td')
    const input = scope ? scope.querySelector('input[name="colour"]') : null
    if (!input) {return}
    btn.disabled = true
    fetch(btn.dataset.randomColourUrl, {headers: {'Accept': 'application/json'}})
        .then(function (resp) { return resp.ok ? resp.json() : null })
        .then(function (body) { if (body && body.colour) {input.value = body.colour} })
        .catch(function () { /* keep the current colour */ })
        .finally(function () { btn.disabled = false })
})

// The delete endpoint returns 204 with an empty body. Handle the surgical removal here,
// rather than on the button itself (its outerHTML swap removes the button — and its own
// after-request listener — before that listener would fire):
//   1. htmx skips the swap on a 204 by default, but we rely on the outerHTML swap to remove
//      the deleted row, so force the swap.
//   2. Decrement the filtered "Showing X of Y" counter to match the removed row, and the
//      section's UNFILTERED data-total (one fewer action exists overall).
//   3. If no actions remain in the system (data-total <= 0), hide the whole search+list
//      section so only the "New action" card shows — matching a fresh empty-account render.
//   4. Otherwise, if the current (filtered) view is now empty — i.e. a search matches none of
//      the remaining actions — keep the table visible but add an empty-state row, so the
//      surgical path matches what a fresh search render shows.
document.body.addEventListener('htmx:beforeSwap', function (e) {
    // Guard failures (e.g. a duplicate action name) come back as a 409 with HX-Retarget/HX-Reswap
    // pointing at #action-error. htmx skips the swap on non-2xx responses by default, so opt the
    // error in — the retarget/reswap headers then route it to the banner (the same mechanism as
    // admin-users.js, so every 409 banner rides one code path).
    if (e.detail.xhr.status === 409) {
        e.detail.shouldSwap = true
        e.detail.isError = false
        return
    }
    if (e.detail.xhr.status === 204) {
        e.detail.shouldSwap = true
        const sh = document.getElementById('showing-shown')
        const tot = document.getElementById('showing-total')
        if (sh) {sh.textContent = parseInt(sh.textContent, 10) - 1}
        const filteredLeft = tot ? parseInt(tot.textContent, 10) - 1 : 0
        if (tot) {tot.textContent = filteredLeft}

        const section = document.getElementById('actions-section')
        const totalLeft = section ? parseInt(section.dataset.total, 10) - 1 : 0
        if (section) {section.dataset.total = totalLeft}

        if (totalLeft <= 0) {
            if (section) {section.classList.add('hidden')}
        } else if (filteredLeft <= 0) {
            const tbody = document.getElementById('actions-tbody')
            if (tbody && !document.getElementById('actions-empty-row')) {
                tbody.insertAdjacentHTML('beforeend',
                    '<tr id="actions-empty-row"><td colspan="3" class="dt-empty">No actions match your search.</td></tr>')
            }
        }
    }
})

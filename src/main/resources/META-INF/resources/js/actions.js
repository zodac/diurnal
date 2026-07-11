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
// htmx:beforeSwap delete handler below in reverse); on a duplicate-name rejection (409), surface
// the server's error banner. Attached directly to the form (not delegated), so `this` is the form.
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
    } else if (event.detail.xhr.status === 409) {
        document.getElementById('action-error').innerHTML = event.detail.xhr.responseText
    }
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

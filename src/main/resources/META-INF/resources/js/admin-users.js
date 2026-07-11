/*
 * Admin users page behaviour (extracted from admin-users.html so it rides the immutable,
 * content-hashed cache instead of being re-parsed on every no-cache navigation).
 *
 * Served as /js/admin-users.<hash>.js in production (hashed + `immutable` in the Dockerfile, baked
 * into AppInfo.jsAdminFile) and /js/admin-users.js in dev. Loaded only on the admin users page, as
 * a classic script at the end of <body>, after the shared /js/app.js.
 */

// Guard failures (e.g. "Cannot delete the last administrator") come back as a 409 with
// HX-Retarget/HX-Reswap pointing at #admin-error. htmx skips the swap on non-2xx responses
// by default, so opt the error in — the retarget/reswap headers then route it to the banner.
document.body.addEventListener('htmx:beforeSwap', function (e) {
    if (e.detail.xhr.status === 409) {
        e.detail.shouldSwap = true
        e.detail.isError = false
        // A rejected delete (e.g. last administrator) leaves its row waiting on a confirmation
        // that can never succeed — disarm it. Deferred so it runs after htmx has settled this
        // response, and scoped to /delete so a rejected role change leaves its edit form open.
        if ((e.detail.xhr.responseURL || '').endsWith('/delete')) {
            setTimeout(function () { window.dtClearArmedRows(null) }, 0)
        }
    }
})

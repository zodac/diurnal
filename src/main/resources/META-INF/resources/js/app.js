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

// An editable row carries both a view state (elements marked [data-dt-view]) and a hidden
// edit state ([data-dt-edit]). Entering/leaving edit mode is a pure client-side toggle —
// Save submits the row's form, Cancel just restores the view. Shared by every editable table.
window.dtStartEdit = function (row) {
    if (!row) return;
    row.classList.add('dt-row-highlight', 'dt-row-edit');
    row.querySelectorAll('[data-dt-view]').forEach(function (el) { el.classList.add('hidden'); });
    row.querySelectorAll('[data-dt-edit]').forEach(function (el) { el.classList.remove('hidden'); });
};
window.dtCancelEdit = function (row) {
    if (!row) return;
    row.classList.remove('dt-row-highlight', 'dt-row-edit');
    row.querySelectorAll('form').forEach(function (f) { f.reset(); });   // drop unsaved input
    row.querySelectorAll('[data-dt-edit]').forEach(function (el) { el.classList.add('hidden'); });
    row.querySelectorAll('[data-dt-view]').forEach(function (el) { el.classList.remove('hidden'); });
};

// Disarm every armed row except (optionally) one. "Armed" = mid edit or mid delete-confirm;
// either way the row shows a visible Cancel (.dt-btn-cancel), so clicking it restores the row.
// Exposed on window so page scripts can also call it (e.g. a delete rejected server-side).
window.dtClearArmedRows = function (exceptRow) {
    document.querySelectorAll('tr .dt-btn-cancel').forEach(function (cancel) {
        var row = cancel.closest('tr');
        if (!row || row === exceptRow) return;
        if (cancel.offsetParent === null) return;   // hidden → this row isn't armed
        cancel.click();
    });
};
// Selecting another entry (clicking its Edit or Delete) disarms whatever was armed before.
document.body.addEventListener('click', function (e) {
    var trigger = e.target.closest('.dt-btn-edit, .dt-btn-delete');
    if (trigger) window.dtClearArmedRows(trigger.closest('tr'));
});

// Day-panel: at most one confirm row open at a time. Runs in capture phase so it fires before
// HTMX on the Delete button; the Cancel click triggers its own hx-get to restore the normal state.
document.addEventListener('click', function (e) {
    if (e.target.closest('.day-item .dt-btn-delete')) {
        document.querySelectorAll('.day-item-confirm .dt-btn-cancel').forEach(function (cancel) {
            cancel.click();
        });
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
            return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c];
        });
    }
    function labelOf(field) {
        return field.getAttribute('data-field-label') || field.name || 'This field';
    }

    document.addEventListener('submit', function (e) {
        var form = e.target;
        if (!form || !form.matches || !form.matches('form[data-validate]')) {
            return;
        }

        var missing = [];
        var errors = [];
        var firstInvalid = null;
        form.querySelectorAll('[required]').forEach(function (field) {
            if (!field.value.trim()) {
                missing.push(labelOf(field));
                firstInvalid = firstInvalid || field;
            } else if (field.type === 'email' && field.value.indexOf('@') === -1) {
                if (errors.indexOf('Email must contain an @ symbol.') === -1) {
                    errors.push('Email must contain an @ symbol.');
                }
                firstInvalid = firstInvalid || field;
            }
        });

        var slot = form.querySelector('[data-form-errors]');
        if (missing.length === 0 && errors.length === 0) {
            if (slot) { slot.innerHTML = ''; slot.hidden = true; }
            return; // valid — let the form submit normally
        }

        e.preventDefault();
        if (!slot) {
            return;
        }

        var html = '';
        if (missing.length > 0) {
            var noun = missing.length === 1 ? 'field' : 'fields';
            html += '<div class="banner banner-error">Please fill in the following ' + noun + ':' +
                    '<ul class="list-disc list-inside mt-1">' +
                    missing.map(function (m) { return '<li>' + escapeHtml(m) + '</li>'; }).join('') +
                    '</ul></div>';
        }
        errors.forEach(function (msg) {
            html += '<div class="banner banner-error">' + escapeHtml(msg) + '</div>';
        });
        slot.innerHTML = html;
        slot.hidden = false;
        if (firstInvalid) { firstInvalid.focus(); }
    });
})();

// A form marked `data-ajax-submit` is posted with fetch() instead of a full-page navigation, so a
// rejected submission (e.g. a bad email/password on /login) can surface an inline error and let the
// user amend and retry WITHOUT the page reloading and wiping the fields they just typed. This runs
// after the `data-validate` handler above (registered later, both at document level, so it sees the
// validator's preventDefault), and only takes over once client-side validation has passed. Server-side
// form auth is unchanged and remains the no-JS fallback: without JS the form submits natively and the
// server still redirects to /login?error=true. OIDC login is a plain link, not this form, so it is
// untouched. (Brace note: Qute parses '{' in templates, so every '{' here is followed by whitespace.)
(function () {
    var lockoutTimer = null;

    // mm:ss, clamped so it can NEVER render a negative value.
    function formatClock(totalSeconds) {
        var s = totalSeconds > 0 ? totalSeconds : 0;
        var mins = Math.floor(s / 60);
        var secs = s % 60;
        return (mins < 10 ? '0' : '') + mins + ':' + (secs < 10 ? '0' : '') + secs;
    }

    document.addEventListener('submit', function (e) {
        var form = e.target;
        if (e.defaultPrevented) { return; }   // client-side validation already blocked this submit
        if (!form || !form.matches || !form.matches('form[data-ajax-submit]')) { return; }
        // While a lockout countdown is running the form is inert — swallow any submit (button click or
        // Enter) until it expires and the button is restored.
        if (lockoutTimer !== null) { e.preventDefault(); return; }
        e.preventDefault();

        var slot = form.querySelector('[data-form-errors]');
        var submitBtn = form.querySelector('button[type="submit"]');
        if (submitBtn) { submitBtn.disabled = true; }

        function stopLockout() {
            if (lockoutTimer !== null) { clearInterval(lockoutTimer); lockoutTimer = null; }
        }

        // Clears any running countdown, hides the banner and re-enables the form.
        function clearLockout() {
            stopLockout();
            if (slot) { slot.hidden = true; slot.innerHTML = ''; }
            if (submitBtn) { submitBtn.disabled = false; }
        }

        function showError(message) {
            stopLockout();
            if (submitBtn) { submitBtn.disabled = false; }
            if (slot) {
                slot.innerHTML = '<div class="banner banner-error">' + message + '</div>';
                slot.hidden = false;
            }
            var pw = form.querySelector('input[type="password"]');
            if (pw) { pw.focus(); }   // land on the password so a minor change is a keystroke away
        }

        // Locked out: keep the Sign in button greyed + inert and show the banner with a live mm:ss
        // countdown. It ticks off wall-clock time to the server-provided expiry (so a throttled/background
        // tab self-corrects on return rather than lagging), is guarded so it can never render a negative
        // value, and once it passes 00:00 the banner is hidden (no "you can try again" text) and the
        // button restored. Enforcement stays server-side — this is cosmetic, so an early retry just
        // re-shows the banner.
        function showLockoutCountdown(seconds) {
            var total = Math.floor(seconds);
            if (!(total > 0) || !slot) { clearLockout(); return; }   // guard: non-positive → nothing to show
            stopLockout();
            if (submitBtn) { submitBtn.disabled = true; }
            slot.innerHTML = '<div class="banner banner-error">Too many failed login attempts. '
                + 'Please try again in <span data-lockout-clock></span>.</div>';
            slot.hidden = false;
            var clock = slot.querySelector('[data-lockout-clock]');
            var endTime = Date.now() + total * 1000;
            function tick() {
                var remaining = Math.round((endTime - Date.now()) / 1000);
                if (remaining < 0) { clearLockout(); return; }   // past expiry → hide + restore the button
                if (clock) { clock.textContent = formatClock(remaining); }
            }
            tick();   // paint immediately, before the first interval
            lockoutTimer = setInterval(tick, 1000);
        }

        fetch(form.action, {
            method: 'POST',
            body: new URLSearchParams(new FormData(form)),
            headers: { 'Accept': 'text/html' },
            redirect: 'follow'
        }).then(function (resp) {
            // Form auth 302s to the landing page on success and back to /login?error=true on failure;
            // fetch follows the redirect, so the final resolved path tells the two apart.
            var dest = new URL(resp.url, window.location.origin);
            if (dest.pathname === '/login') {
                // A lockout carries the seconds left in X-Login-Retry-After; otherwise it's a bad login.
                var retryAfter = parseInt(resp.headers.get('X-Login-Retry-After'), 10);
                if (retryAfter > 0) {
                    showLockoutCountdown(retryAfter);
                } else {
                    showError('Invalid email or password.');
                }
            } else {
                window.location.assign(resp.url);   // session cookie already set — load the landing page
            }
        }).catch(function () {
            showError('Something went wrong. Please try again.');
        });
    });
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
            : num.toLocaleString(undefined);
    }
    // Replace each run of digits (optionally with a decimal part) in the element's text. A leading
    // sign/word stays put, so "+1234" → "+1,234" and "1234 this month" → "1,234 this month".
    window.Diurnal.formatNumbers = function (root) {
        root = root || document.body;
        var els = [];
        if (root.classList && root.classList.contains('js-num')) { els.push(root); }
        if (root.querySelectorAll) { Array.prototype.push.apply(els, root.querySelectorAll('.js-num')); }
        els.forEach(function (el) {
            if (el.dataset.numDone) { return; }   // idempotent: don't re-group an already-grouped value
            el.dataset.numDone = '1';
            var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null);
            var nodes = [];
            var node;
            while ((node = walker.nextNode())) { nodes.push(node); }
            nodes.forEach(function (textNode) {
                textNode.nodeValue = textNode.nodeValue.replace(/\d+(?:\.\d+)?/g, function (match) {
                    var dot = match.indexOf('.');
                    return fmt(Number(match), dot === -1 ? 0 : match.length - dot - 1);
                });
            });
        });
    };
    // Initial render (the body is fully parsed above this script), then again for any HTMX-swapped
    // content (e.g. the stats list paginating in).
    window.Diurnal.formatNumbers(document.body);
    document.body.addEventListener('htmx:afterSwap', function (e) { window.Diurnal.formatNumbers(e.target); });
})();

// ── Global tooltip long-press (touch) ─────────────────────────────────────────
// Desktop reveals `.app-tooltip` on hover (CSS). Touch has no hover, so a LONG press on any tooltip
// host — an element with a direct-child `.app-tooltip` (see partials/tooltip.html) — opens it by
// adding `.tip-open` (the same class the CSS reveal keys on), and swallows the click the press would
// otherwise fire (navigation, htmx, opening the colour picker…). A press elsewhere dismisses it.
// The Action-stats picker manages its OWN hosts (they also drag/toggle), so #stats-fields-list is
// skipped here. Mouse is left to hover.
(function () {
    var LONG_PRESS_MS = 500;
    var timer = null;
    var openHost = null;
    var suppressClick = false;

    // The nearest ancestor (or self) whose DIRECT child is an `.app-tooltip` — i.e. the host.
    function hostOf(el) {
        while (el && el.nodeType === 1) {
            if (el.querySelector(':scope > .app-tooltip')) return el;
            el = el.parentElement;
        }
        return null;
    }
    function closeTip() {
        if (openHost) { openHost.classList.remove('tip-open'); openHost = null; }
    }

    document.addEventListener('pointerdown', function (e) {
        if (e.pointerType === 'mouse') return;               // mouse uses hover
        suppressClick = false;
        if (openHost && !openHost.contains(e.target)) closeTip();   // tap outside dismisses
        if (e.target.closest('#stats-fields-list')) return;  // handled by the stats-picker script
        var host = hostOf(e.target);
        if (!host) return;
        timer = setTimeout(function () {
            timer = null;
            suppressClick = true;
            closeTip();
            host.classList.add('tip-open');
            openHost = host;
        }, LONG_PRESS_MS);
    }, true);

    function cancel() { if (timer) { clearTimeout(timer); timer = null; } }
    document.addEventListener('pointermove', cancel, true);
    document.addEventListener('pointerup', cancel, true);
    document.addEventListener('pointercancel', cancel, true);

    // Swallow the click a long-press would otherwise trigger. Capture + stopImmediatePropagation so
    // it never reaches the element's own (htmx / link / colour-input) handler.
    document.addEventListener('click', function (e) {
        if (!suppressClick) return;
        suppressClick = false;
        e.preventDefault();
        e.stopPropagation();
        e.stopImmediatePropagation();
    }, true);
})();

// ── Live password-requirements popover ────────────────────────────────────────
// Drives `partials/password-constraints.html`: while the associated NEW-password field is focused,
// reveal the popover and recolour each requirement green (met) / red (unmet) as the user types. The
// rows are server-rendered from net.zodac.diurnal.auth.PasswordConstraints, so this only evaluates —
// the check tokens below (minLength / maxLength) MUST match Constraint.type. One handler serves both
// the registration and settings pages; each page has a single opted-in field.
(function () {
    function met(type, bound, len) {
        if (type === 'minLength') return len >= bound;
        if (type === 'maxLength') return len <= bound;
        return true;                                     // unknown token: never block, just show it
    }
    function refresh(tip, len) {
        tip.querySelectorAll('[data-pw-check]').forEach(function (row) {
            var ok = met(row.getAttribute('data-pw-type'), parseInt(row.getAttribute('data-pw-value'), 10), len);
            row.classList.toggle('text-success', ok);
            row.classList.toggle('text-danger', !ok);
            var icon = row.querySelector('[data-pw-icon]');
            if (icon) icon.textContent = ok ? '✓' : '✗';
        });
    }
    document.querySelectorAll('[data-pw-tooltip]').forEach(function (tip) {
        var input = document.getElementById(tip.getAttribute('data-pw-for'));
        if (!input) return;
        var update = function () { refresh(tip, input.value.length); };
        input.addEventListener('focus', function () { update(); tip.classList.add('pw-open'); });
        input.addEventListener('input', update);
        input.addEventListener('blur', function () { tip.classList.remove('pw-open'); });
        update();                                        // colour correctly before first reveal
    });
})();

/*
 * Admin API-docs page behaviour (extracted from admin-api-docs.html so it rides the immutable,
 * content-hashed cache instead of being re-parsed on every no-cache navigation).
 *
 * Served as /js/admin-api-docs.<hash>.js in production (hashed + `immutable` in the Dockerfile,
 * baked into AppInfo.jsApiDocsFile) and /js/admin-api-docs.js in dev. Loaded only on the admin
 * API-docs page, as a classic script at the end of <body>, after the shared /js/app.js.
 */

// The Swagger UI at /api is its OWN (same-origin) document, so it doesn't inherit the app's fonts
// (it ships its own Titillium/Open Sans). Inject a stylesheet into it on load so its text matches
// the user's selected font — Nova when the page is in the Nova theme (`.font-nova` on <html>,
// rendered server-side from the Font setting), the system sans otherwise — while leaving the
// request/response code samples, schema models and editor monospace. Same-origin, so
// `contentDocument` is accessible; the @font-face URLs and the stylesheet target are same-origin too.
// The rule set also applies to elements Swagger renders asynchronously after load (CSS is live), so
// injecting once on `onload` is enough.
window.alignApiFont = function (frame) {
    let doc
    try { doc = frame.contentDocument } catch (e) { return } // guard cross-origin (shouldn't happen)
    if (!doc || !doc.head) {return}
    const nova = document.documentElement.classList.contains('font-nova')
    const bodyFont = nova
        ? '\'Nova Flat\', ui-sans-serif, system-ui, sans-serif'
        : 'ui-sans-serif, system-ui, -apple-system, \'Segoe UI\', Roboto, Helvetica, Arial, sans-serif'
    const css =
        '@font-face{ font-family:\'Nova Flat\';font-weight:400;font-style:normal;font-display:swap;' +
            'src:url(\'/fonts/NovaFlat-Book.woff2\') format(\'woff2\')}' +
        '@font-face{ font-family:\'Nova Flat\';font-weight:700;font-style:normal;font-display:swap;' +
            'src:url(\'/fonts/NovaFlat-Bold.woff2\') format(\'woff2\')}' +
        `.swagger-ui, .swagger-ui *{ font-family:${  bodyFont  } !important}` +
        // Keep actual code/JSON monospace: syntax-highlighted samples, the schema model, type
        // labels, the "Try it out" editor and any <code>/<pre>.
        '.swagger-ui .microlight, .swagger-ui code, .swagger-ui pre, .swagger-ui textarea,' +
        '.swagger-ui .model, .swagger-ui .prop-type, .swagger-ui .parameter__type' +
            '{ font-family:monospace !important}' +
        // Drop Swagger's own header bar (logo, "Swagger UI" title, Explore/spec field and the
        // dark-mode bulb). The navbar above already frames the page, and the theme now follows
        // the app rather than that bulb, so the whole topbar is redundant. Injected before the
        // iframe is revealed (it starts hidden), so the bar never flashes into view.
        '.swagger-ui .topbar, .swagger-ui .dark-mode-toggle{ display:none !important}' +
        // Swagger's style.css forces `overflow-y: scroll` on <html>, leaving a permanent (and now
        // unused) scrollbar track in the iframe. We size the iframe to its content and scroll the
        // page instead, so suppress the iframe's own scrollbar entirely.
        'html{ overflow:hidden !important}' +
        // Hide the spec-URL link (/q/openapi) shown under the API title.
        '.swagger-ui .info .link{ display:none !important}' +
        // Tighten the header: Swagger renders the Authorize control in a tall, full-width
        // `.scheme-container` bar below the description, leaving a large empty gap. Lift it out of
        // flow into the top-right, level with the title, and draw the operations separator directly
        // under the description. Positioned against the shared info/scheme wrapper (`:has`) so the
        // alignment is independent of whatever sits above it.
        '.swagger-ui > div:has(> .scheme-container){ position:relative}' +
        '.swagger-ui .scheme-container{ position:absolute; top:18px; right:20px; width:auto;' +
            ' margin:0; padding:0; background:transparent; box-shadow:none}' +
        '.swagger-ui .scheme-container .schemes{ padding:0; width:auto}' +
        '.swagger-ui .scheme-container .auth-wrapper{ margin:0}' +
        '.swagger-ui .info{ margin:30px 0 0 0 !important}' +
        '.swagger-ui .information-container{ padding-bottom:16px; margin-bottom:16px;' +
            ' border-bottom:1px solid rgba(128,128,128,.35)}' +
        // The Authorize dialog is `position:fixed` inside the iframe and centred vertically
        // (top:50% + translateY(-50%)). But the iframe has no scroll of its own — it's sized to
        // its full content height and the WINDOW scrolls (see syncApiHeight) — so the iframe's
        // "viewport" is the entire, possibly very tall, document. Centring then drops the popup
        // halfway down ALL the docs, far below the fold once operations are expanded. Pin it to
        // the top instead (keeping the horizontal centring) so it appears at a predictable,
        // reachable position near the Authorize control regardless of content height.
        '.swagger-ui .dialog-ux .modal-ux{ top:24px !important; transform:translate(-50%,0) !important}' +
        // On narrow viewports the absolutely-positioned Authorize control would sit on top of the
        // title/description (there is no room beside them). Drop it back into normal flow so it
        // renders below the description instead of overlapping it. Mirrors the app's `sm:` (640px)
        // breakpoint; the iframe is `w-full`, so this query tracks the real device width.
        '@media (max-width:640px){' +
            '.swagger-ui .scheme-container{ position:static; top:auto; right:auto; margin:0 0 16px 0}' +
            '.swagger-ui .info{ margin:20px 0 0 0 !important}' +
        '}'
    const style = doc.createElement('style')
    style.setAttribute('data-diurnal-font', '')
    style.textContent = css
    doc.head.appendChild(style)
}

// Swagger UI ships its own dark mode: the bulb-icon React control whose componentDidMount checks
// `prefers-color-scheme: dark` and — AFTER the first paint — adds `dark-mode` to its <html>
// (swagger-ui.css styles everything off that class). On a dark host that's a visible light->dark
// flash. /api is same-origin, so we instead drive that decision from the app's OWN resolved theme
// (the `.dark` class layout.html sets server-side from the user's setting) and apply it BEFORE the
// first paint: no flash, and the docs follow the in-app theme rather than the OS independently.
function steerApiTheme(frame) {
    let win, doc
    try { win = frame.contentWindow; doc = frame.contentDocument } catch (e) { return false }
    if (!win || !doc || !doc.documentElement) {return false}
    // Make Swagger's prefers-color-scheme probe report the APP theme (re-read live, so it tracks a
    // theme switch), so its componentDidMount and bulb toggle agree with what we paint — no fighting,
    // and the bulb's state stays correct. Patched once per iframe window (the flag rides the window,
    // so a navigation that swaps the window re-patches on the next poll).
    if (!win.__diurnalApiThemePatched && win.matchMedia) {
        const native = win.matchMedia.bind(win)
        win.matchMedia = function (query) {
            if (/prefers-color-scheme:\s*dark/i.test(query)) {
                return { matches: document.documentElement.classList.contains('dark'), media: query,
                         onchange: null, addListener: function () {}, removeListener: function () {},
                         addEventListener: function () {}, removeEventListener: function () {},
                         dispatchEvent: function () { return false } }
            }
            return native(query)
        }
        win.__diurnalApiThemePatched = true
    }
    // Pre-set the class so the very first paint (shell + content) is already themed.
    doc.documentElement.classList.toggle('dark-mode', document.documentElement.classList.contains('dark'))
    return true
}

// contentWindow attaches almost immediately; poll from element-creation so the matchMedia patch
// lands before Swagger mounts. Stops at onload (revealApiDocs), with a safety cap.
(function () {
    const frame = document.getElementById('api-frame')
    if (!frame) {return}
    let tries = 0
    frame.__diurnalSteer = window.setInterval(function () {
        if (!steerApiTheme(frame) && ++tries > 400) { window.clearInterval(frame.__diurnalSteer) }
    }, 5)
    frame.addEventListener('load', function () {
        window.alignApiFont(frame)
        window.revealApiDocs(frame)
    })
})()

// Size the iframe to its content height so the WHOLE PAGE scrolls (one window scrollbar that
// pushes the footer down), rather than the iframe scrolling internally.
function syncApiHeight(frame) {
    let doc
    try { doc = frame.contentDocument } catch (e) { return }
    if (!doc || !doc.documentElement) {return}
    // Collapse the iframe to zero BEFORE measuring. We force `html { overflow:hidden }` (below), so
    // documentElement.scrollHeight is floored at the iframe's own viewport height — i.e. whatever
    // we last set it to. Measured directly, it can therefore only ever GROW: expanding operations
    // enlarges it, but collapsing them again never shrinks it back, stranding a tall iframe with
    // empty space and a window scrollbar that won't retract. Zeroing the height first frees the
    // viewport so the measurement reflects the real content height (shrink included). The
    // reset → measure → set happens synchronously in one turn, so the browser never paints the
    // collapsed state — no flicker. Body height is content-driven (not tied to the iframe height),
    // so this doesn't feed back into the ResizeObserver in watchApiHeight.
    frame.style.height = '0'
    const content = Math.max(doc.documentElement.scrollHeight, doc.body ? doc.body.scrollHeight : 0)
    frame.style.height = `${content  }px`
}

// Keep that height in sync as Swagger's content grows/shrinks (expanding an operation, the schema
// models, or a width-driven reflow). Observe the iframe BODY only — its height is the content
// height, so it changes on real content changes; observing <html> would track the iframe height we
// set and loop. Width changes come via the parent window resize.
function watchApiHeight(frame) {
    let doc
    try { doc = frame.contentDocument } catch (e) { return }
    if (!doc) {return}
    syncApiHeight(frame)
    if (window.ResizeObserver && doc.body) {
        new ResizeObserver(function () { syncApiHeight(frame) }).observe(doc.body)
    }
    window.addEventListener('resize', function () { syncApiHeight(frame) })
}

// Settle the theme once Swagger has mounted, size it to its content, then reveal (it starts hidden
// to hide any race).
window.revealApiDocs = function (frame) {
    if (frame.__diurnalSteer) { window.clearInterval(frame.__diurnalSteer) }
    steerApiTheme(frame)
    watchApiHeight(frame)
    frame.style.visibility = 'visible'
}

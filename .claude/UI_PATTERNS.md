# UI Patterns & Conventions

Rules for templates (`src/main/resources/templates/`), the CSS source (`frontend/css/app.css`) and
front-end markup in general. Complements `CODE_STYLE.md` (Java) and the architecture notes in
`CLAUDE.md`. Read this before writing or editing any template or CSS.

## 1. Extraction rules

### When to extract a Qute partial

Extract markup into `templates/partials/` when **the same structure appears in 2+ templates** (or
2+ times in one template) and the differences are expressible as parameters. Do **not** extract
single-use markup speculatively — note it as a candidate and extract on the second use.

- Parameterise with explicit params documented in a leading `{! … !}` comment (see
  `partials/tooltip.html` for the house style: every param listed, required/optional stated,
  defaults documented).
- Qute is strict: default optional params with `.or(default)` (never elvis `?:` inside `{#let}`),
  and require callers to pass `""`/`false` explicitly for `{#if}`-tested attributes (see
  `partials/form-field.html`).
- Blocks that vary per call site use `{#insert}` slots (see `partials/combo-field.html`'s
  `{#selected}`/`{#options}` blocks).
- Existing partials are the catalogue — reuse before writing new markup: `banner`, `form-field`,
  `combo-field`/`combo-option` (THE dropdown — see the rule below), `language-option-label` (one
  language's name as BOTH the Settings language picker's closed button and its list write it — see
  [`I18N.md`](I18N.md)), `search-input` (optional `placeholder=`/`value=` params, both defaulted with
  `.or(...)`; pass `value=` for a page whose search term rides the URL, as `/notes` does),
  `tooltip`, `stat-tile`, `pagination`, `dt-row-actions`,
  `dt-confirm-delete-row`, `preview-option`/`preview-thumb`, `eye-icons`, `password-constraints`,
  `nav-links`, `navbar`, `footer`, `calendar-toolbar`, `stats-chart`/`stats-chart-candidates`,
  `colour-picker`/`random-colour-button` (the pair that make up a colour control - the new-action
  card, an action row's edit state and the Settings "Note colour" row each include both). That pair is
  the worked example of an optional `endpoint=` param: pass one and the picker auto-saves its own value
  on change (`hx-patch`, no swap, exactly like `combo-field`); leave it off and the colour is only
  submitted with the surrounding form. The randomise button finds its input by walking up to the nearest
  `form`, `td` or `[data-colour-scope]` - mark a non-form host (a Settings row) with the last of those.
  The full-size geometry of the pair is the `.colour-picker-input` / `.colour-picker-btn` component
  classes (the data-table row keeps its own compact `.dt-color-input` / `.dt-icon-btn` variants), so the
  picker and every button beside it cannot drift apart.
- **A single-use block stays inline.** The dashboard's note box is the worked example: it is rendered exactly once and
  is deliberately never a swap target (see [`FRONTEND.md`](FRONTEND.md)), so it lives in `dashboard.html` rather than
  becoming `partials/note-card.html`. Extract it if a second caller ever appears.
- A partial rendered BOTH inline and as a swap target is one partial, not two. `stats-chart-candidates`
  is embedded by `stats-chart` for its unfiltered first render and returned on its own by
  `/internal/stats/chart/{actionId}/candidates` as the search box filters, so the filtered and unfiltered
  lists cannot drift. Reach for that shape before writing a second copy of a list's markup in Java or JS.

### When to extract a component class

Move a utility cluster into `app.css` `@layer components` when **the same class string appears 3+
times**, or when it names a real design-system concept (button, card, field, badge) even at 2 uses.

- Name it semantically (`.btn-primary`, `.stat-tile`, `.field-label`) — never after its looks.
- Every colour inside must be a `var(--color-*)` token or a semantic Tailwind utility
  (`bg-surface`, `text-ink`, `border-line`, `text-brand`, …). **Never raw `gray-*`/`indigo-*`/
  `red-*` palettes** in templates or new component classes (the FOUC hex literals in
  `layout.html`'s inline script are the one documented exception).
- One-off layout tweaking (margins, flex direction, gaps) stays as inline utilities on the
  element; component classes carry the *identity* of the element, utilities carry its *placement*.
- Rebuild with `npm --prefix frontend run css` after any class change, or the class gets purged.
- **Every dropdown on the Settings page is `partials/combo-field.html`** — a button plus a
  `role="listbox"` panel (`.combo-*` in `app.css`, `wireCombo()` in `settings.js`), never a native
  `<select>`. It started with the language row, which needs a filter box inside its popup — a surface
  the browser owns — and the whole page followed rather than carry two kinds of dropdown that look and
  behave differently. `partials/select-field.html` was deleted in that change; do not reintroduce a
  native `<select>` row beside these. The list **scrolls past five options**, so a long list (the
  timezones) stays inside its card. Only the language row passes `search=true`; every other row differs
  in nothing. The real field stays a named `<input type="hidden">` carrying the same `hx-patch` the
  `<select>` had, so the save path is unchanged by the control's shape.
- **Dialogs use `.modal-overlay`** — the shared overlay chrome (fixed, centred, dimmed; toggle `.hidden`
  and nothing else, since the class owns `display` in both states). Both the Settings preview lightbox
  and the Stats frequency graph use it; it is the worked example of the "real design-system concept at
  2 uses" rule above. Their PANELS are deliberately not shared — one is a scrollable `.card`
  (`.modal-panel`), the other a bare frame around images — so share the chrome, not the contents.
- The self-contained widgets keep their own namespaced families of plain CSS at the BOTTOM of `app.css`
  (un-layered, so they beat Tailwind's layered utilities): `.dt-*` tables, `.d-*`/`.cal-*` calendar,
  `.chart-*` for the frequency graph, and `.note-*` for the dashboard note box. A namespace like that is for a widget, not a licence to skip the
  shared classes — the graph still uses `.card`, `.swatch`, `.empty-note` and `partials/tooltip`.

### Icons

**Every inline SVG glyph lives in `partials/icon.html`**, referenced by name:
`{#include partials/icon name='plus' cls='w-3.5 h-3.5' /}`. Never embed raw `<svg>`/path data at
a call site — add a new named case to the catalogue instead (each case owns its full `<svg>`
chrome: viewBox, stroke/fill, stroke-width; `cls` carries per-site sizing/animation, or is
omitted when a CSS rule sizes the glyph, e.g. `.icon-chip svg`). All catalogue icons are
decorative (`aria-hidden`) — the host control carries the accessible name. The one exception is
`partials/eye-icons.html`: its two glyphs are a stateful show/hide pair wired to
`data-eye-show`/`data-eye-hide`, so they stay in their own component partial.

## 2. `id` attribute conventions

- **kebab-case**, always unique in the document.
- Give an `id` to every **unique page landmark or section**: each page's `<main>`
  (`id="{page}-main"`, e.g. `dashboard-main`), each self-contained card/section on a page
  (e.g. the dashboard's calendar panel, day logger panel, and stats summary; each Settings card).
  A section that is an HTMX swap target always needs one.
- Do **not** add ids to repeated items (rows, tiles, options) unless they are swap targets — then
  use the `{prefix}-{entityId}` pattern (`action-{id}`, `user-row-{id}`, `log-{date}-{actionId}`).
- Name ids for **what the section does, not where it sits**: prefer `day-logger-panel` over
  `right-panel`.
- **Ids are API surface.** They are referenced from committed JS (`/js/*.js`), from Java
  (`HX-Retarget` headers, hx-target strings built in resources), and from Playwright specs.
  Renaming one is a coordinated change: grep `templates/`, `META-INF/resources/js/`,
  `src/main/java/`, and `tests/` before touching it, and update all in the same commit.

## 3. Page scaffolding

- `layout.html` owns `<html>/<head>/<body>`; pages provide only `{#body}`.
- Every page's outer wrapper is the page shell (`min-h-viewport flex flex-col`, extracted as a
  component class once approved — see §5), with `<main>` as the `flex-1` region and
  `partials/footer` last, so the footer sticks to the viewport bottom on short pages.
- Standard app pages: `partials/navbar` + `<main class="page-container px-4 py-8">` + an `<h2>`
  page title + optional muted subtitle.
- Auth-style pages (login, register, setup, error pages): a single centred `card shadow-xs w-full
  max-w-sm p-8` inside a centring `<main>` — reuse the shared shell rather than re-rolling it.
- Page-specific JS is a separate committed `/js/{page}.js` file wired via `data-*` attributes —
  never inline `<script>` logic or `on*=`/`hx-on=` attributes (CSP; see CLAUDE.md).

## 4. Colour & style tokens

- Semantic utilities only: `bg-surface(-muted)`, `text-ink(-muted)`, `border-line(-subtle)`,
  `text-brand`/`bg-brand`/`ring-brand-ring`, `text-success`, `text-danger`. New accents route
  through the brand tokens; new colour needs to get a new `--color-*` token pair (`:root` + `.dark`),
  not a raw palette class.
- Message banners use `.banner .banner-{error|success|warning}` via `partials/banner.html` — never
  hand-rolled alert divs (Java-built HTMX error HTML uses the same classes).
- Tooltips are always `partials/tooltip.html` on a `group relative` host with an `aria-label` —
  never `title=`. That partial is for text known at render time (an icon button's label).
- **A string that the layout may TRUNCATE gets the other kind: mark it `data-tip-full` and add
  nothing else.** One shared floating bubble (`.app-tooltip-float`, positioned by app.js) then shows
  the whole string on hover / long-press — but only while the element is actually clipped, measured
  live, so a string that fits reveals nothing. It is a separate mechanism because a truncating
  element is `overflow: hidden` by definition, which would clip a bubble nested inside it the way
  `partials/tooltip.html`'s is; being `position: fixed` also lets it show for truncated text inside a
  `.modal-overlay`, and lets the handlers be delegated (nothing to re-wire after an HTMX swap, a bare
  `innerHTML` write or a calendar re-render). The bubble is `aria-hidden` and the host needs no
  `aria-label`: the full string is already in the DOM, so assistive tech never saw the truncation.
  **Mark every new truncating element that carries dynamic text** (a user's own action name, note,
  display name, filename). Two exceptions, both deliberate: an element that already hosts a
  `partials/tooltip.html` bubble of its own (`partials/stats-field-row.html`'s caption, whose
  long-press shows the stat's DESCRIPTION — two tooltips on one host would fight), and one whose
  clipping JS has already decided for itself, which passes the text instead of being measured
  (`data-tip-full="<full text>"` — `dashboard.js`'s `fitFullEvents`, which in a tight calendar cell
  hides the event name outright, leaving nothing to measure).
- **Template comments are always Qute `{! … !}`** (stripped at render time) — never HTML
  `<!-- … -->`, which ships its bytes to the client in every response (pages are `no-cache`, so
  that's every navigation; partials rendered by the month back-fill would ship them ~30× per
  response). A bonus: Qute does not parse `{` inside `{! … !}`, so prose like `{date}` is safe
  there. As of 2026-07-13 no template contains an HTML comment; keep it that way.

## 5. Review outcomes (2026-07-13 UI review)

Everything from the 2026-07-13 review has been **applied**:

- the shared partials (`centered-card`, `error-page`, `card-wordmark`, `num-pref-row`, `card-header`, `search-input`, `stat-tile-compact`,
  `account-links`, `confirm-actions`, `colour-picker`, `tooltip-text`, the named `icon` catalogue)
- the component classes/tokens (`--color-canvas` body base rule, `.page-shell`, `.page-title`, `.page-subtitle`, `.link-brand`, `.empty-note`,
  `.icon-chip*`, `.inline-num-input`, `.day-item-btn`, tokenised `.field-label`/`.nav-link`/hamburger)
- the landmark ids (`stats-summary`, `{page}-main`, `settings-*` cards, `site-header`/`site-footer`, the `day-panel` → `day-logger-panel` rename)
- the shared Java `HtmxResponses.conflictBanner(...)`
- the JS consolidation (`Diurnal.bannerHtml`/`requiredFilled`/`postForm` the merged AJAX-form scaffolding, `flashStatus`, `swapField`, the
  `beforeSwap`/`HX-Retarget` 409 mechanism everywhere).

Two candidates were examined and **deliberately rejected** — do not re-propose them without new
evidence:

- **A shared long-press helper** for the tooltip mechanics in `app.js` (global handler) and
  `settings.js` (stats picker): the two share only a `setTimeout` pattern; the picker's timer is
  interwoven with its drag/toggle gestures (shared `suppressClick`, pointermove doubling as
  drag-move), so a common helper would couple two independent gesture systems and add complexity.
- **Reusing `dtStartEdit`/`dtCancelEdit` for the settings view↔edit rows**: those toggles also
  apply the `.dt-row-*` highlight classes, which are table-row styling the settings rows don't
  (and shouldn't) carry — their rings live in `.settings-field-edit`/`.settings-field-confirm`.
  The duplication was removed with a local `swapField(hideId, showId)` helper in `settings.js`
  instead.

## 6. Known keep-in-sync pairs (deliberate duplication — do NOT merge, but update together)

- `partials/banner.html` ↔ the Java banner HTML in `HtmxResponses.conflictBanner(...)` ↔ the JS
  `Diurnal.bannerHtml(...)` helper: three surfaces render the same `.banner banner-error` markup
  (Qute, Java, JS) and cannot share code across languages — each language now builds it in exactly
  one place; keep the three byte-identical. **A FOURTH copy is hand-written in `register.html`** (its
  missing-fields body is a `<ul>`, not a string, so it cannot go through the partial) — it carries the
  same `js-digits js-phrase` pair by hand, and is the one to check when that pair changes.
- `register.html`'s server-rendered missing-fields banner ↔ the `data-validate` banner built in
  `app.js`: the two look the same and fill the same slot, but their wording deliberately DIFFERS by one
  entry — the server knows the exact count and uses the plural-aware `missingFieldsIntro(count)`, while
  the client cannot replicate every language's CLDR grammar and uses the count-independent
  `missingRequiredFieldsPrefix` instead. Keep the markup and the field list identical; do not "fix" the
  intro to match (see both `@Message` methods' own Javadoc).
- `layout.html`'s `data-i18n-lockout-retry-countdown` ↔ `app.js`'s `LOCKOUT_CLOCK_TOKEN`: the message
  travels through a `data-*` attribute, which can only carry text, so the live `m:ss` clock's position
  in the sentence is marked by a literal `%CLOCK%` the JS swaps for the countdown element. Both spell
  that token out; they must agree, or the banner renders with no clock.
- `layout.html`'s inline FOUC `<script>` ↔ `auth.security.CspPolicy#FOUC_SCRIPT_HASH`: the strict CSP pins
  that block's SHA-256, so **editing the script means updating the constant in the same change** — an
  unmatched hash makes the browser refuse to run it and every page loads unthemed. `SecurityHeadersFilterIT`
  computes the real hash and fails with the correct value, so this is caught, but only at the `*IT` tier.
  (The same holds for the inline `<style>` and `FOUC_STYLE_HASH`.)
- `actions.js`'s hardcoded `#actions-empty-row` HTML ↔ the same row in `partials/actions-list.html`.
- `settings.js` `newStepValid()` ↔ `app.js`'s password-popover `met()`: the minLength/maxLength
  token checks are duplicated on purpose (no cross-file dependency, so a stale cached `app.js`
  can't break the settings gate); both mirror `text.TextConstraint.type`, which
  `TextFieldExtensions.constraints(...)` emits.

## 7. RTL & logical properties (see [`I18N.md`](I18N.md)'s "Right-to-left support")

`<html dir>` varies by language (`user/Language#dir()`, Arabic is the one offered RTL language today) — so **any
new physical-direction utility class or CSS property is a bug**, not just a style nit, and is picked up by grepping
for `\b(ml-|mr-|pl-|pr-|left-|right-|text-left|text-right)` in templates or `margin-left|padding-left|text-align:
left|...` in `frontend/css/app.css` before landing.

- **Use the logical form**: `ml-*`/`mr-*` → `ms-*`/`me-*`; `pl-*`/`pr-*` → `ps-*`/`pe-*`; `left-*`/`right-*`
  (positioning) → `start-*`/`end-*`; `text-left`/`text-right` → `text-start`/`text-end`. In hand-written `app.css`
  rules: `margin-left`/`margin-right` → `margin-inline-start`/`margin-inline-end` (same for `padding-`), `left`/
  `right` (positioning) → `inset-inline-start`/`inset-inline-end`, `border-left`/`border-right` →
  `border-inline-start`/`border-inline-end`, `text-align: left/right` → `text-align: start/end`. Tailwind v4 ships
  all of these natively (`ms-`/`me-`/`ps-`/`pe-`/`start-`/`end-`/`text-start`/`text-end`/`rounded-s-`/`rounded-e-`),
  no plugin or config needed.
- **A centring pattern (`left-1/2` + `-translate-x-1/2`) LOOKS direction-symmetric but is NOT** — converting the
  anchor to `start-1/2` is correct and necessary, but the transform must ALSO become a direction-aware pair
  (`ltr:-translate-x-1/2 rtl:translate-x-1/2`), for the same reason as the toggle-switch thumb below: `transform`
  has no logical axis. `start-1/2` = `left: 50%` under LTR (shift left by half the element's width to center it,
  `-translate-x-1/2`) but = `right: 50%` under RTL (shift RIGHT by half its width instead, `translate-x-1/2`) — a
  single unscoped `-translate-x-1/2` only centers the LTR case and drags the element further off the physical left
  under RTL. This was shipped wrong once (every `align="center"` tooltip in the shared `partials/tooltip.html` /
  `frequency-slot-tooltip.html` / `admin-user-row.html` / `stats-field-row.html`) and wasn't caught until a real
  mobile RTL pass showed the page itself overflowing horizontally — the visual symptom of a centered element
  drifting off one edge is easy to misread as an unrelated layout bug, so treat ANY `-translate-x-*` paired with a
  logical inset as suspect and check it in a real RTL browser, not by inspection.
- **A directional GLYPH (an SVG icon or a Unicode character encoding a real forward/back meaning — chevrons,
  arrows, «/‹/›/») does NOT mirror on its own** just because its container does. Two patterns, chosen by what the
  glyph already is:
  - An SVG via `partials/icon.html`: give the component class (or an inline `cls=`) an
    `rtl:scale-x-[-1]` — see `.chart-nav-glyph` in `app.css` (a dedicated class, fixed in the CSS) vs the
    settings.html preview-modal chevrons (no dedicated class, fixed inline at the call site). **Not every
    chevron is directional** — `settings.html`'s disclosure-toggle chevron rotates 90° on expand and encodes no
    forward/back meaning, so it stays unmirrored; check what a glyph MEANS before mirroring it.
  - A literal Unicode character (no SVG involved): wrap it and transform the wrapper. A horizontally-flipped «
    renders as a correct-looking » (and the reverse), so `rtl:scale-x-[-1]` mirrors these exactly like an SVG icon
    would, with no `{#if}`-based character-swapping needed.
  - **Exception, by explicit product decision, not oversight**: `partials/calendar-toolbar.html`'s `.cal-chevron`
    glyphs (the Dashboard's «/‹/›/» navigation) are deliberately left UNMIRRORED — the button each sits in still
    moves to the opposite visual edge under `dir="rtl"` like every other toolbar element, but the character itself
    stays static in every language (reversed from this class's original auto-mirrored behaviour). Don't treat this as
    the template to copy for a new directional glyph; the two bullets above are still the default.
- **A `transform`/`cursor` value has no logical form** — unlike `left`/`margin`/`border`, CSS offers no
  direction-relative keyword for `scaleX()`'s sign or a diagonal-resize cursor (`nwse-resize` vs `nesw-resize`).
  These need an explicit `[dir="rtl"] .foo { ... }` override (or Tailwind's `rtl:` variant) rather than a logical
  property — see `.note-resize-corner`'s cursor flip in `app.css`.
- **A toggle-switch thumb's `peer-checked:` translate is a physical `translate-x`, not a logical one** — Tailwind
  has no `translate-inline-end` utility, so a checked-state shift needs BOTH an explicit `ltr:`/`rtl:` pair
  (`peer-checked:ltr:after:translate-x-5 peer-checked:rtl:after:-translate-x-5`), never a single unscoped
  `peer-checked:after:translate-x-5` once the thumb's REST position (`after:start-0.5`) is logical — an unscoped
  transform would then push the thumb the same PHYSICAL direction regardless of which edge it actually rests
  against, moving it off the track under RTL. See the two toggle switches in `settings.html`.
- **CSS Grid `grid-column-start`/flexbox `justify-content ~ flex-start/flex-end`/plain DOM-order auto-flow are
  ALREADY logical** (relative to the grid/flex container's start line, not the physical viewport) — a component
  built from these with no explicit physical override (the dashboard's outer grid, the calendar's day grid, the
  calendar toolbar) mirrors automatically under `dir="rtl"` with zero class changes. Don't add an `rtl:` override
  to a component like this without first checking whether it already mirrors for free — verify in a real browser,
  not just by reading the CSS, since an unnecessary override is easy to get backwards.
- **JS that reads real pixel geometry (`getBoundingClientRect()`) is usually ALREADY direction-agnostic** — it
  reflects however the browser actually rendered the element, mirrored layout included. JS that HARDCODES a
  physical assumption (`el.left > other.left` meaning "beside, to the right", or a drag delta's sign assuming
  which edge a resize handle sits on) is not, and needs an explicit `document.dir === 'rtl'` (or
  `getComputedStyle(document.documentElement).direction`) branch. See `note.js`'s `noteMaxWidth()`/resize-drag
  math for a real example of each shape, and `dashboard.js`'s month/year popup positioning for a real example of
  the geometry-based kind needing no change at all.
- **`dir="rtl"` mirrors LAYOUT only — it has no bearing on which digit GLYPHS a number/digit renders as.** Arabic
  (`ar-SA`) uses Eastern Arabic-Indic digits (`١٢٣`), which is a separate, orthogonal axis from direction. A
  number the server or client renders as a bare `String`/`Number#toString()` always stays Latin-digit — it needs
  an explicit localization pass:
  - Client-side text already tagged `.js-num` (app.js) is grouped AND digit-localized together via
    `Number#toLocaleString(Diurnal.lang)` — for figures where both apply (stats counts/averages).
  - A calendar day number or year must localize digits WITHOUT ever grouping (a year must never render "٢،٠٢٦" —
    years aren't grouped in any language) — use `Diurnal.localizeDigits(text)` (a glyph-for-glyph regex
    transcode built from `Intl.NumberFormat`, no grouping applied), or tag the element `.js-digits` and let the
    matching `Diurnal.localizeDigitsIn(root)` walker (app.js, same shape as `.js-num`'s `formatNumbers`) do it
    declaratively on page load / after an HTMX swap. See `dashboard.js`'s `setCalTitle` and day-cell rendering,
    and `.js-digits` on the footer year/version and the Settings preset pills.
  - **A decorative label for a bound value (a preset pill) localizes its own TEXT via `.js-digits` while its
    `data-value` attribute (what JS reads to write the real input) is left untouched.** `.js-digits`/`.js-num`
    only ever rewrite text nodes, never attributes — an `href` or `data-*` value is never touched even inside a
    localized element (see the footer version link).
  - **An EDITABLE numeric field can also display this language's own digit glyphs, but only if it is
    `type="text" inputmode="numeric"`, not `type="number"`** — a number input's `.value` is spec-constrained to
    a plain ASCII "valid floating-point number" string and cannot hold e.g. Eastern Arabic-Indic digits at all.
    The Settings numeric steppers (`partials/num-pref-row.html`, `.num-pref-value`) use this shape: JS
    (`wireNumericPref` in settings.js) localizes the field's value/placeholder for display on every read/write,
    but the value that actually leaves the browser is delocalized back to plain Latin right before the request
    fires (a shared `htmx:configRequest` listener rewrites the specific field names, since htmx otherwise builds
    the PATCH straight from each field's live — localized — DOM value). Bounds validation (`min`/`max`) is
    JS-side only (`clamp()`, passed as plain numbers, not read off the markup) plus the server, which is
    unaffected either way since it only ever receives Latin digits. The day panel's per-action count field
    (`partials/day-action-item.html`) uses the GENERIC version of this same shape, `.js-num-input` (app.js) —
    reach for `wireNumericPref` only when a field also needs preset pills/a stepper/clamping; a bare editable
    count needs nothing beyond `.js-num-input`.
  - **`DateTimeFormatter#withLocale(locale)` does NOT switch numbering systems the way `NumberFormat` does** —
    a weekday/month NAME localizes, but a day-of-month/year NUMBER stays plain ASCII unless the formatter is
    ALSO given `.withDecimalStyle(DecimalStyle.of(locale))`. Every Java-side date formatter that renders a
    number (not just a name) needs this chained — use `Language#localizeNumerals(DateTimeFormatter)` rather
    than repeating the `withDecimalStyle` call at each site. A formatter that must stay locale-agnostic on
    purpose (a wire key/id round-tripping through a URL, e.g. `FrequencyKeys`' month/year key) must NOT get
    this — keep the KEY formatter and the DISPLAY LABEL formatter separate rather than sharing one, even when
    they'd otherwise produce identical ASCII output for `en-GB`.
  - **A "plain `fetch()` + `innerHTML`" swap bypasses htmx's own swap mechanism entirely, so `htmx:afterSwap`
    never fires — none of `.js-num`/`.js-digits`/`.js-num-input`/`Diurnal.fitFigures`'s declarative passes run
    on it.** This is a RECURRING shape in this codebase (the dashboard's day panel and stats-summary card,
    the stats page's frequency-chart modal) — each caller must call every relevant `Diurnal.*In(root)` pass by
    hand right after setting `innerHTML` (see `dashboard.js`'s `swapDayPanel`/`swapStatsSummary`,
    `stats.js`'s chart `load()`). A newly-added localization pass is easy to wire into the DECLARATIVE
    `htmx:afterSwap` path and then forget for these three manual call sites — grep `\.innerHTML\s*=` across
    `META-INF/resources/js/` when adding one, and check each hit.
  - **A localized digit run needs bidi ISOLATION, not just an inherited `dir`** — a number is always written
    left-to-right internally regardless of the surrounding language, but a plain inherited `dir` (`unicode-bidi:
    embed`, the browser default for the `dir` attribute) still lets the Unicode Bidi Algorithm consider
    neighbouring characters at the run's boundary; this showed up for real as a version string's leading "v"
    reordering to the trailing edge next to Arabic-Indic digits. `.js-num`/`.js-digits`/`.js-num-input` all
    carry `unicode-bidi: isolate` in `app.css` for this reason (matching what `<bdi>` gives HTML content
    natively) — a future digit-bearing marker class should too.
  - **A PHRASE that mixes translatable WORDS with an embedded number needs a DIFFERENT fix again — `.js-phrase`
    (`unicode-bidi: plaintext`), not `.js-digits`' `isolate`.** `isolate` only protects a number from its
    surroundings; it does nothing about the WORDS around it reordering against each other and against the
    number. Confirmed for real: "Page 1 of 2" rendered
    as "2 of 1 Page" under `dir="rtl"` with no direction pinned on the phrase — every word/number is its own
    bidi run, and an RTL paragraph places the first logical run on the right and works leftward, backwards for
    Latin-script text. `unicode-bidi: plaintext` fixes this WITHOUT hardcoding a direction (unlike the footer's
    `dir="ltr"` pin): it resolves the phrase's own base direction from its first STRONGLY-directional character
    (a digit is direction-neutral and gets skipped) — so "Page 1 of 2" resolves LTR (first strong char "P") and
    a genuinely Arabic phrase this same class also covers (a Java-formatted date embedded in an English "since
    {date}" caption) resolves RTL from its own Arabic letters, unaffected. **This needed no later revisit** —
    the heuristic keeps adapting once English message-bundle wording becomes real Arabic. Applied to
    `partials/stat-tile.html`/`stat-tile-compact.html` (value+sub, unconditionally), `partials/pagination.html`,
    `partials/tooltip.html`/`frequency-slot-tooltip.html`, the frequency chart's caption, and — the broadest
    single win — `partials/banner.html` and its JS mirror `Diurnal.bannerHtml()` (app.js), which together back
    most success/error messages app-wide. Reserve `.js-digits` alone for content that is ONLY EVER digits with
    no surrounding words (a version number, a bare count in an input); use `.js-phrase` (alongside `.js-digits`
    if it also needs digit-glyph transcoding) for anything a translator's sentence could wrap around.
  - **`.textContent`/`.innerText` extraction cannot catch a bidi reordering bug — it reads LOGICAL DOM order,
    not the VISUAL rendering the Unicode Bidi Algorithm actually produces.** Every `.js-phrase` bug above was
    invisible to the `.innerText` dumps used throughout this whole effort's verification (the DOM order was
    always correct — "Page 1 of 2", digits in the right place — only the on-screen RENDERING was reversed).
    Any RTL verification claim needs an actual screenshot (or a bounding-box/position check) of the specific
    text, not just a dump of its DOM content, or a real bug reads as "already verified working."
- **Some server-rendered text is deliberately app CHROME, not page content, and stays untranslated/unmirrored/
  Latin-digit on purpose** — the footer's build year · version · GitHub row (`partials/footer.html`) is the one
  example today: its row carries a pinned `dir="ltr"` (order never mirrors, treated like a copyright/version bug
  the way most software does) but its digits DO still localize (`.js-digits` on the year and the version text) —
  order-pinning and digit-glyph localization are separate, independently-decided axes, not a package deal. This
  was a deliberate per-element product call (asked of the user, not inferred), not a default to copy elsewhere
  without the same judgment call.
- **The app's STRUCTURAL layout (which panel/column sits where) is a SEPARATE decision from the footer's above,
  and a bigger one: it is now deliberately PINNED, not mirrored** — a considered reversal of the original
  "let CSS Grid/Flexbox auto-mirror" approach, made after the auto-mirroring shipped and was reviewed for real
  (asked of the user, not inferred). The navbar (`partials/navbar.html`), Settings' two-column layout
  (`settings.html`'s `#prefs-form`) and the dashboard's 2x2 panel grid (`dashboard.html`) are all app WORKSPACE
  chrome — positions a user builds muscle memory for — not a reading surface, so which panel/column/link sits
  where stays IDENTICAL across every language. Only the CONTENT inside each pinned region still fully localizes
  (RTL text alignment, mirrored controls within a card, the calendar's own internal day-grid, `.js-phrase`
  phrases, digit glyphs) — this is content mirroring, same as everywhere else in the app, just inside a frame
  that no longer moves.
  - **The mechanism**: `dir="ltr"` on the STRUCTURAL container (the flex/grid row that must stay physically
    ordered) freezes its own item placement, then EVERY grid/flex item inside it re-asserts the page's REAL
    direction via `dir="{language:dir(language)}"` (the same `Language#dir()`/`LanguageExtensions` bridge
    `layout.html` uses for `<html dir>`) — so a re-asserted item's own content behaves exactly as if nothing
    were pinned, while its OUTER position never moves. Never rely on inheritance alone once a `dir="ltr"` is
    pinned upstream — anything that should still localize needs its own explicit re-assertion, or it silently
    inherits the frozen `ltr` too.
  - **A pinned region can still have INTERNAL pieces that must stop mirroring as a consequence** — found for
    real with the dashboard's note-panel resize handle: `.note-resize-right`/`-corner`'s CSS used a LOGICAL
    property (`inset-inline-end`) from when the panel itself still moved sides under RTL; once the panel's
    OUTER position was pinned but its OWN `dir` was still re-asserted to the real direction, the handle would
    have kept flipping to the panel's OTHER edge under Arabic while the panel itself stayed put — a handle
    is positioning/chrome, the same category as the panel's own position, so it was reverted to a PHYSICAL
    property (`right`), and `note.js`'s matching `document.documentElement.dir === 'rtl'` branches (the
    `sideBySide` check, the resize-drag sign) were removed entirely, back to their original always-physical
    form. **Check every internal `inset-inline-*`/logical-property/`[dir="rtl"]` rule inside a region before
    pinning it** — anything reasoned about "moves with `dir`" during the original auto-mirroring design needs re-deriving once its
    container stops moving.
  - Verified with real-browser screenshots (not just DOM text — see the bidi lesson above) comparing `en-GB`
    and `ar-SA` side by side: navbar, Settings' cards and the dashboard's four panels are pixel-identical in
    position between the two, with only their internal content differing.
  - **A `dir="ltr"` pin, once anything inside it uses a COMPETING `ltr:`/`rtl:` Tailwind pair (one rule per
    direction — a centering transform, a toggle-switch thumb), breaks that pair in a way that's easy to miss
    because it looks like nothing should be wrong.** Tailwind compiles `ltr:`/`rtl:` to
    `:where(:dir(X),[dir=X],[dir=X] *)` — the `[dir=X] *` clause matches ANY ancestor with that literal
    attribute, not the nearest one (unlike native `:dir()`, which resolves like `direction` inheritance —
    correctly nearest-ancestor-based). Once a pinned region has `<html dir="rtl">` still somewhere further up
    (with no `dir="rtl"` reassertion inside the pinned region itself, like the navbar/footer), BOTH halves of a
    competing pair match at once, and CSS source order — not proximity — silently wins. Confirmed for real: a
    tooltip's centering pair, nested in the pinned navbar, always resolved to the RTL half regardless of the
    tooltip's own correctly-resolved `:dir(ltr)`, shifting it a full 100% of its own width to the wrong side.
    **The fix is `.app-tooltip-center`/`.toggle-thumb` (app.css) — handwritten rules against the native
    `:dir()` pseudo-class directly, which has no such fallback clause and is immune.** A SINGLE-direction
    override (no competing opposite-direction rule — `.chart-nav-glyph`, the settings preview-modal's
    `rtl:scale-x-[-1]`) is NOT affected: there is nothing for source order to arbitrate between. **Before adding
    a new competing `ltr:`/`rtl:` PAIR anywhere in a `dir="ltr"`-pinned region (or anywhere it could ever end up
    nested under one), use `:dir()` directly instead of Tailwind's prefix** — see app.css's own comment on
    `.app-tooltip-center` for the fuller mechanism and how to verify it (host/tooltip center-point geometry, not
    just DOM presence).
- The FOUC script's hex literals in `layout.html` ↔ the `--color-*` tokens in `app.css`
  (documented in the script comment: it runs before the stylesheet exists).

# UI Patterns & Conventions

Rules for templates (`src/main/resources/templates/`), the CSS source (`src/main/css/app.css`) and
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
- Blocks that vary per call site use `{#insert}` slots (see `partials/select-field.html`'s
  `{#options}` block).
- Existing partials are the catalogue — reuse before writing new markup: `banner`, `form-field`,
  `select-field`, `tooltip`, `stat-tile`, `pagination`, `dt-row-actions`, `dt-confirm-delete-row`,
  `preview-option`/`preview-thumb`, `eye-icons`, `password-constraints`, `nav-links`, `navbar`,
  `footer`, `calendar-toolbar`.

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
- Rebuild with `npm run css` after any class change, or the class gets purged.

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
  `src/main/java/`, and `e2e/` before touching it, and update all in the same commit.

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
  through the brand tokens; new colour needs get a new `--color-*` token pair (`:root` + `.dark`),
  not a raw palette class.
- Message banners use `.banner .banner-{error|success|warning}` via `partials/banner.html` — never
  hand-rolled alert divs (Java-built HTMX error HTML uses the same classes).
- Tooltips are always `partials/tooltip.html` on a `group relative` host with an `aria-label` —
  never `title=`.
- **Template comments are always Qute `{! … !}`** (stripped at render time) — never HTML
  `<!-- … -->`, which ships its bytes to the client in every response (pages are `no-cache`, so
  that's every navigation; partials rendered by the month back-fill would ship them ~30× per
  response). A bonus: Qute does not parse `{` inside `{! … !}`, so prose like `{date}` is safe
  there. As of 2026-07-13 no template contains an HTML comment; keep it that way.

## 5. Review outcomes (2026-07-13 UI review)

Everything from the 2026-07-13 review has been **applied** — the shared partials
(`centered-card`, `error-page`, `card-wordmark`, `num-pref-row`, `card-header`, `search-input`,
`stat-tile-compact`, `account-links`, `confirm-actions`, `colour-picker`, `tooltip-text`, the
named `icon` catalogue), the
component classes/tokens (`--color-canvas` body base rule, `.page-shell`, `.page-title`,
`.page-subtitle`, `.link-brand`, `.empty-note`, `.icon-chip*`, `.inline-num-input`,
`.day-item-btn`, tokenised `.field-label`/`.nav-link`/hamburger), the landmark ids
(`stats-summary`, `{page}-main`, `settings-*` cards, `site-header`/`site-footer`, the
`day-panel` → `day-logger-panel` rename), the shared Java `HtmxResponses.conflictBanner(...)`, and
the JS consolidation (`Diurnal.bannerHtml`/`requiredFilled`/`postForm`, the merged AJAX-form
scaffolding, `flashStatus`, `swapField`, the `beforeSwap`/`HX-Retarget` 409 mechanism everywhere).

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
  one place; keep the three byte-identical.
- `register.html`'s server-rendered missing-fields banner ↔ the `data-validate` banner built in
  `app.js`: both render "Please fill in the following field(s): <ul>…"; the two paths must look
  identical.
- `actions.js`'s hardcoded `#actions-empty-row` HTML ↔ the same row in `partials/actions-list.html`.
- `settings.js` `newStepValid()` ↔ `app.js`'s password-popover `met()`: the minLength/maxLength
  token checks are duplicated on purpose (no cross-file dependency, so a stale cached `app.js`
  can't break the settings gate); both mirror `PasswordConstraints.Constraint.type`.
- The FOUC script's hex literals in `layout.html` ↔ the `--color-*` tokens in `app.css`
  (documented in the script comment: it runs before the stylesheet exists).

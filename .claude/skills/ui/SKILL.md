---
name: ui
description: Building or changing UI - templates, partials, CSS and the JS that drives them - plus how to verify it without being misled. Use for: add a card or page, Qute, htmx, tooltip, table, Settings page, Tailwind, dark mode, RTL, responsive, screenshots, Playwright specs.
---

# Building UI

Reference detail is in [`UI_PATTERNS.md`](../../UI_PATTERNS.md) (extraction rules, `id` conventions, page
scaffolding, colour tokens, RTL), [`FRONTEND.md`](../../FRONTEND.md) (the CSS build, served scripts, `.dt-*`
tables, the calendar, Stats tiles, preview thumbnails) and [`I18N.md`](../../I18N.md) (messages, locale
formatting, fonts). **This skill is the order to do things in, and the traps that are in none of them.**

## 1. Do not hand-roll it

There are **62 partials and 200+ component classes**. Nearly every visual element you might build already exists.
Check here before writing markup:

| You want                         | Use                                                                             |
|----------------------------------|---------------------------------------------------------------------------------|
| A tooltip                        | `partials/tooltip.html` (`text`/`pos`/`align`) — never a hand-rolled bubble     |
| A tooltip for text that may clip | `data-tip-full` on the element — a shared floating bubble, `.app-tooltip-float` |
| A card                           | `.card` + `partials/card-header.html` (`title`, optional `statusId`)            |
| A page wrapper                   | `.page-shell` / `.page-container` / `.page-title` / `.page-subtitle`            |
| A data table                     | `.dt-*` — `.dt-table`, `.dt-row`, `.dt-cell`, `.dt-head-cell`, `.dt-empty`      |
| Row actions in a table           | `partials/dt-row-actions.html`, `partials/dt-confirm-delete-row.html`           |
| Pagination                       | `partials/pagination.html` + `.dt-pagination`                                   |
| A form field                     | `partials/form-field.html`, `.form-input`/`.form-select`/`.field-label`         |
| A dropdown/combobox              | `partials/combo-field.html` + `partials/combo-option.html`, `.combo-*`          |
| A colour picker                  | `partials/colour-picker.html` + `partials/random-colour-button.html`            |
| A success/error banner           | `partials/banner.html`, `.banner-success`/`.banner-error`/`.banner-warning`     |
| A button                         | `.btn-primary` / `.btn-secondary` / `.icon-btn-primary` / `.icon-chip`          |
| An icon                          | `partials/icon.html`                                                            |
| A modal                          | `.modal-overlay` / `.modal-panel` / `.modal-header`                             |
| A numeric preference row         | `partials/num-pref-row.html` + `.num-pref-*`                                    |
| A stat tile                      | `partials/stat-tile.html` and its dispatch/compact/row variants                 |
| A rejection message on a page    | `partials/text-failure-message.html` — never the Java `@NotUiFacing` wording    |
| A search box                     | `partials/search-input.html`                                                    |

**Extract a new partial or component class only at the thresholds `UI_PATTERNS.md` §1 sets** — not on first
duplication.

## 2. Rules you will otherwise trip

- **Colour comes from a token, never a literal.** Every colour is a `var(--color-*)`. A user-chosen colour goes
  through `colour/Colours` and renders exactly as picked in both themes; the one derived shade in the app is the
  calendar's note marker, and it is derived because it is a legibility floor.
- **Table values widen the table; they do not wrap.** Auto layout + `whitespace-nowrap` value cells +
  `overflow-x-auto` means a long value scrolls rather than wrapping, truncating or crushing its neighbours.
  `.dt-table-fixed` on the notes list is the one exception. `.dt-head-cell` is `nowrap` for the same reason.
- **`id` conventions** are in `UI_PATTERNS.md` §2 — read them before inventing one; the E2E specs and the JS both
  key off ids.
- **Never use Tailwind's `ltr:`/`rtl:` variants for a competing pair.** They compile to `[dir=X] *`, which matches
  on *any* ancestor, unlike native `:dir()` which resolves from the nearest. With a `dir` pinned on an inner
  container, both halves of a competing pair (`ltr:-translate-x-1/2` + `rtl:translate-x-1/2`) match at once and CSS
  source order silently picks the wrong one. Write `.foo:dir(ltr) { … } .foo:dir(rtl) { … }` by hand instead.
  Logical properties (`start-*`, `end-*`, `inset-inline-*`) are unaffected — they inherit correctly.
- **A phrase mixing words and numbers needs `.js-phrase`** so bidi does not scramble it under RTL. See
  `UI_PATTERNS.md` §7.
- **Pluralisation happens inside the `@Message` value**, via `{#if count == 1}…{#else}…{/if}` — never by composing
  words in Java. A duration is worded from a `DaySpan`, never from a bare day count.

## 3. Settings page specifics

The two-column layout, which column a card lands in, the mobile ordering, and the deliberately frozen LTR column
assignment are all documented **in a comment at the top of `settings.html`** (and summarised in `UI_PATTERNS.md`
§3). Read that comment before adding or moving a card — the placement rule is not arbitrary and the mobile order
is achieved with `order-last` plus `display:contents`, not by source order alone.

A new setting is more than a card row — see the `endpoint` skill for the full `@Preference` chain.

## 4. Rebuild the CSS

```bash
npm --prefix frontend run css        # or css:watch alongside quarkus:dev
```

**Any class added in a template *or in Java* is purged unless the CSS is rebuilt.** A `mvn` build regenerates it,
but it needs `frontend/node_modules` (`npm --prefix frontend install` once after cloning).

## 5. Qute traps

- **A bare `{word` is parsed as an expression, even inside a `<script>` block or an HTML/JS comment.** Put a space
  after the brace.
- **Qute runs strict here**: referencing a key absent from the data map throws at render time — even a bare
  `{#if x}` throws rather than being falsy. Default an optional include param **inside the partial** with
  `.or(…)`: `{#let pos=pos.or('top') align=align.or('center')}`. The elvis form does **not** work in `#let` —
  section params are split on whitespace before expression parsing, so ` ?: ` breaks and `pos?:'top'` mis-parses.
  Elvis works only in a plain output expression. This exact bug 500'd `/admin/api-docs` once.

## 6. JS and htmx traps

- **A plain `fetch()` + `innerHTML` swap does not fire `htmx:afterSwap`**, so none of the declarative passes
  (`Diurnal.formatNumbers`, `localizeDigitsIn`, `localizeNumInputsIn`, `fitFigures`) run on the new content.
  Three call sites bypass htmx deliberately — `dashboard.js`'s `swapDayPanel` and `swapStatsSummary`, and
  `stats.js`'s chart modal loader — and each must call every pass **by hand**. When adding a new
  `Diurnal.*In(root)` pass, `grep '\.innerHTML\s*=' src/main/resources/META-INF/resources/js/` and wire it into
  each hit. A fresh page load will look fine either way; the gap only appears after that specific swap.
- **`htmx.process(el)` re-wires `hx-*` attributes for future interactions; it does not fire `afterSwap`.**
- **In `htmx:configRequest`, never call `.map()`/`.filter()` on a value from `event.detail.parameters`.** A
  multi-valued key is a `Proxy` whose array methods execute but return `undefined` — assigning that back deletes
  every entry and appends the literal string `"undefined"`, silently collapsing a multi-row field with no client
  error. Go through the real `FormData` methods instead: `getAll(name)`, `delete(name)`, then `append` each
  transformed value. That handles scalar and repeated fields uniformly.
- **htmx `console.error`s every 4xx and it cannot be suppressed.** For a form whose failure is expected and handled
  inline, submit via `fetch()` (like the login/register cards in `app.js`).
- **Renaming a `data-*` attribute means renaming its camelCase `dataset` property too** — `data-chart-action` →
  `data-chart-subject` left `stats.js` reading `dataset.chartAction` and the frequency graph silently stopped
  opening. A text search for the attribute string finds nothing wrong; grep for both spellings.
- **The dark-mode checkbox is a pair**: a hidden `<input value="false">` plus the real `<input value="true">`.
  Checked posts `["false","true"]`, unchecked posts `["false"]`.

## 7. Verifying it — the part that misleads people

**A visual claim needs a screenshot**, and several obvious-looking checks silently prove nothing: `.textContent`
reads logical DOM order while bidi decides visual order separately, a class-presence check cannot see a geometry
bug, headless Chromium paints no scrollbars, and `toHaveText` with a RegExp skips whitespace normalisation. Those,
the calendar-spec date trap, and the two screenshot sets (`app` vs `documentation`) are in
[`references/verifying.md`](references/verifying.md) — **read it before claiming a UI change works.**

## 8. Then run the gate

`.github/scripts/lint_and_tests.sh java` covers templates, CSS and the UI specs. See the `gate` skill — and note
that a dev server must not be running while it does.

# Verifying UI, and the screenshot tooling

Read this when checking that a change actually looks right - these are the traps that make a wrong result look
like a right one. For building the thing in the first place, the `ui` skill itself is enough.

## 7. Verifying it — the part that misleads people

- **A visual claim needs a screenshot.** `.textContent`/`.innerText` reads *logical* DOM order; the Unicode Bidi
  Algorithm decides *visual* order separately, and the two diverge arbitrarily once LTR words sit in an RTL
  paragraph. A whole RTL effort was verified by text dumps and missed "Page 1 of 2" rendering as "2 of 1 Page".
  When reading an RTL screenshot, the correct check is **"does the first logical element appear rightmost"** — not
  "does it read left-to-right".
- **A geometry bug is invisible to a class-presence check.** Verify a centering/transform fix with real numbers
  (host-vs-target centre point), not by asserting the element has the right classes.
- **Headless Chromium paints no scrollbars.** A screenshot will never show one, even a deliberately red one.
  Assert the computed `scrollbar-color`/`scrollbar-width` instead.
- **Playwright: `toHaveText` with a RegExp matches raw `textContent`** and skips the whitespace normalisation the
  plain-string form applies. Use a plain string for full-text equality.
- **Calendar specs must not use a bare `pastDateStr(n)`.** The grid draws only
  `(weekday-of-the-1st - weekStart + 7) % 7` leading days, so how far back a `.d-min-cell[data-date=…]` exists
  depends on today's date — on 2026-09-01 exactly one leading cell existed and 16 specs failed with "element(s) not
  found" before any request reached the app. Use `showMonthOf(page, iso)` for a genuinely past day, or
  `otherDaysThisMonth(count)` to stay in today's month.
- **A single E2E timeout may be sandbox CPU contention** rather than a regression — see the `gate` skill for how to
  isolate it.

## 8. Screenshots

`scripts/generate-screenshots.cjs <app|documentation|all>`:

- **`app`** — the 8 in-app Settings preview thumbnails. **Gitignored and generated inside the Docker build**; any
  `docker build` produces fresh ones. You rarely run this by hand.
- **`documentation`** — the 9 committed README shots in `docs/screenshots/`. Allowed to lag; regenerate and commit
  manually when a README-visible page changes: `scripts/dev-up.sh`, then
  `node scripts/generate-screenshots.cjs documentation`, then `scripts/dev-teardown.sh`. Output is WebP.

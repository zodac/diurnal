# Front-end build

**Rebuild the CSS after ANY class change — in a template *or* in Java — or Tailwind purges it:**

```bash
npm --prefix frontend run css        # or css:watch alongside quarkus:dev
```

`/css/app.css` is a build artifact and is gitignored. Any `mvn` build regenerates it via the POM's `css-build`
exec, but that needs `frontend/node_modules`, so `npm --prefix frontend install` is a one-time step after cloning.

## Before editing anything here

- **The brand colour is GENERATED — never hand-edit it.** The `--color-brand*` family lives in `@generated:brand`
  regions of `app.css`, computed by `scripts/generate-brand.py` from the `fill` of `assets/wordmark.svg`. To
  rebrand: change the `fill`, then `npm --prefix frontend run brand`.
- **Colour comes from a token, never a literal** — every colour is a `var(--color-*)`. Route a new accent through
  `bg-brand`/`text-brand`/`border-brand`, never a literal `indigo-*`.
- **Never use Tailwind's `ltr:`/`rtl:` variants for a competing pair.** They compile to `[dir=X] *`, matching on
  *any* ancestor, so both halves of a pair can match at once under nested `dir` attributes. Write `:dir(ltr)` /
  `:dir(rtl)` by hand.

The `ui` skill is the order to do things in; [`FRONTEND.md`](../.claude/FRONTEND.md) and
[`UI_PATTERNS.md`](../.claude/UI_PATTERNS.md) hold the detail. Run `.github/scripts/lint_and_tests.sh java`
afterwards — it covers templates, CSS and the UI specs.

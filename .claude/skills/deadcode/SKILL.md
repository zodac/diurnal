---
name: deadcode
description: Find production code that only the tests keep alive - a class, method or constant in src/main that nothing in src/main, no template and no script ever reaches. Use for: dead code, unused method, is this still used, test-only code, can I delete this, unreferenced, tidy up.
---

# Finding production code that only the tests use

**The gate cannot see this.** Qodana's unused-declaration inspection is the only check in the build that reports an
unused *public* declaration, and it counts a call from `src/test` as a use. So a method whose sole remaining caller
is the test that pins it reads as "used" forever, and the build stays green while the code is unreachable in
production. This is the one dead-code shape the whole gate is blind to, which is why it needs a deliberate sweep
rather than a linter.

The worked example this came from: `FrequencyPeriod.label()` returned the English strings `"Month"`/`"Year"`, had
no production caller at all (`partials/stats-chart.html` hardcodes `{msg:month}`/`{msg:year}`), and was held alive
by one unit test asserting those two strings. It was also live bait for the "third bucket" trap in
[`I18N.md`](../../I18N.md) — an English label on an enum is the thing that renders in every language the moment
somebody loops the constants to build the toggle.

## Step 1 — run the scan

Write this to `/tmp/deadscan.py` and run it from the repo root. It is deliberately textual (no compiler, no
bytecode): the consumers that matter here are Qute templates and browser scripts, which reach Java by NAME and
which no call-graph tool in this build follows.

```python
import re, os, glob

def strip(src):                                    # comments are not usage - but see Step 2
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    return re.sub(r'//[^\n]*', '', src)

MAIN = glob.glob('src/main/java/**/*.java', recursive=True)
TEST = glob.glob('src/test/java/**/*.java', recursive=True)
RES  = [p for p in glob.glob('src/main/resources/**/*', recursive=True)
        if os.path.isfile(p) and p.endswith(('.html', '.js', '.properties', '.yaml', '.yml', '.json'))
        and 'htmx.min' not in p]
E2E  = [p for p in glob.glob('tests/**/*.ts', recursive=True) if 'node_modules' not in p]

main_raw  = {p: open(p, encoding='utf-8').read() for p in MAIN}
main_src  = {p: strip(v) for p, v in main_raw.items()}
test_blob = '\n'.join(strip(open(p, encoding='utf-8').read()) for p in TEST)
res_blob  = '\n'.join(open(p, encoding='utf-8', errors='replace').read() for p in RES)
e2e_blob  = '\n'.join(open(p, encoding='utf-8', errors='replace').read() for p in E2E)

# Anything a framework calls reflectively is not dead just because no Java names it.
SKIP = ('@Override', '@Inject', '@Observes', '@GET', '@POST', '@PUT', '@DELETE', '@PATCH', '@Produces',
        '@Consumes', '@Path', '@Scheduled', '@PostConstruct', '@PreDestroy', '@PreUpdate',
        '@TemplateExtension', '@ServerExceptionMapper', '@Provider', '@Message', '@ConfigMapping')
KEYWORDS = {'if', 'for', 'while', 'switch', 'catch', 'return', 'new', 'record',
            'equals', 'hashCode', 'toString', 'this', 'super'}

cands = []
for p, src in main_src.items():
    lines = src.split('\n')
    for i, l in enumerate(lines):
        kind = name = None
        m = re.match(r'    (?:public|protected)(?:\s+static)?(?:\s+final)?\s+[\w.<>\[\], ?]+\s+(\w+)\s*\(', l)
        if m:
            kind, name = 'method', m.group(1)
        else:
            m = re.match(r'    (?:public|protected)\s+static\s+final\s+[\w.<>\[\], ?]+\s+(\w+)\s*=', l)
            if m:
                kind, name = 'constant', m.group(1)
        if not name or name in KEYWORDS:
            continue
        # 25 lines, not 8: an OpenAPI @Operation/@APIResponse block easily runs that long above its method.
        if any(a in '\n'.join(lines[max(0, i - 25):i]) for a in SKIP):
            continue
        cands.append((p, i + 1, kind, name))

rows = []
for p, line, kind, name in cands:
    names = {name}
    bean = re.match(r'(?:get|is)([A-Z]\w*)$', name)     # getCssUrl() is reached as {appInfo.cssUrl}
    if bean:
        names.add(bean.group(1)[0].lower() + bean.group(1)[1:])
    w = re.compile(r'\b(?:' + '|'.join(re.escape(n) for n in names) + r')\b')

    other_main = sum(len(w.findall(s)) for q, s in main_src.items() if q != p)
    own        = len(w.findall(main_src[p])) - 1
    res_hits   = len(w.findall(res_blob))
    t          = len(w.findall(test_blob)) + len(w.findall(e2e_blob))
    if other_main == 0 and own <= 0 and res_hits == 0 and t > 0:
        doc = sum(len(w.findall(v)) for q, v in main_raw.items() if q != p)
        rows.append((p.replace('src/main/java/net/zodac/diurnal/', ''), line, kind, name, t,
                     'doc-link only' if doc else 'no main reference'))

print('=== declared in src/main, referenced ONLY from tests (%d) ===' % len(rows))
for r in sorted(rows):
    print('  %-42s:%-5d %-9s %-30s test-refs=%-3d %s' % r)
```

## Step 2 — triage every hit by hand; most are false positives

**A raw run reported 45 candidates and only 3 survived triage.** Do not act on the list directly. The categories
that produce a false positive here, in the order they cost the most time:

| False positive                                              | Why it looks dead                                                                                                                                          | How to confirm                                                                                                                       |
|-------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| **Qute JavaBean property access** - 42 of the first 45 hits | `{inject:appInfo.cssUrl}` calls `getCssUrl()`. Qute strips the `get`/`is` and lowercases, so the template never contains the method's own name             | `grep -rn "inject:" src/main/resources/templates`. The scan folds the prefix off already; a hand-rolled one that does not is useless |
| **Anything read by name from a template**                   | `@TemplateExtension` methods, `AppMessages` `{msg:...}` entries, and any field a partial reads off a model object                                          | Search the templates for the bare name AND the property form                                                                         |
| **Reflective framework entry points**                       | JAX-RS endpoints, CDI observers, Jackson record constructors, `@ConfigMapping` accessors, Panache/JPA callbacks. Nothing in Java calls them                | The `SKIP` annotation list covers these; the look-back must clear a long OpenAPI block                                               |
| **Javadoc `{@link}` references**                            | A `{@link Foo#BAR}` is a real compile-time reference under `-Dlint`, so deleting the target breaks the build - but a link is *documentation*, not usage    | The scan reports these as `doc-link only`. Treat as "used by docs, not by code" and decide accordingly                               |
| **A bulk sweep in one test**                                | `AppPathsTest.internalUrls(...)` enumerates the whole `AppPaths` catalogue to assert the `BASE_PATH` prefix. Being in that list is not evidence either way | Read the test. A per-member assertion is a pin; a catalogue sweep is incidental                                                      |

## Step 3 — sanity-check every survivor against the WHOLE repo

**Before acting on any candidate, grep the entire repository for its bare name.** The scan in Step 1 only reads
`src/main/java`, `src/main/resources`, `src/test/java` and `tests/**.ts` — so a reference living anywhere else is
invisible to it, and the scan will confidently call the member dead.

```bash
grep -rn --binary-files=without-match "<TheName>" . \
  --exclude-dir=node_modules --exclude-dir=target --exclude-dir=.git --exclude-dir=.qodana \
  --exclude-dir=playwright-report --exclude-dir=test-results --exclude-dir=test-results-smoke \
  --exclude-dir=code-quality-config
```

This is cheap and it works. Run against the three survivors above it immediately turned up a reference the
structured scan had no way to see: `frontend/css/app.css` explains the `.stat-tile dt` wrap rule by naming
`StatField.MAX_LABEL_LENGTH` — a Java constant cited from a CSS comment, in a directory outside every glob in
Step 1. It also showed that comment still called the class `ActionStatField`, a name renamed out of the codebase
some time ago, so the sweep found a stale cross-language reference as well as a missed one.

Read each hit and classify it:

- **A call** — the member is not dead. Stop.
- **A comment, a doc, or a `{@link}`** — not usage, but it IS documentation someone wrote deliberately. Deleting
  the member orphans the prose; decide knowingly, and fix the prose in the same change.
- **A frozen file** — a `V*__*.sql` migration comment may name the old thing forever and **must not be edited**
  (see the Hard rules in [`CLAUDE.md`](../../CLAUDE.md)). Leave it and move on.

## Step 4 — decide, and do not just delete

A true hit is one of three things, and only the first is unambiguous:

1. **Dead, and a latent trap** — delete it, and the test with it. `FrequencyPeriod.label()` was this: unused, and
   it was English UI text sitting on an enum, which [`I18N.md`](../../I18N.md) records as having shipped twice.
   When you remove one, say in the type's Javadoc *why* it is absent, or the next person re-adds it.
2. **Dead, but part of a deliberately complete catalogue** — leave it, or ask. `AppPaths.getInternalDataImport()`
   has no production caller because `settings.js` builds that URL with the sanctioned
   `Diurnal.url('/internal/data/import')` form. But `AppPaths` is documented as the single builder of *every*
   application URL, so a gap in the catalogue is arguably the defect, not the unused method.
3. **An alias nothing needed** — `StatField.MAX_LABEL_LENGTH` is `= TextFields.STAT_NAME_MAX_LENGTH` and carries a
   long Javadoc about the rendering bound. No code calls it, but Step 3 showed `frontend/css/app.css` cites it by
   name to justify the `.stat-tile dt` wrap rule, so deleting it orphans that explanation too. Removing it costs
   the documentation on both sides; keeping it costs an unused public constant. Surface it rather than settling it
   silently.

**A refactor can create these.** `MAX_LABEL_LENGTH` had exactly one non-test reference at `HEAD` — a `{@link}` in
`ProfileService.updateStatsFields`'s Javadoc — and that Javadoc was deleted when those methods were made private
(private members carry no Javadoc, see [`CODE_STYLE.md`](../../CODE_STYLE.md)). Re-run this scan after any sweep
that removes documentation or narrows visibility; that is exactly when new orphans appear.

## Step 5 — verify the removal

Deleting a member that a template reads by name fails at RUNTIME, not at compile time — Qute resolves
`{inject:…}` and `{x.foo}` when the page renders. `mvn package` proves nothing about it.

- `mvn -o clean install -Dall` — the ITs render the real pages, which is what catches a template that lost its
  accessor.
- If the member was reachable from a page, load that page too (the `run` skill, or the relevant `*IT`).
- Removing a public member can also shift Qodana: fewer callers can turn a `WeakerAccess` or `MethodMayBeStatic`
  finding on, so run `.github/scripts/lint_and_tests.sh java:qodana` before calling it done.

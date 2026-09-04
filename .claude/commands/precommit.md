---
description: Pre-flight the working tree before a commit — scope the gate to what changed, check the docs kept up, and propose a commit message.
---

# Pre-commit pre-flight

Get the working tree ready for a commit the user is about to make.

**Do not commit, and do not offer to.** This command prepares and reports; the user commits when they choose to.
It exists because `.hooks/pre-commit` runs the whole auto-detected gate on `master`, so a commit takes as long as
the gate does — finding a lint nit here is minutes cheaper than finding it there.

## 1. See what changed

```bash
git status --short
git diff --stat
git diff --stat --cached
```

## 2. Run the gate, scoped to the change

Let auto-detection choose, since that is what `pre-commit` will do:

```bash
.github/scripts/lint_and_tests.sh
```

If that selects `java` (any `src/`, `frontend/`, template, UI-spec or Dockerfile change), it is a ~10-minute run —
delegate it to the `gate-runner` agent so its output stays out of this conversation, and report the verdict.
Anything smaller, run inline. Triage any failure with the `gate` skill before blaming the change.

## 3. Check the documentation kept up

A change to one of these usually means a doc changed too. Report anything that looks out of step — do not silently
fix it:

| Changed                              | Should usually also change                                            |
|--------------------------------------|-----------------------------------------------------------------------|
| A new endpoint or `@Preference`      | `OpenApiSurfaceIT.PUBLIC_API_CONTRACT`, the Settings page, the bundle |
| A new migration                      | `DATABASE.md`'s migration-decision index                              |
| A `.claude/hooks/` guard             | `.claude/hooks/tests/run-hook-tests.sh`                               |
| A measured optimisation              | The `perf` skill's verdict table and its reference file               |
| A new partial or component class     | `UI_PATTERNS.md`, and the `ui` skill's "do not hand-roll it" table    |
| A convention you applied to the code | `CODE_STYLE.md` — the rule belongs in the same change                 |

Also confirm nothing in the diff touches `RELEASE_NOTES.md` or `VERSION` (hand-authored, and guarded).

## 4. Propose a message

`.hooks/commit-msg` requires `[Category] Text` on **every non-empty line**, so propose a **single line** unless
there is a real reason not to. Use `[AI]` for anything under `.claude/`, otherwise reuse an existing category:

```bash
git log --format='%s' -100 | grep -oE '^\[[^]]+\]' | sort | uniq -c | sort -rn
```

## 5. Report

State: what changed, the gate verdict, any doc that looks out of step, and the proposed message. Then stop.

# Text Input Validation

> Every user-submitted free-text value - action name, display name, stat name, password, email, note - is validated by
> ONE shared pipeline in `net.zodac.diurnal.text`, with per-field overrides for the parts that legitimately differ
> (length, normalisation, extra rules). Read this before adding a text input, changing a length bound, or adding a
> character/content rule.

## Why

The rules were previously copied per field, and had already drifted:

- Only the stat name rejected control characters and collapsed whitespace runs; an action name or display name of
  `"a<NUL>b"` or `"a     b"` was stored verbatim and rendered into the calendar and the actions table.
- The display-name bound (2-100) lived in `UserSettings` but was re-implemented a second time in
  `RegistrationService.validate`, and `OidcUserProvisioner.provision` applied neither - an IdP `name` claim was
  written straight to the column.
- `settings.html` capped the display-name field at `maxlength="255"` while the server rejected anything over 100;
  the action-name cap was the literal `100` in two templates.
- `users.display_name` was an unbounded `varchar(255)` while its validator said 100; nothing checked a bound
  against its column.
- Length was measured with `String.length()` (UTF-16 units) everywhere, so a name of 51 emoji failed a 100-char cap.

One pipeline makes each of those a single-line change instead of a five-file sweep, and makes a NEW cross-cutting
rule (the planned "invalid characters" policy) apply everywhere by construction.

## Design

Package `net.zodac.diurnal.text` - pure, no CDI, no entities, no I/O, 100% PITest.

### `TextField` - the spec

A record (data only; derived logic lives in `TextFieldExtensions`, per the record/Extensions rule in `CLAUDE.md`)
holding everything that varies per input:

| Component                 | Purpose                                                                                                                                                                                                 |
|---------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `label`                   | The human name used to word every message ("Display name must be between 2 and 100 characters.")                                                                                                        |
| `minLength` / `maxLength` | Bounds, measured in **code points**. A `minLength` of `0` marks the field OPTIONAL: a blank submission is accepted and normalises to the empty string (the stat name's "use the catalogue label" reset) |
| `normalisation`           | `CLEANED`, `VERBATIM` or `MULTILINE`                                                                                                                                                                    |
| `rules`                   | Extra `TextRule`s layered on top of the shared pipeline                                                                                                                                                 |

Built through `TextField.of(label, min, max)` (cleaned), `TextField.secret(label, min, max)` (verbatim),
`TextField.multiline(label, min, max)` (multi-line), and `withRules(...)`. No message ever quotes the submitted value,
so a secret needs no separate flag.

### `Normalisation`

- **`CLEANED`** - control characters become spaces, whitespace runs collapse to one, the result is stripped and
  NFC-normalised. This is today's `StatField.sanitiseLabel` behaviour, promoted to the default for every
  non-secret field. Both passes are **Unicode-aware, not ASCII-only**: `\s` and `\p{Cntrl}` in Java match ASCII only,
  so a no-break space, an em space, an ideographic space or a C1 control would otherwise survive the collapse AND the
  strip - leaving a name that renders as nothing but passes every length check. The patterns are
  `\p{gc=Cc}` and `[\s\p{Zs}\p{Zl}\p{Zp}\x{0085}]+` for that reason.
- **`VERBATIM`** - the value is used exactly as submitted. For secrets only: stripping or normalising a password
  changes what the user typed, and would silently invalidate existing credentials. A `secret` field also carries
  **no rules at all, not even the shared ones**: a password is never rendered, never compared against another user's
  value and is stored only as a hash, so the reasons the shared rules exist do not apply to it - and constraining
  which characters it may hold would shrink the keyspace and lock out anyone whose existing password holds one.
- **`MULTILINE`** - `CLEANED`, except that the **line feed survives**. Added for the day note (`TextFields.NOTE`), the
  only input in the app that is a block of prose rather than a label: `CLEANED` collapses every whitespace run,
  newlines included, so it would flatten a journal entry into a single paragraph. The pass is, in order: line
  terminators folded to `\n` (a browser textarea submits CRLF per the HTML specification, so this is the everyday
  case, not an edge one) -> every OTHER control character to a space -> runs of **horizontal** whitespace to one
  space -> each line stripped -> a run of 3+ newlines condensed to 2 (one blank line: a paragraph break is ordinary,
  a thousand of them is padding that would otherwise pass the length check as "content") -> the whole value stripped
  -> NFC. **The order is load-bearing**: lines are stripped *before* the blank-line run is condensed, so a line of
  spaces counts as blank; and the whole-value strip happens *last*, which is what removes leading/trailing newlines.
  Nothing else is relaxed - the length is still measured in code points (**a newline counts toward the bound**), and
  the field still carries the shared content rules.

> **The trap, if a second multi-line field is ever added.** A line feed is a `Cc` control character, so
> `NO_INVISIBLE_CHARACTERS` rejects it. No `CLEANED` field ever meets one (normalisation turns it into a space long
> before the rules run), which is why the collision never surfaced until the note existed. A `MULTILINE` field
> therefore carries **`NO_INVISIBLE_CHARACTERS_ALLOWING_NEWLINE`** in its place - the same predicate with the line feed
> exempted, built by parameterising the original rather than copying it, so the two can never drift. The exemption is
> applied in the rule's own loop and deliberately **not** inside `isInvisible`, so that the joiner check underneath
> keeps asking the strict question: a zero-width joiner at the start of a line joins nothing, exactly like one beside a
> space, and stays rejected.

### `TextRule` - the per-field override seam

```java
public record TextRule(String id, Predicate<String> accepts, String requirement) { }
```

`TextRules` holds the shared ones. A rule added to a single `TextFields` entry applies to that input; a rule added
to the defaults inside `TextField.of(...)` applies to every cleaned field at once. `withRules(...)` **adds to** the
rules a field already carries, so a field-specific rule can never displace a shared one.

Two rules are shared by every cleaned field (and by every multi-line one, with the invisible-character rule in its
newline-tolerant form - see `MULTILINE` above; and deliberately by NO secret - see below):

- **`NO_INVISIBLE_CHARACTERS`** rejects three families:
  - any code point of category `Cf`, `Cs`, `Co` or `Cc` - the zero-width characters (ZWSP, ZWNJ, BOM, soft hyphen,
    word joiner, invisible times), the bidirectional overrides/isolates/marks, the interlinear annotations, the tag
    characters, unpaired surrogates and the private-use area;
  - the explicit `BLANK_CHARACTERS` - code points that are **letters or marks** by category, and so slip past the
    check above, but render as nothing: the hangul fillers (U+115F, U+1160, U+3164, U+FFA0), the Khmer inherent
    vowels (U+17B4, U+17B5) and the blank braille pattern (U+2800). These are the characters behind the "blank
    name" trick on every chat platform, and a name made only of them passed every check before they were named;
  - the Unicode **noncharacters** (U+FDD0-U+FDEF and the last two code points of every plane), which are
    permanently reserved and can never become assigned - so rejecting them costs no future emoji. The reason is that **two different values must never render
  identically**: `ad<ZWSP>min` stores as a different name from `admin` but is indistinguishable on screen, so it
  defeats the duplicate-name check; a bidirectional override goes further and reverses the text after it. Unpaired
  surrogates are in the set for a second reason - PgJDBC silently rewrites one to a single byte, so the value stored
  would not be the value validated.
  - **The two zero-width JOINERS (U+200D and U+200C) are the deliberate exception**, and are accepted ONLY between
    two other characters (neither a space nor itself invisible) - a leading, trailing, doubled or space-adjacent
    joiner is still invisible padding and still rejected. U+200D binds the parts of a multi-person emoji; U+200C
    is **mandatory orthography in Persian, Urdu and Pashto** (the everyday word `پیاده‌روی` is misspelled without
    it), which the first version of this rule rejected outright. The residual cost is knowingly accepted: a joiner
    between two Latin letters is invisible, so `ad<ZWNJ>min` renders like `admin` - already reachable through
    homoglyphs, and not worth refusing to store someone's own language over.
  - **Unassigned code points are deliberately NOT rejected**: the JDK's Unicode tables lag new emoji releases, so
    rejecting them would reject emoji that a current browser renders perfectly.
- **`NO_STACKED_MARKS`** rejects a run of more than `MAX_CONSECUTIVE_MARKS` (4) consecutive non-spacing/enclosing
  marks - the "zalgo" pattern, which renders as a column of glyphs that overflows the row it is shown in. Four
  clears every script that genuinely stacks marks (Hebrew with points and cantillation, Thai, Devanagari,
  Vietnamese). Note that normalisation composes the FIRST mark onto the letter before it, so a submission needs
  `MAX_CONSECUTIVE_MARKS + 2` marks to be rejected.

A rule is applied only once the value is non-empty and within its length bounds, so a rule never re-checks either.

### `TextOutcome` - the result

```java
public sealed interface TextOutcome permits Valid, Failure {
    record Valid(String value) implements TextOutcome { }   // the NORMALISED value - callers store THIS, never the raw input

    sealed interface Failure extends TextOutcome { TextField field(); }
    record Blank(TextField field) implements Failure { }
    record TooShort(TextField field) implements Failure { }
    record TooLong(TextField field) implements Failure { }
    record RuleFailed(TextField field, TextRule rule) implements Failure { }
}
```

The intermediate `Failure` lets a caller that needs no per-cause distinction collapse every rejection into one
branch. `TextOutcomeExtensions.message(failure)` generates the wording from the field, replacing the handwritten
`DISPLAY_NAME_RANGE_MESSAGE` / `NEW_PASSWORD_TOO_LONG_ERROR` / stat-name constants.

### `TextValidation` - the single entry point

`TextValidation.check(TextField, @Nullable String)` runs a fixed pipeline, identical for every field:

```
null -> normalise (per policy) -> empty -> length (code points) -> extra rules
```

A whitespace-only submission to a `CLEANED` field is already empty by the time the emptiness check runs, so one
check covers both. A `VERBATIM` field keeps its whitespace, so `"   "` is a real password.

There is one companion entry point: **`TextValidation.coerce(field, raw)`**, which normalises, truncates to the
maximum and returns `Optional.empty()` if what remains is still unusable. It exists for the ONE caller with no user
to report a rejection to - provisioning a display name from an OIDC `name` claim (`auth/OidcDisplayName`). Every
other caller must `check` and reject; a value a user typed is never silently stored as something else.

### `TextFields` - the catalogue

One constant per input in the app. The catalogue is the only place a bound is written:

```java
public static final TextField ACTION_NAME  = TextField.of("Action name", 1, ACTION_NAME_MAX_LENGTH);
public static final TextField DISPLAY_NAME = TextField.of("Display name", DISPLAY_NAME_MIN_LENGTH, DISPLAY_NAME_MAX_LENGTH);
public static final TextField STAT_NAME    = TextField.of("Stat name", 0, STAT_NAME_MAX_LENGTH);
public static final TextField EMAIL        = TextField.of("Email", EMAIL_MIN_LENGTH, EMAIL_MAX_LENGTH).withRules(TextRules.EMAIL_SHAPE);
public static final TextField PASSWORD     = TextField.secret("Password", PASSWORD_MIN_LENGTH, PASSWORD_MAX_LENGTH);
public static final TextField NOTE         = TextField.multiline("Note", 1, NOTE_MAX_LENGTH);
```

Each bound is ALSO a `public static final int` constant, because a Bean Validation annotation
(`@Size(max = ...)` on `RegisterRequest`) needs a compile-time constant. The field is built from the constant, so
the two cannot disagree.

> **`NOTE` is the one entry whose bound is not fixed at compile time.** A deployment may set its own through
> `NOTE_MAX_LENGTH` (`config/NotesConfig`), and the field the application validates against is built from that by
> `note/NoteField` — so `TextFields.NOTE` is the DEFAULT instance rather than the one in force, and
> `TextFields.note(int)` is the factory both go through. Only the note can do this, and only because it has no column:
> every other bound is pinned to a `VARCHAR(n)` by `TextFieldsSchemaIT`, whereas a note is stored sealed in an unbounded
> `bytea` (`notes.content` was dropped in `V28`), so its bound lives purely in `TextValidation`. `AppLifecycle` refuses
> to boot outside `[NOTE_MAX_LENGTH_FLOOR, NOTE_MAX_LENGTH_CEILING]`. The full reasoning — why the ceiling is where it
> is, and what happens to notes already stored above a lowered bound — is in [`NOTES.md`](NOTES.md).

## What is accepted, cleaned and rejected

The full policy, and the reasoning behind each row. `NaughtyStringsTest` pins every one of these to the pipeline, in
the spirit of the "big list of naughty strings"; `TextRulesTest` covers the rules themselves.

| Input                                                                                                                                                   | Answer                        | Why                                                                                                                                                                                                                                                                             |
|---------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| ASCII and C1 control characters, `NUL`, `DEL`                                                                                                           | cleaned to a space            | Has a readable equivalent. `NUL` is also the one value PostgreSQL cannot store at all (`invalid byte sequence for encoding "UTF8": 0x00`), so the clean is load-bearing                                                                                                         |
| No-break/em/ideographic space, line & paragraph separators                                                                                              | cleaned to a space            | Whitespace a user cannot tell from a space. A value made only of these is then rejected as **blank**                                                                                                                                                                            |
| Zero-width space, BOM, soft hyphen, word joiner, tag characters, interlinear annotations                                                                | **rejected**                  | Renders as nothing, so two different values would look identical                                                                                                                                                                                                                |
| Zero-width joiner / non-joiner **between two characters**                                                                                               | **accepted**                  | Real orthography: emoji sequences, and mandatory in Persian/Urdu/Pashto                                                                                                                                                                                                         |
| Zero-width joiner / non-joiner anywhere else (leading, trailing, doubled, beside a space)                                                               | **rejected**                  | Joining nothing, so it is only invisible padding                                                                                                                                                                                                                                |
| Hangul fillers (U+3164, U+115F, U+1160, U+FFA0), Khmer inherent vowels, blank braille (U+2800)                                                          | **rejected**                  | The "blank name" trick: LETTERS by category, invisible on screen                                                                                                                                                                                                                |
| Unicode noncharacters (U+FDD0-U+FDEF, U+xFFFE, U+xFFFF)                                                                                                 | **rejected**                  | Permanently reserved, never assignable, renders as a fallback box                                                                                                                                                                                                               |
| Bidirectional overrides, isolates and marks                                                                                                             | **rejected**                  | Reorders the text after it, so a name can be made to display as something it is not                                                                                                                                                                                             |
| Unpaired surrogates, private-use characters                                                                                                             | **rejected**                  | Not renderable, and a surrogate is silently rewritten by the driver on its way to the column                                                                                                                                                                                    |
| More than 4 stacked combining marks ("zalgo")                                                                                                           | **rejected**                  | Renders as a column of glyphs that overflows the row                                                                                                                                                                                                                            |
| Emoji, in every form (ZWJ sequences, skin tones, flags, keycaps, variation selectors)                                                                   | **accepted, unchanged**       | Ordinary text for a habit tracker. Measured in code points, so a family emoji costs 7 characters of the bound, not 1                                                                                                                                                            |
| Astral-plane text, every script, RTL text without an override                                                                                           | **accepted**                  | Ordinary text                                                                                                                                                                                                                                                                   |
| Homoglyphs, full-width forms, circled digits, upside-down text, fraktur/double-struck/small-caps letters, enclosed alphanumerics, runes, Unicode digits | **accepted**                  | Legitimate text in some script. A name here is not a security boundary (it is scoped to one account and never authenticates), so confusables are not policed                                                                                                                    |
| U+FDFD `﷽`, which renders about ten characters wide                                                                                                     | **accepted**                  | One code point, one character. The actions table is deliberately never truncated or wrapped (it scrolls horizontally), so a wide glyph widens the row rather than breaking the layout                                                                                           |
| ANSI escape sequences, CR/LF (log forging)                                                                                                              | cleaned to a space            | The display name reaches the application log; a control character cannot survive the cleaning pass, so a name can neither forge a log line nor colour a terminal                                                                                                                |
| A newline, **in a `MULTILINE` field only**                                                                                                              | **accepted, preserved**       | It is what the user typed. A journal entry's paragraphs are content, not padding; `CRLF`/`CR` fold to `\n`, a run of blank lines condenses to one, and the newline counts toward the length bound. Every other field still folds it to a space                                  |
| `<script>`, `'; DROP TABLE`, `{7*7}`, `${7*7}`, `%s`, `../../`                                                                                          | **accepted, stored verbatim** | A name is data. It is made safe where it is RENDERED - Qute escapes by default (no `raw` filters exist in the templates), the calendar writes `textContent`, and Panache/JPQL is parameterised. Escaping on the way IN would show the user something other than what they typed |
| Anything at all, in a password                                                                                                                          | **accepted, unchanged**       | See the `VERBATIM` note above                                                                                                                                                                                                                                                   |

### Case folding and normalisation can LENGTHEN a value

Two operations grow a value, and both run in places where a bound has already been checked:

- **`toLowerCase` on the email.** A Turkish dotted capital I (U+0130) folds to TWO code points, so a 254-character
  address becomes ~500 - past the bound it was just checked against, and past `VARCHAR(255)`. Folding must
  therefore happen **before** `TextValidation.check`, which is what `RegistrationService` and
  `OidcUserProvisioner` do; folding afterwards turned a 400 into a 500 at INSERT time.
- **NFC normalisation.** It usually shortens, but U+0958 expands to two code points and U+FB2C to three. The
  pipeline measures AFTER normalising, so this is already correct - `NaughtyStringsTest` pins it.

The same reasoning applies to any future transformation of a validated value: transform first, then validate, so
the value that was checked is the value that is stored.

### What the DATABASE supports (measured, not assumed)

- `server_encoding` is `UTF8`, and `varchar(n)` / `length()` count **characters (code points)** - the same unit
  `TextFieldExtensions.length` uses, so a bound and its column agree exactly (`TextFieldsSchemaIT` pins them).
- A `NUL` byte is rejected outright by the server; every other code point in the table above stores and reads back
  byte-for-byte, emoji included (a family emoji is 7 characters / 25 bytes).
- Unique indexes are **byte-exact**: they do not fold case or normalisation form. Case folding for the email is done
  in the services (`toLowerCase(Locale.ROOT)` on every read and write path); NFC folding is done by the pipeline.

## Room for internationalisation

The app is English-only today, and a future translation (including RTL locales) is **not** constrained by this
pipeline in any structural way - the UI's own strings are template/bundle text, which never goes through it. What
the pipeline does constrain is USER DATA, and `NaughtyStringsTest.check_realTextInEveryScript_isAccepted` pins
ordinary words in Arabic, Persian, Urdu, Hebrew (including Biblical pointing), Thai, Devanagari, Tamil, Korean,
Japanese, Chinese, Vietnamese, Greek and Russian as accepted, so a rule added later cannot quietly break one.

Two rules are the only places a locale could rub, and both are a one-line change:

- **Bidi marks and isolates are rejected** (U+200E/U+200F/U+061C, U+2066-U+2069) along with the overrides. RTL
  user data does not need them - the correct fix for a name whose direction is ambiguous is `dir="auto"` on the
  element that renders it, which an RTL-capable UI needs anyway. If a real case ever appears, the safe subset to
  allow is BALANCED isolates (they cannot leak direction past their own scope, unlike the overrides, which must
  stay rejected).
- **`MAX_CONSECUTIVE_MARKS` is 8**, measured against the worst real script rather than guessed: Biblical Hebrew
  reaches six marks on one letter (dagesh + two vowel points + meteg + two cantillation marks). Modern Arabic,
  Hebrew, Thai and Devanagari never exceed four. Raise the constant if a script needs more.

### Deferred: `dir="auto"` on user-content elements

**Decided 2026-08-05: not done yet, to be picked up as the first step of the l10n work.** It is written down here
because it is the other half of the bidi decision above - that rule rejects the characters a user would otherwise
paste in to fix a name's direction, on the grounds that the RENDERER should be deciding direction instead. Until
this exists, nothing is.

What it is: `dir="auto"` tells the browser to derive direction from the value's own first strong character, so an
Arabic or Hebrew name renders correctly inside the English LTR page. It is about user DATA, not the interface
locale, so it is correct even if the UI stays English forever - and it is a **no-op for every value in the database
today**, since it changes nothing when the first strong character is LTR.

Where it goes: the leaf element holding ONLY the user's value - the name cell in `partials/action-row.html`, the
day-panel rows (`partials/day-action-item.html`, `partials/day-action-item-confirm-delete.html`),
`partials/admin-user-row.html`, the navbar display name, and the stat captions in `stats.html` /
`partials/stats-summary.html`.

The one thing to get right: **granularity**. It must NOT go on a row or card wrapper that also holds buttons and
labels - an RTL value would then flip the surrounding chrome, which is the spoofing-adjacent behaviour the bidi
rule exists to prevent. On the leaf element it reorders the value and nothing else.

What it does NOT do: give an RTL *interface*. That needs `dir` on `<html>` plus CSS logical properties, and
`frontend/css/app.css` currently uses **zero** of `margin-inline`/`padding-inline`/`inset-inline`/`text-align:
start` - it is entirely physical-direction. That conversion is page-chrome work and belongs with the rest of the
l10n effort. The split is clean: `dir="auto"` handles user data, logical properties handle chrome, and neither
creates rework for the other.

### The rest of the outstanding i18n work

All of it lives outside this package: `<html lang="en">` is hard-coded (as is the absent `dir`),
`time/Durations.plural` appends an English "s" (Arabic has six plural forms), the display-name bound of 50 was
sized against the Latin navbar (CJK glyphs are about twice as wide), and a translated rejection message would need
whole message templates rather than the current `label + ' ' + requirement` concatenation, whose word order is
English-specific.

## Flow

```
form / JSON body
      |
      v
Resource  -- surface policy ONLY (coerce vs 400, confirm-password, first-user refusal)
      |
      v
*Service  -- TextValidation.check(TextFields.X, raw)
      |        `- switch on TextOutcome -> its own sealed *Result variant
      v
entity.field = outcome.value()      <- ALWAYS the normalised value
```

### Validate once, then treat the value as settled

**A submission goes through the pipeline exactly ONCE per request, and everything downstream uses
`TextOutcome.Valid.value()`** - never the raw submission, and never a second `check`/`normalise` pass. The outcome
carries both the verdict and the normalised value precisely so no caller has to re-derive either.

Concretely, that means:

- A service that reports several failures at once (`RegistrationService`) checks each field once, keeps the
  outcomes, and derives BOTH the missing-field list and the error list from them.
- A service with a surface-specific pre-check (`PasswordChangeService`'s confirm-mismatch) does that check on the
  raw pair, then makes one pipeline pass and hashes `Valid.value()`.
- Normalisation happens in the service, not the resource. `StatField.labelsByKey` pairs the picker's parallel
  form lists and returns the names **exactly as submitted**; `ProfileService.updateStatsFields` makes the single
  pass and hands the normalised names to `StatField.encode`, which stores what it is given.
- The read path does not re-normalise. A stored value was normalised when it was written, so
  `StatField.customLabelFor` only applies the "a name equal to the catalogue label is not a rename" RULE.
- `TextValidation.coerce` normalises once and then calls the same internal check as `check`, rather than
  re-entering the public entry point (which would normalise again).

The reason is not performance - normalisation is idempotent and cheap. It is that a value normalised in two places
is a value whose two normalisations can drift, which is the exact failure this package was built to end.

The existing per-domain sealed results (`ActionResult.NameTooLong`, `ProfileResult.Invalid`,
`RegistrationResult.Invalid`) are **unchanged** - only the rules move. The resource still holds no rule, the
service still owns the single business logic, and the `switch` still gives compile-time exhaustiveness.

Surface policy is untouched by this: `TextValidation` reports what is wrong, and the caller decides whether to
4xx (the API) or coerce (a web form) - see the reject-not-coerce rule in `CLAUDE.md`.

## Templates

`web/TextFieldCatalogue` is a `@Named("textFields")` bean exposing the catalogue to Qute, so a `maxlength`
attribute derives from the same constant as the server check:
`maxlength="{inject:textFields.actionName.maxLength}"`. Where a page already threads data in (the register and
settings pages), the value is passed as page data sourced from the same constants.

The client-side evaluators of those rows (`app.js`, `settings.js`) count **code points** (`Array.from(value).length`),
matching `TextFieldExtensions.length`; `value.length` would count an emoji twice and contradict the answer the server
is about to give. The `maxlength` ATTRIBUTE cannot be made code-point-aware (the browser counts UTF-16 units), so it
stops an all-emoji value at half the bound - it is only ever stricter than the server, never laxer, and the server
stays authoritative.

> **The note field deliberately carries NO `maxlength`.** It is the one input where the UTF-16-vs-code-point mismatch
> above actually bites: against a 10,000-character bound the attribute would stop an emoji-heavy note at five thousand,
> silently and with no explanation. The note box counts code points itself (`Array.from(value).length`), shows the
> figure, lets the user overrun, and refuses to SAVE while over — which is both accurate and explicable, where a
> silent cut-off is neither. This is also what makes a LOWERED `NOTE_MAX_LENGTH` safe for notes already written above
> it: the box loads such a note in full and reports how far over it is, where a `maxlength` would have cut it on sight.

`TextFieldExtensions.constraints(field)` replaces the deleted `PasswordConstraints.all()` and works for any field,
driving both the requirements tooltip (`partials/password-constraints.html`) and its live client-side red/green
check in `layout.html`. Only the length bounds are published: the client evaluator understands
`minLength`/`maxLength` only, so publishing a rule row would render a requirement that could never turn green.

## Adding a new text input

1. Add a `TextField` constant to `TextFields`.
2. Call `TextValidation.check` from the owning `*Service` and map the outcome onto that domain's sealed result.
3. Store `Valid.value()`, never the raw submission.
4. Point the template's `maxlength` at the catalogue.
5. If the field has a column, give it `@Column(length = …)` matching the catalogue max (a guard test enforces this).

---

## Implementation status

All steps below are **done**; the full `lint_and_tests.sh java` gate (unit + `*IT` + linters + PITest, then E2E and
deployment-smoke) is green.

1. This document, plus the `CLAUDE.md` deep-reference pointer.
2. The `text` package: `Normalisation`, `TextRule`/`TextRules`, `TextField` + `TextFieldExtensions`, `TextOutcome` +
   `TextOutcomeExtensions`, `TextConstraint`, `TextValidation`, `TextFields`.
3. Unit tests to the 100% PIT bar: `TextValidationTest` (parameterised over the whole catalogue - null, blank,
   whitespace-only, `min-1`/`min`/`max`/`max+1` code points, control character, astral-plane character),
   `TextFieldTest`, `TextFieldExtensionsTest`, `TextOutcomeExtensionsTest`, plus the explicit
   "`PASSWORD` is never normalised" case - the one place centralising could have broken existing logins.
4. Callers migrated:
   - `ActionService` create/update -> `TextFields.ACTION_NAME`; `ActionValidation` now holds only the colour rule.
   - `ProfileService.updateDisplayName` -> `TextFields.DISPLAY_NAME`; `UserSettings.isInvalidDisplayName` and
     `DISPLAY_NAME_RANGE_MESSAGE` deleted.
   - `StatField.sanitiseLabel`/`isValidLabel` delegate to `TextFields.STAT_NAME`; `MAX_LABEL_LENGTH` is now an
     alias of the catalogue bound (its Javadoc carries the tile-rendering reasoning, which is stats-specific).
   - `RegistrationService` dropped its duplicated display-name, email and password checks.
   - `PasswordChangeService` -> `TextFields.PASSWORD`; `PasswordConstraints` deleted outright.
   - `OidcUserProvisioner` -> the new pure `auth/OidcDisplayName`, which coerces the IdP `name` claim through
     `DISPLAY_NAME` (claim -> email local part -> email) instead of storing it raw.
5. Templates: `maxlength="255"` on the settings display-name field (a real bug - the browser accepted 155
   characters the server rejected) and the two hard-coded action-name `100`s now come from the catalogue.
6. `V24__narrow_display_name.sql` narrows `users.display_name` to `varchar(100)`, with
   `@Column(length = TextFields.DISPLAY_NAME_MAX_LENGTH)` on the entity and `TextFieldsSchemaIT` pinning every
   bound to its column width in both directions.
7. `V25__display_name_max_50.sql` tightens the display name again, from 100 to **50**, sized so the name always
   fits the desktop navbar (which renders it in full beside the nav links, untruncated). Unlike the 255 -> 100 step,
   this one CAN find rows that no longer fit, so the migration cuts them to 50 characters before narrowing the
   column - see the data note below.
8. The shared content policy: `TextRules.NO_INVISIBLE_CHARACTERS` and `TextRules.NO_STACKED_MARKS` on every cleaned
   field, Unicode-aware normalisation, `ActionResult.InvalidName` so an action name's content rejection can be
   worded on both surfaces (it previously fell through to the "too long" banner), the stat-name rejection worded from
   the pipeline rather than always as a length failure, and code-point counting in the two client-side evaluators.
   Covered by `NaughtyStringsTest`, `TextRulesTest`, two `SurfaceParityIT` cases and two `actions.spec.ts` E2E cases.
9. **`Normalisation.MULTILINE` + `TextFields.NOTE`** (2026-08-05), added for the per-date notes feature - see
   [`NOTES.md`](NOTES.md). The first multi-line input in the app: `TextField.multiline(...)`,
   `TextRules.NO_INVISIBLE_CHARACTERS_ALLOWING_NEWLINE` (the original rule parameterised, not copied), the
   `NOTE_MAX_LENGTH` bound of 10,000 pinned to `notes.content` by `TextFieldsSchemaIT`, and coverage in
   `TextFieldExtensionsTest` (the normalisation pass, step by step and in order), `TextRulesTest` (the exemption is
   exactly one code point wide) and `NaughtyStringsTest` (a note rejects every invisible character the other fields
   reject).
   > **Both halves of that bound sentence have since changed** and are kept only as history: `V28` dropped
   > `notes.content` (so `TextFieldsSchemaIT` now asserts the column's ABSENCE, there being no width to pin a sealed
   > `bytea` against), and 10,000 became the DEFAULT of a per-deployment `NOTE_MAX_LENGTH` rather than the bound
   > itself - which is precisely what the missing column made possible. See item 10.
10. **A per-deployment note bound + a counter preference** (2026-08-14/15). `NOTE_MAX_LENGTH` (`config/NotesConfig`
    -> `note/NoteField`, range-checked at startup) makes `TextFields.NOTE` the only catalogue entry whose maximum is
    not a compile-time constant, and `User.showNoteCounter` decides whether the note box shows its counter at all.
    Both are documented in full - including what happens to notes already stored above a lowered bound - in
    [`NOTES.md`](NOTES.md).

### Deliberately out of scope

- **Existing stored values are NOT normalised retroactively.** Normalisation applies on next write only; there is
  no data-fixing migration for it. The 255 -> 100 column narrowing needed none either: no row could exceed 100,
  because the old validator already capped it there.

- **The 100 -> 50 display-name change is the one migration that DOES rewrite stored data.** A name of 51-100
  characters was legal when it was saved, so `V25` cuts any such name to 50 characters before narrowing the column
  (the `ALTER` would fail on the row otherwise). Affected users see their name shortened and can set a new one in
  Settings. Nothing else in the app truncates a value a user typed - this is a one-off consequence of tightening a
  bound after the fact, not a change to the reject-never-truncate rule.
- Email validation stays at its current strength (shape only, no deliverability check); it moved into the
  catalogue without becoming stricter.
- Mixed-script/confusable detection (a Cyrillic `a` in an otherwise Latin name) is **out of scope** - see the
  homoglyph row above.
